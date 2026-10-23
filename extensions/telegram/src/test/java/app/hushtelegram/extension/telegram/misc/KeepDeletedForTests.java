/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.text.Spanned;
import java.util.Map;

/** Lets tests outside this package measure a kept message's time. */
public final class KeepDeletedForTests {
    private KeepDeletedForTests() {}

    /** Whether a kept message's label, measured now, comes back red. */
    public static boolean paintsRed() {
        KeepDeleted.Persist persist = KeepDeleted.persist;
        KeepDeleted.Keys keys = KeepDeleted.keys;
        KeepDeleted.persist = new KeepDeleted.Persist() {
            @Override public String read(String key) { return key.equals("kept_99") ? "10:1" : ""; }

            @Override public boolean write(Map<String, String> entries) { return true; }
        };
        KeepDeleted.keys = message -> new long[] {99, 10, 1};
        KeepDeleted.forgetAllForTests();
        try {
            KeepDeleted.measuring(new Object());
            return KeepDeleted.painted(KeepDeleted.labelled("9:41 PM")) instanceof Spanned;
        } finally {
            KeepDeleted.measuring(null);
            KeepDeleted.persist = persist;
            KeepDeleted.keys = keys;
            KeepDeleted.forgetAllForTests();
        }
    }
}
