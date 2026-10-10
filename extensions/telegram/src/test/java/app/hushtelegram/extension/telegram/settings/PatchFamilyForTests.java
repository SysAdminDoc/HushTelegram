/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.settings;

import java.util.Arrays;
import java.util.EnumSet;

/** Lets tests outside this package say which families the build carries. */
public final class PatchFamilyForTests {
    private PatchFamilyForTests() {}

    public static void inBuild(PatchFamily... families) {
        PatchFamily.inBuildForTests = families.length == 0 ? EnumSet.noneOf(PatchFamily.class)
                : EnumSet.copyOf(Arrays.asList(families));
    }

    public static void reset() {
        PatchFamily.inBuildForTests = null;
    }
}
