/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.settings;

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
 * <p>Nothing here changes what Telegram does. The settings screen asks {@link #refuses()} before
 * any change it would make. Resume and Debug logging are always let through ({@link #exempts}).
 */
public final class SettingsLock {
    /** How long an unlock waits. */
    static final long WAIT_MILLIS = 24L * 60 * 60 * 1000;

    enum State {
        /** Settings change as usual. */
        OFF,
        /** Settings are read-only, and no unlock has started. */
        LOCKED,
        /** Settings are read-only until the wait that started at {@link Stored#UNLOCK_STARTED} has run. */
        UNLOCKING,
    }

    /** What the lock keeps between starts. Neither goes in a settings file, and a pause leaves both alone. */
    static final class Stored {
        static final BooleanSetting LOCKED =
                new BooleanSetting("hushtelegram_settings_locked", Boolean.FALSE, false, false);
        /** When the unlock's wait started, by the wall clock, or 0 while none has. */
        static final LongSetting UNLOCK_STARTED =
                new LongSetting("hushtelegram_settings_unlock_started", 0L, false, false);

        static {
            Setting.keepWhenPaused(LOCKED, UNLOCK_STARTED);
        }

        private Stored() {
        }
    }

    /** The wall clock. A test sets its own. */
    static volatile LongSupplier clock = System::currentTimeMillis;

    private SettingsLock() {
    }

    /**
     * Where the lock stands now. A wait that has run turns the lock off here, and one that started
     * after what the clock now reads starts again from now.
     */
    static synchronized State state() {
        long started = Stored.UNLOCK_STARTED.savedValue();
        if (!Stored.LOCKED.savedValue()) {
            if (started != 0L) Stored.UNLOCK_STARTED.save(0L);
            return State.OFF;
        }
        if (started == 0L) return State.LOCKED;
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

    /** Locks the settings. Answers whether they're locked now. */
    static synchronized boolean lock() {
        if (state() != State.OFF) return true;
        return Stored.UNLOCK_STARTED.save(0L) && Stored.LOCKED.save(true);
    }

    /** Starts the wait, when the settings are locked and none has started. Answers whether one is under way. */
    static synchronized boolean startUnlock() {
        State state = state();
        if (state != State.LOCKED) return state == State.UNLOCKING;
        return Stored.UNLOCK_STARTED.save(startOf(clock.getAsLong()));
    }

    /** Stops the wait under way. The settings stay locked. Answers whether they're locked with no wait now. */
    static synchronized boolean cancelUnlock() {
        State state = state();
        if (state == State.OFF) return false;
        return state == State.LOCKED || Stored.UNLOCK_STARTED.save(0L);
    }

    /**
     * The lock row's tap, by where the lock stands: off, it locks; locked, it starts the wait;
     * waiting, it cancels the wait. Answers the note that says what happened. A wait that ran out
     * just before a tap on Cancel unlock locks again, which is what that tap asked for.
     */
    static String advance() {
        switch (state()) {
            case OFF:
                return lock() ? L10n.t("Settings locked. Unlocking takes 24 hours.") : couldNot();
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
            case LOCKED: return L10n.t("Start unlock");
            default: return L10n.t("Cancel unlock");
        }
    }

    /** The lock row's line under its title. */
    static String summary(State state) {
        switch (state) {
            case OFF:
                return L10n.t("Makes every setting here read-only. To change one again, start an unlock here and wait 24 hours.");
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

    /** 0 means no wait, so a clock at or before 1970 starts one a millisecond after. */
    private static long startOf(long now) {
        return Math.max(now, 1L);
    }

    private static String couldNot() {
        return L10n.t("Couldn't change the lock. Try again in a moment.");
    }
}
