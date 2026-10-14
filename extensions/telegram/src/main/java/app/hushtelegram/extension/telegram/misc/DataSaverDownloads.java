/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import app.hushtelegram.extension.shared.Logger;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Holds Telegram's automatic media downloads while Android's Data Saver restricts Telegram on a
 * metered network.
 *
 * <p>DownloadController answers whether a photo, video, file, voice message, song or story starts
 * downloading on its own: when a chat shows it, when a new message is put in the download queue,
 * and when that queue is read back to start. The patch asks {@link #hold} at the start of each
 * answer and answers no on yes, the way Telegram answers when automatic downloads are off for the
 * network. The few places that read the network's download mask themselves get 0 from
 * {@link #mask}. Telegram's own check when the network changes still reads its real mask, so its
 * queue is cancelled or picked up the way it always is, and nothing is restarted from here. A
 * download already running finishes, and what you tap still downloads, since a tap doesn't ask.
 *
 * <p>The hold needs the switch on, HushTelegram not paused, Android reporting Data Saver on for
 * Telegram and the active network metered. Data Saver off or allowing Telegram, an unmetered
 * network, or anything Android won't say leaves Telegram's own rules. Telegram's network type, its
 * data statistics and calls aren't touched.
 */
public final class DataSaverDownloads {
    private DataSaverDownloads() {}

    /** How long one answer from Android is used. Chat cells ask while they lay out, so it isn't asked each time. */
    static final long FRESH_MS = 1000;

    /** What Android says about Data Saver for Telegram. */
    public enum DataSaver { ON, OFF, ALLOWED, UNKNOWN }

    /** Android's two facts, as the settings row shows them. */
    public static final class Status {
        public final DataSaver dataSaver;
        /** Whether the active network is metered, or null when Android won't say. */
        @Nullable public final Boolean metered;

        Status(DataSaver dataSaver, @Nullable Boolean metered) {
            this.dataSaver = dataSaver;
            this.metered = metered;
        }

        /** Whether these facts hold automatic downloads, the switch aside. */
        public boolean restricts() {
            return dataSaver == DataSaver.ON && Boolean.TRUE.equals(metered);
        }
    }

    /** The last answer and when it was read, kept together so a reader never mixes two readings. */
    private static final class Reading {
        final long at;
        final boolean restricts;

        Reading(long at, boolean restricts) {
            this.at = at;
            this.restricts = restricts;
        }
    }

    @Nullable private static volatile Reading last;

    /**
     * Injected at the start of DownloadController's answers about automatic downloads.
     *
     * @return true to answer no, so nothing starts downloading on its own
     */
    public static boolean hold() {
        HookStatus.invoked(FamilyNames.FASTER_DOWNLOADS);
        try {
            if (!Utils.settingsReady() || !Settings.DATA_SAVER_DOWNLOADS.get()) return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FASTER_DOWNLOADS, "Data Saver switch", failure);
            return false;
        }
        if (!restricted()) return false;
        HookStatus.counted(FamilyNames.FASTER_DOWNLOADS, "automatic download held for Data Saver");
        return true;
    }

    /**
     * Injected right after a read of the network's download mask outside DownloadController.
     *
     * @return no media types while the hold is on, and {@code mask} otherwise
     */
    public static int mask(int mask) {
        return mask != 0 && hold() ? 0 : mask;
    }

    /** Whether Android restricts Telegram now, asked at most once every {@link #FRESH_MS}. */
    private static boolean restricted() {
        long now = SystemClock.elapsedRealtime();
        Reading reading = last;
        if (reading != null && now - reading.at >= 0 && now - reading.at < FRESH_MS) return reading.restricts;
        boolean restricts = status(Utils.getContext()).restricts();
        last = new Reading(now, restricts);
        return restricts;
    }

    /** Drops the last answer, so the next question asks Android again. */
    static void forget() {
        last = null;
    }

    /** What Android says now. Each fact it won't give is unknown, which never holds anything. */
    public static Status status(@Nullable Context context) {
        DataSaver dataSaver = DataSaver.UNKNOWN;
        Boolean metered = null;
        ConnectivityManager connectivity = null;
        try {
            if (context != null) connectivity = context.getSystemService(ConnectivityManager.class);
        } catch (RuntimeException failure) {
            Logger.printDebug(() -> "Data Saver: no connectivity service: " + failure);
        }
        if (connectivity == null) return new Status(dataSaver, null);
        try {
            switch (connectivity.getRestrictBackgroundStatus()) {
                case ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED:
                    dataSaver = DataSaver.ON;
                    break;
                case ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED:
                    dataSaver = DataSaver.ALLOWED;
                    break;
                case ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED:
                    dataSaver = DataSaver.OFF;
                    break;
                default:
                    break;
            }
        } catch (RuntimeException failure) {
            Logger.printDebug(() -> "Data Saver: status unreadable: " + failure);
        }
        try {
            metered = connectivity.isActiveNetworkMetered();
        } catch (RuntimeException failure) {
            Logger.printDebug(() -> "Data Saver: metered unreadable: " + failure);
        }
        return new Status(dataSaver, metered);
    }
}
