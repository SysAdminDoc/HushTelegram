/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.settings;

import android.os.SystemClock;

/** Hand clocks for the settings lock, and a way to put the lock back the way it started. */
final class SettingsLockForTests {
    private SettingsLockForTests() {
    }

    /** Points the lock's wall clock and its time since start both at {@code now[0]}, which the test moves. */
    static void handClocks(long[] now) {
        SettingsLock.clock = () -> now[0];
        SettingsLock.uptime = () -> now[0];
    }

    /** Forgets everything the lock stored and puts the real clocks back. */
    static void forget() {
        SettingsLock.Stored.LOCKED.resetToDefault();
        SettingsLock.Stored.UNLOCK_STARTED.resetToDefault();
        SettingsLock.Stored.LOCKED_AT.resetToDefault();
        SettingsLock.Stored.LOCKED_AT_UPTIME.resetToDefault();
        SettingsLock.clock = System::currentTimeMillis;
        SettingsLock.uptime = SystemClock::elapsedRealtime;
    }
}
