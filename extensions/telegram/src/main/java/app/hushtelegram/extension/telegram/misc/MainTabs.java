/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Telegram's main screen has a bar at the bottom with Chats, Contacts, Calls or Settings, and your
 * profile, and a swipe moves between their pages. With the switch on the Contacts tab is left out
 * of the bar and a swipe doesn't stop on its page. Telegram sets up the bar when it builds the main
 * screen and again whenever Calls and Settings trade places, and the tab is hidden each time, so
 * that choice keeps working. The chat list's new message button still opens the contact list.
 */
public final class MainTabs {
    /** The Contacts page, second of the main screen's pages; its tab is the second in the bar too. */
    static final int CONTACTS = 1;

    private MainTabs() {}

    /**
     * Called each time Telegram has set which tabs the bar shows: when the main screen is built,
     * without animation, and when Calls and Settings trade places, with it.
     *
     * @param screen Telegram's main screen
     * @param animated whether Telegram animates the change
     */
    public static void tabsShown(Object screen, boolean animated) {
        if (!on() || screen == null) return;
        try {
            // The bar is built after the main screen's first call here; nothing to hide before that.
            if (!hideContacts(screen, animated)) return;
            HookStatus.counted(FamilyNames.HIDE_CONTACTS_TAB, "Contacts tab hidden");
            if (leavesContacts(page(screen))) showChats(screen, animated);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_CONTACTS_TAB, "tab bar", failure);
        }
    }

    /**
     * Asked before a swipe on the main screen moves one page on or back.
     *
     * @param position the page showing now
     * @param forward whether the swipe moves to the next page rather than the one before
     * @return true to stop the swipe, which Telegram then treats as one its page turned down
     */
    public static boolean blocksSwipe(int position, boolean forward) {
        if (!on() || !landsOnContacts(position, forward)) return false;
        HookStatus.counted(FamilyNames.HIDE_CONTACTS_TAB, "swipe to Contacts stopped");
        return true;
    }

    /** Whether a swipe from {@code position} that way would land on the Contacts page. */
    static boolean landsOnContacts(int position, boolean forward) {
        return (forward ? position + 1 : position - 1) == CONTACTS;
    }

    /** Whether the main screen has to move off {@code page} now that its tab is gone. */
    static boolean leavesContacts(int page) {
        if (page != CONTACTS) return false;
        HookStatus.counted(FamilyNames.HIDE_CONTACTS_TAB, "moved from Contacts to Chats");
        return true;
    }

    /** Whether the switch is on and HushTelegram isn't paused. */
    static boolean on() {
        HookStatus.invoked(FamilyNames.HIDE_CONTACTS_TAB);
        try {
            return Utils.settingsReady() && Settings.HIDE_CONTACTS_TAB.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_CONTACTS_TAB, "switch", failure);
            return false;
        }
    }

    /**
     * Hides the Contacts tab in the main screen's bar, the way Telegram hides Calls or Settings.
     * False when the bar isn't built yet. Replaced when patching.
     */
    public static boolean hideContacts(Object screen, boolean animated) { return false; }

    /** The page the main screen shows, or -1 before its pages are built. Replaced when patching. */
    public static int page(Object screen) { return -1; }

    /**
     * Turns the main screen to Chats and marks its tab. With {@code animated} it slides there the way
     * Telegram leaves the Calls page when that tab goes; without, it's simply set, as Telegram sets
     * the first page while it builds the screen. Replaced when patching.
     */
    public static void showChats(Object screen, boolean animated) {}
}
