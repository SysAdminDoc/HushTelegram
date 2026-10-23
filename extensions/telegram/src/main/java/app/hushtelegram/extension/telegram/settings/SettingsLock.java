/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.settings;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.function.LongSupplier;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.settings.BaseSettings;
import app.hushtelegram.extension.shared.settings.BooleanSetting;
import app.hushtelegram.extension.shared.settings.LongSetting;
import app.hushtelegram.extension.shared.settings.Setting;

/**
 * A lock with a waiting period on HushTelegram's settings, for someone who wants their switches to
 * stay put when an urge says otherwise. Locked, every setting on the screen is read-only. Start
 * unlock begins a 24-hour wait that can be canceled while it runs, and once it has run the lock is
 * off. Only the flag and the wall-clock time the wait started are stored, and a clock that reads
 * earlier than that start begins the wait again from now.
 *
 * <p>For the first {@link #QUICK_UNLOCK_MILLIS} after locking, the same row unlocks at once, so a
 * tap made in a hurry costs nothing (#23). That window is held to the wall clock and to the time
 * since the phone started, both saved as the lock went on: a clock set back or forward, or a
 * restart, closes it early, and none of them opens it again.
 *
 * <p>Nothing here changes what Telegram does. The settings screen asks {@link #refuses()} before
 * any change it would make. Resume and Debug logging are always let through ({@link #exempts}).
 */
public final class SettingsLock {
    /** How long an unlock waits. */
    static final long WAIT_MILLIS = 24L * 60 * 60 * 1000;
    /** How long after locking a tap still unlocks at once. */
    static final long QUICK_UNLOCK_MILLIS = 10L * 60 * 1000;

    enum State {
        /** Settings change as usual. */
        OFF,
        /** Settings are read-only, locked moments ago, and a tap on the row unlocks them at once. */
        JUST_LOCKED,
        /** Settings are read-only, and no unlock has started. */
        LOCKED,
        /** Settings are read-only until the wait that started at {@link Stored#UNLOCK_STARTED} has run. */
        UNLOCKING,
    }

    /** What the lock keeps between starts. None goes in a settings file, and a pause leaves them alone. */
    static final class Stored {
        static final BooleanSetting LOCKED =
                new BooleanSetting("hushtelegram_settings_locked", Boolean.FALSE, false, false);
        /** When the unlock's wait started, by the wall clock, or 0 while none has. */
        static final LongSetting UNLOCK_STARTED =
                new LongSetting("hushtelegram_settings_unlock_started", 0L, false, false);
        /** When the lock went on, by the wall clock, while a tap still unlocks at once, or 0 after. */
        static final LongSetting LOCKED_AT =
                new LongSetting("hushtelegram_settings_locked_at", 0L, false, false);
        /** When the lock went on, by the time since the phone started, kept beside {@link #LOCKED_AT}. */
        static final LongSetting LOCKED_AT_UPTIME =
                new LongSetting("hushtelegram_settings_locked_at_uptime", 0L, false, false);

        static {
            Setting.keepWhenPaused(LOCKED, UNLOCK_STARTED, LOCKED_AT, LOCKED_AT_UPTIME);
        }

        private Stored() {
        }
    }

    /** The wall clock. A test sets its own. */
    static volatile LongSupplier clock = System::currentTimeMillis;
    /** The time since the phone started, which no clock setting moves. A test sets its own. */
    static volatile LongSupplier uptime = SystemClock::elapsedRealtime;

    private SettingsLock() {
    }

    /**
     * Where the lock stands now. A wait that has run turns the lock off here, and one that started
     * after what the clock now reads starts again from now. A quick-unlock window that has closed
     * is forgotten here.
     */
    static synchronized State state() {
        long started = Stored.UNLOCK_STARTED.savedValue();
        if (!Stored.LOCKED.savedValue()) {
            if (started != 0L) Stored.UNLOCK_STARTED.save(0L);
            if (Stored.LOCKED_AT.savedValue() != 0L) forgetQuickUnlock();
            return State.OFF;
        }
        if (started == 0L) {
            if (Stored.LOCKED_AT.savedValue() == 0L) return State.LOCKED;
            if (quickUnlockLeft() > 0L) return State.JUST_LOCKED;
            forgetQuickUnlock();
            return State.LOCKED;
        }
        long now = clock.getAsLong();
        if (now < started) {
            Stored.UNLOCK_STARTED.save(startOf(now));
            return State.UNLOCKING;
        }
        if (now - started < WAIT_MILLIS) return State.UNLOCKING;
        // The wait has run. The flag goes first: a start time left behind without it means nothing.
        if (!Stored.LOCKED.save(false)) return State.UNLOCKING;
        Stored.UNLOCK_STARTED.save(0L);
        return State.OFF;
    }

    /** Whether the settings are read-only now. */
    public static boolean locked() {
        return state() != State.OFF;
    }

    /** How long the unlock under way has left, or 0 when none is. */
    static synchronized long remaining() {
        if (state() != State.UNLOCKING) return 0L;
        long left = WAIT_MILLIS - (clock.getAsLong() - Stored.UNLOCK_STARTED.savedValue());
        return Math.max(0L, Math.min(WAIT_MILLIS, left));
    }

    /**
     * How long the quick-unlock window has left, or 0 once it's closed. Whichever clock has moved
     * further counts, and either one reading earlier than the lock closes it.
     */
    private static long quickUnlockLeft() {
        long lockedAt = Stored.LOCKED_AT.savedValue();
        long lockedAtUptime = Stored.LOCKED_AT_UPTIME.savedValue();
        long now = clock.getAsLong();
        long up = uptime.getAsLong();
        if (lockedAt == 0L || now < lockedAt || up < lockedAtUptime) return 0L;
        return Math.max(0L, QUICK_UNLOCK_MILLIS - Math.max(now - lockedAt, up - lockedAtUptime));
    }

    /** Closes the quick-unlock window. The lock itself stays as it is. */
    private static void forgetQuickUnlock() {
        // The wall-clock time goes first: an uptime left behind without it means nothing.
        if (Stored.LOCKED_AT.save(0L)) Stored.LOCKED_AT_UPTIME.save(0L);
    }

    /** Locks the settings and opens the quick-unlock window. Answers whether they're locked now. */
    static synchronized boolean lock() {
        if (state() != State.OFF) return true;
        // The window is saved before the flag, so a lock that took always comes with it.
        return Stored.LOCKED_AT.save(startOf(clock.getAsLong())) && Stored.LOCKED_AT_UPTIME.save(uptime.getAsLong())
                && Stored.UNLOCK_STARTED.save(0L) && Stored.LOCKED.save(true);
    }

    /** Takes the lock off at once, while the quick-unlock window is open. Answers whether the settings are open now. */
    static synchronized boolean unlockNow() {
        State state = state();
        if (state != State.JUST_LOCKED) return state == State.OFF;
        if (!Stored.LOCKED.save(false)) return false;
        forgetQuickUnlock();
        return true;
    }

    /** Starts the wait, when the settings are locked and none has started. Answers whether one is under way. */
    static synchronized boolean startUnlock() {
        State state = state();
        if (state == State.OFF || state == State.UNLOCKING) return state == State.UNLOCKING;
        if (!Stored.UNLOCK_STARTED.save(startOf(clock.getAsLong()))) return false;
        forgetQuickUnlock();
        return true;
    }

    /** Stops the wait under way. The settings stay locked. Answers whether they're locked with no wait now. */
    static synchronized boolean cancelUnlock() {
        State state = state();
        if (state == State.OFF) return false;
        return state != State.UNLOCKING || Stored.UNLOCK_STARTED.save(0L);
    }

    /**
     * The lock row's tap, by where the lock stands: off, it locks; just locked, it unlocks at once;
     * locked, it starts the wait; waiting, it cancels the wait. Answers the note that says what
     * happened. A wait that ran out just before a tap on Cancel unlock locks again, which is what
     * that tap asked for.
     */
    static String advance() {
        switch (state()) {
            case OFF:
                return lock() ? L10n.f("Settings locked. Changed your mind? Tap again within %1$d min.", quickUnlockMinutes())
                        : couldNot();
            case JUST_LOCKED:
                return unlockNow() ? L10n.t("Settings unlocked. They change as usual again.") : couldNot();
            case LOCKED:
                return startUnlock() ? L10n.t("Unlock started. Settings open in 24 hours unless you cancel.") : couldNot();
            default:
                return cancelUnlock() ? L10n.t("Unlock canceled. Settings stay locked.") : couldNot();
        }
    }

    /**
     * The switch changes a lock lets through: Pause turned off, since resuming only puts the other
     * switches back to work, and Debug logging either way, since it only changes what a diagnostic
     * report holds.
     */
    static boolean exempts(@Nullable String key, Object newValue) {
        if (BaseSettings.DEBUG.key.equals(key)) return true;
        return BaseSettings.PAUSED.key.equals(key) && Boolean.FALSE.equals(newValue);
    }

    /**
     * Whether a change has to be turned down because the settings are locked. When it does, a short
     * note says so and how the lock comes off, and the caller leaves the setting as it is.
     */
    public static boolean refuses() {
        State state = state();
        if (state == State.OFF) return false;
        Utils.showToastLong(note(state));
        return true;
    }

    /** What a turned-down change says. */
    static String note(State state) {
        if (state == State.JUST_LOCKED) {
            return L10n.f("Settings are locked. Tap Unlock now within %1$d min.", quickUnlockMinutesLeft());
        }
        if (state == State.UNLOCKING) {
            long minutes = minutesLeft();
            return L10n.f("Settings are locked. They unlock in %1$d h %2$d min.", minutes / 60, minutes % 60);
        }
        return L10n.t("Settings are locked. Tap Start unlock, then wait 24 hours.");
    }

    /** The lock row's title. */
    static String title(State state) {
        switch (state) {
            case OFF: return L10n.t("Lock HushTelegram settings");
            case JUST_LOCKED: return L10n.t("Unlock now");
            case LOCKED: return L10n.t("Start unlock");
            default: return L10n.t("Cancel unlock");
        }
    }

    /** The lock row's line under its title. */
    static String summary(State state) {
        switch (state) {
            case OFF:
                return L10n.f("Makes every setting here read-only. A tap within %1$d min unlocks them again. After that, unlocking takes 24 hours.",
                        quickUnlockMinutes());
            case JUST_LOCKED:
                return L10n.f("Locked just now. Tap within %1$d min to unlock right away. After that, unlocking takes 24 hours.",
                        quickUnlockMinutesLeft());
            case LOCKED:
                return L10n.t("Your settings are locked. Tap to start the 24-hour wait. You can cancel it while it runs.");
            default:
                long minutes = minutesLeft();
                return L10n.f("Settings unlock in %1$d h %2$d min. Tap to cancel and keep them locked.",
                        minutes / 60, minutes % 60);
        }
    }

    /** Whole minutes left, rounded up, so the last seconds of a wait still read as a minute. */
    private static long minutesLeft() {
        return (remaining() + 59_999L) / 60_000L;
    }

    /** The quick-unlock window's length in minutes. */
    private static long quickUnlockMinutes() {
        return QUICK_UNLOCK_MILLIS / 60_000L;
    }

    /** Whole minutes the quick-unlock window has left, rounded up like {@link #minutesLeft()}. */
    private static synchronized long quickUnlockMinutesLeft() {
        return (quickUnlockLeft() + 59_999L) / 60_000L;
    }

    /** 0 means no wait, so a clock at or before 1970 starts one a millisecond after. */
    private static long startOf(long now) {
        return Math.max(now, 1L);
    }

    private static String couldNot() {
        return L10n.t("Couldn't change the lock. Try again in a moment.");
    }
}
