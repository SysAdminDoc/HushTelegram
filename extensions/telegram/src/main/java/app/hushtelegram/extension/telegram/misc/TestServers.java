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
 * Telegram's sign-in phone screen has a Test Backend checkbox that only shows while the account
 * being added already sits on Telegram's test servers. With the switch on, it shows for every new
 * sign-in. Ticking it moves only that account's connection to the test servers, the way Telegram's
 * own box does, so test sessions stay apart from the accounts already signed in.
 */
public final class TestServers {
    private TestServers() {}

    /**
     * Asked with Telegram's own answer to whether this account is on the test servers, right where
     * the sign-in screen decides to add the checkbox. True shows it. Telegram's yes always stays a
     * yes, so a test account keeps its box whatever the switch says.
     */
    public static boolean shown(int stock) {
        HookStatus.invoked(FamilyNames.TEST_SERVER_SIGN_IN);
        if (stock != 0) return true;
        try {
            return Utils.settingsReady() && Settings.TEST_SERVER_SIGN_IN.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TEST_SERVER_SIGN_IN, "switch", failure);
            return false;
        }
    }
}
