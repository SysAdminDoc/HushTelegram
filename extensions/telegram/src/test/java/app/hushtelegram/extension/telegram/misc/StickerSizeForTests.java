/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

/** Asks {@link StickerSize} about its switch, since the unpatched app never calls it. */
public final class StickerSizeForTests {
    private StickerSizeForTests() {}

    /** Whether stickers would take the picked size. */
    public static boolean on() {
        return StickerSize.on();
    }
}
