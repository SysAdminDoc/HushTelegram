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
 * Bypasses content restrictions (no forwards, no saving, no copying) on channels and chats.
 * Hooks the three places Telegram checks: {@code MessagesController.isChatNoForwards},
 * {@code MessagesController.isPeerNoForwards} and the {@code noforwards} field on messages.
 * When the switch is on, all three answer false so forwarding, copying and saving work as if
 * the restriction was never set. The existing Allow screenshots patch handles FLAG_SECURE
 * separately.
 */
public final class ChannelRestrictions {
    private ChannelRestrictions() {
    }

    /**
     * Called where Telegram asks whether a chat forbids forwarding. Returns 0 (false) when the
     * switch is on, letting the stock answer through otherwise.
     */
    public static int isChatNoForwards(int stockAnswer) {
        if (stockAnswer == 0) return 0;
        try {
            if (!Utils.settingsReady() || !Settings.BYPASS_CHANNEL_RESTRICTIONS.get()) return stockAnswer;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BYPASS_CHANNEL_RESTRICTIONS, "switch read", t);
            return stockAnswer;
        }
        HookStatus.counted(FamilyNames.BYPASS_CHANNEL_RESTRICTIONS, "chat restriction bypassed");
        return 0;
    }

    /**
     * Called where Telegram reads a message's noforwards flag. Returns 0 (false) when the
     * switch is on.
     */
    public static int noForwards(int stockAnswer) {
        if (stockAnswer == 0) return 0;
        try {
            if (!Utils.settingsReady() || !Settings.BYPASS_CHANNEL_RESTRICTIONS.get()) return stockAnswer;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BYPASS_CHANNEL_RESTRICTIONS, "switch read", t);
            return stockAnswer;
        }
        HookStatus.counted(FamilyNames.BYPASS_CHANNEL_RESTRICTIONS, "message restriction bypassed");
        return 0;
    }
}
