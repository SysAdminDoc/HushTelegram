/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Keeps Telegram's device statistics on the phone.
 *
 * <p>When the server's app config turns on {@code collectDeviceStats}, the messages controller's
 * {@code logDeviceStats()} reads the phone's storage directories and sends what it found as a
 * {@code help.saveAppLog} event. That method asks this class first, and while the switch is on it
 * returns without reading or sending anything. Messages, calls and everything else Telegram
 * needs go on as before.
 */
public final class Analytics {
    private Analytics() {}

    /** Injected at the start of {@code logDeviceStats()}. True means return at once. Never throws. */
    public static boolean skipDeviceStats() {
        HookStatus.invoked(FamilyNames.DISABLE_ANALYTICS);
        try {
            if (!Utils.settingsReady() || !Settings.DISABLE_ANALYTICS.get()) return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.DISABLE_ANALYTICS, "switch read", t);
            return false;
        }
        HookStatus.counted(FamilyNames.DISABLE_ANALYTICS, "device stats report skipped");
        return true;
    }
}
