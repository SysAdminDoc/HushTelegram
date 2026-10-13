/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

/** Asks {@link OutsideTranslate} whether it would act, since the unpatched app has no messages to show. */
public final class OutsideTranslateForTests {
    private OutsideTranslateForTests() {}

    /** Whether the switch lets the translator work now. */
    public static boolean active() {
        return OutsideTranslate.active();
    }

    /** Turns a chat on or off in the account with user ID [owner], the way its header item does. */
    public static boolean toggleChat(long owner, long dialog) {
        return OutsideTranslate.toggleChat(owner, dialog);
    }

    public static boolean chatOn(long owner, long dialog) {
        return OutsideTranslate.chatOn(owner, dialog);
    }

    public static void reset() {
        OutsideTranslate.resetForTests();
    }
}
