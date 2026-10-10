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
 * In a group or channel where you can post as a channel you run, Telegram puts the picture of whoever
 * you'd post as next to the message box, and tapping it picks someone else. With the switch on the
 * picture is left out while that's you. Once a channel is picked it shows again, so a message never
 * goes out under a channel's name without the picture saying so.
 */
public final class SendAs {
    private SendAs() {}

    /**
     * Asked each time the message box decides whether the Send as button shows.
     *
     * @param identity who you'd post as, Telegram's peer, or null when there's no choice
     * @param shown whether Telegram would show the button
     * @return whether it shows
     */
    public static boolean show(Object identity, boolean shown) {
        if (!on() || !shown || identity == null) return shown;
        try {
            return !hides(isUser(identity));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_SEND_AS, "identity", failure);
            return true;
        }
    }

    /** Whether the button goes, given whether you'd post as a person. The only person a Send as list holds is you. */
    static boolean hides(boolean person) {
        if (!person) return false;
        HookStatus.counted(FamilyNames.HIDE_SEND_AS, "Send as button hidden");
        return true;
    }

    /** Whether the switch is on and HushTelegram isn't paused. */
    static boolean on() {
        HookStatus.invoked(FamilyNames.HIDE_SEND_AS);
        try {
            return Utils.settingsReady() && Settings.HIDE_SEND_AS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_SEND_AS, "switch", failure);
            return false;
        }
    }

    /** Whether Telegram's peer is a person rather than a channel or group. Replaced when patching. */
    public static boolean isUser(Object identity) { return false; }
}
