/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.util.List;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Keeps Telegram's usage reports on the phone.
 *
 * <p>When the server's app config turns on {@code collectDeviceStats}, the messages controller's
 * {@code logDeviceStats()} reads the phone's storage directories and sends what it found as a
 * {@code help.saveAppLog} event. While you scroll a channel, Telegram also times how long each post
 * stays on screen and sends the batch as {@code messages.reportReadMetrics}. Both ask this class
 * first, and while the switch is on neither is read nor sent. Messages, calls, view counts and
 * everything else Telegram needs go on as before.
 */
public final class Analytics {
    private Analytics() {}

    /** Injected at the start of {@code logDeviceStats()}. True means return at once. Never throws. */
    public static boolean skipDeviceStats() {
        return skip("device stats report skipped");
    }

    /**
     * Injected where a channel's read metrics are about to go out, with the batch waiting to be
     * sent. True means return without sending; the batch is emptied first, as Telegram empties it
     * after a send, so it doesn't pile up while you stay in the channel. Never throws.
     */
    public static boolean skipReadMetrics(List<?> pending) {
        if (!skip("read metrics report skipped")) return false;
        try {
            if (pending != null) pending.clear();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.DISABLE_ANALYTICS, "read metrics clear", t);
        }
        return true;
    }

    private static boolean skip(String what) {
        HookStatus.invoked(FamilyNames.DISABLE_ANALYTICS);
        try {
            if (!Utils.settingsReady() || !Settings.DISABLE_ANALYTICS.get()) return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.DISABLE_ANALYTICS, "switch read", t);
            return false;
        }
        HookStatus.counted(FamilyNames.DISABLE_ANALYTICS, what);
        return true;
    }
}
