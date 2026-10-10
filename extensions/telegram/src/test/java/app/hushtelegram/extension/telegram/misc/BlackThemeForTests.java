/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.util.List;

/** Lets a test outside this package give BlackTheme ids without Telegram's lookup, and watch its launch screens. */
public final class BlackThemeForTests {
    private BlackThemeForTests() {}

    private static final BlackTheme.LaunchScreens ANDROID = BlackTheme.launchScreens;

    /** The launch screen the switch asks for. */
    public static final int BLACK_LAUNCH_SCREEN = BlackTheme.LAUNCH_SCREEN;

    /** Each launch screen BlackTheme asks for lands in {@code asked}, and Android isn't asked. */
    public static void watchLaunchScreens(List<Integer> asked) {
        BlackTheme.launchScreens = (activity, theme) -> asked.add(theme);
    }

    public static void stopWatchingLaunchScreens() {
        BlackTheme.launchScreens = ANDROID;
    }

    /** Ids 1 to 16 in the order of {@link BlackTheme#SURFACES}. */
    public static void useStandInIds() {
        int[] ids = new int[BlackTheme.SURFACES.length];
        for (int i = 0; i < ids.length; i++) ids[i] = i + 1;
        BlackTheme.ids = ids;
    }
}
