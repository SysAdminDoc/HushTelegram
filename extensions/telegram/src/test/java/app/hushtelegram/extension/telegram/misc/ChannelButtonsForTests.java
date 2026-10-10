/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

/** Asks {@link ChannelButtons} and {@link SendAs} about their switches, since the unpatched app never calls them. */
public final class ChannelButtonsForTests {
    private ChannelButtonsForTests() {}

    /** Whether the channel bar's side buttons would go. */
    public static boolean on() {
        return ChannelButtons.on();
    }

    /** Whether the Send as button would go while you post as yourself. */
    public static boolean sendAsOn() {
        return SendAs.on();
    }
}
