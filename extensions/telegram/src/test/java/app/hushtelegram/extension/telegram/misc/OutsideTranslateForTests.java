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
}
