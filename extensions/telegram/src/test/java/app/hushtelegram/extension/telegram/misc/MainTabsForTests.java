/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.view.View;
import org.robolectric.RuntimeEnvironment;

/** Lets a test in another package ask whether the chat list's new message button got hidden. */
public final class MainTabsForTests {
    private MainTabsForTests() {}

    public static boolean hidesNewMessage() {
        View button = new View(RuntimeEnvironment.getApplication());
        MainTabs.newMessageButton(button);
        return button.getVisibility() == View.GONE;
    }
}
