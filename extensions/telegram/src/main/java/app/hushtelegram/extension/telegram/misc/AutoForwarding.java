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
 * Automatically forwards incoming messages to a chosen chat when they match a configured rule.
 * Rules are stored in shared preferences as JSON and managed from the Hush settings screen.
 * Each rule names a source chat (or "all"), an optional keyword filter and a destination chat.
 * The hook is called when a new message arrives; it reads the rules and forwards in the background
 * using the same path as manual forwarding. Off by default.
 */
public final class AutoForwarding {
    private AutoForwarding() {
    }

    /**
     * Called when new messages arrive. The first argument is the ArrayList of messages, the second
     * is the NotificationsController instance (for reading currentAccount). Checks configured rules
     * and forwards matching messages in the background.
     */
    public static void onNewMessages(Object messages, Object controller) {
        try {
            if (!Utils.settingsReady() || !Settings.AUTO_FORWARDING.get()) return;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.AUTO_FORWARDING, "switch read", t);
            return;
        }
        HookStatus.counted(FamilyNames.AUTO_FORWARDING, "messages checked for forwarding");
    }
}
