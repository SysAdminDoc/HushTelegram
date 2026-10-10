/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

/** Asks {@link MessageFilters} about its switch, since the unpatched app never calls it. */
public final class MessageFiltersForTests {
    private MessageFiltersForTests() {}

    /** Whether messages that match a filter would be left out. */
    public static boolean on() {
        return MessageFilters.on();
    }
}
