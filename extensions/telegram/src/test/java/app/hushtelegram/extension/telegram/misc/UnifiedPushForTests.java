/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import org.robolectric.RuntimeEnvironment;

/** Lets tests outside this package ask the UnifiedPush gate. */
public final class UnifiedPushForTests {
    private UnifiedPushForTests() {}

    /** With an address saved, whether a Firebase sign-up would go out as the UnifiedPush address. */
    public static boolean swapsFirebase() {
        UnifiedPush.prefs(RuntimeEnvironment.getApplication()).edit()
                .putString(UnifiedPush.ENDPOINT, "https://ntfy.sh/upProbe?up=1").commit();
        String token = UnifiedPush.token(2, "firebase-token");
        int type = UnifiedPush.type(2);
        return !"firebase-token".equals(token) || type != 2;
    }
}
