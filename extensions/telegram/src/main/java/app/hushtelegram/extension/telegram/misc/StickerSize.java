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
 * Stickers in chats at a size the person picks. Telegram fits a sticker into a square half as wide
 * as the smaller of the chat and the screen's height (0.4 of a tablet's shorter side). With the
 * switch on, that square is the chosen share of it instead, and the sticker's own shape is fitted
 * into it as before. Animated emoji and dice go through the same code with sizes of their own,
 * and keep them.
 */
public final class StickerSize {
    private StickerSize() {}

    /** The sizes a person can pick, as a percent of Telegram's. */
    public static final int[] CHOICES = {50, 75, 125, 150};
    /** The size picked until the person picks another. */
    public static final int DEFAULT = 75;

    /**
     * Asked once a bubble knows the largest side a sticker may take.
     *
     * @param message Telegram's message
     * @param size the largest side Telegram allows, in pixels
     * @return that size, or the picked share of it for a sticker
     */
    public static float size(Object message, float size) {
        if (message == null || !on()) return size;
        try {
            if (emoji(message)) return size;
            int percent = percent(Settings.STICKER_SIZE.get());
            HookStatus.counted(FamilyNames.CHANGE_STICKER_SIZE, "sticker resized");
            return size * percent / 100f;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHANGE_STICKER_SIZE, "sticker", failure);
            return size;
        }
    }

    /** A saved size that isn't one of {@link #CHOICES}, from an old or hand-edited store, reads as the default. */
    public static int percent(int saved) {
        for (int choice : CHOICES) if (choice == saved) return saved;
        return DEFAULT;
    }

    /** Whether the switch is on and HushTelegram isn't paused. */
    static boolean on() {
        HookStatus.invoked(FamilyNames.CHANGE_STICKER_SIZE);
        try {
            return Utils.settingsReady() && Settings.CHANGE_STICKER_SIZE.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHANGE_STICKER_SIZE, "switch", failure);
            return false;
        }
    }

    /** Whether the message is an animated emoji or a dice. Replaced when patching. */
    public static boolean emoji(Object message) { return false; }
}
