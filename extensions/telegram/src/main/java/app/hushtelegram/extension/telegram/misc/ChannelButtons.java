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
 * A channel's bottom bar has Mute or Join in the middle and up to four buttons around it: Search,
 * Gift, Direct messages and Info. With the switch on the chat screen leaves Search, Direct messages
 * and Info out each time it updates the bar. Gift belongs to Hide Premium, gifts and Stars, and the
 * channel's Recent actions log, which builds the same bar for a Search of its own, isn't touched.
 */
public final class ChannelButtons {
    private ChannelButtons() {}

    /**
     * Takes the place of the chat screen's call that shows or hides one button of the bar.
     *
     * @param bar Telegram's bottom bar
     * @param button the button's number
     * @param shown whether Telegram would show it
     * @param animated whether the change animates
     */
    public static void set(Object bar, int button, boolean shown, boolean animated) {
        place(bar, button, shown && !hidden(button, sideButtons()), animated);
    }

    /** Whether the switch is on and the button is one of the side buttons it hides, one bit each in {@code sides}. */
    static boolean hidden(int button, int sides) {
        if (!on()) return false;
        try {
            if (button < 0 || button > 30 || (sides & 1 << button) == 0) return false;
            HookStatus.counted(FamilyNames.HIDE_CHANNEL_BUTTONS, "channel bar button hidden");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_CHANNEL_BUTTONS, "button", failure);
            return false;
        }
    }

    /** Whether the switch is on and HushTelegram isn't paused. */
    static boolean on() {
        HookStatus.invoked(FamilyNames.HIDE_CHANNEL_BUTTONS);
        try {
            return Utils.settingsReady() && Settings.HIDE_CHANNEL_BUTTONS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_CHANNEL_BUTTONS, "switch", failure);
            return false;
        }
    }

    /** One bit for each side button's number: Search, Direct messages and Info. Replaced when patching. */
    public static int sideButtons() { return 0; }

    /** Telegram's own call that shows or hides a button of the bar. Replaced when patching. */
    public static void place(Object bar, int button, boolean shown, boolean animated) {}
}
