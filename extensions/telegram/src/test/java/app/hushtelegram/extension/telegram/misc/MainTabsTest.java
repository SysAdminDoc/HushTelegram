/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MainTabsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void reset() { restore(); }
    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.HIDE_CONTACTS_TAB);
        Settings.HIDE_CONTACTS_TAB.resetToDefault();
        HookStatus.clear();
    }

    private static String report() { return String.join("\n", HookStatus.report()); }

    @Test public void offByDefaultEverySwipeGoesAsTelegramDecides() {
        assertFalse(Settings.HIDE_CONTACTS_TAB.get());
        assertFalse(MainTabs.blocksSwipe(0, true));
        assertFalse(MainTabs.blocksSwipe(2, false));
        MainTabs.tabsShown(new Object(), false);
        assertTrue(report().contains(FamilyNames.HIDE_CONTACTS_TAB));
        assertFalse(report().contains("Contacts tab hidden"));
    }

    @Test public void onOnlyASwipeThatLandsOnContactsIsStopped() {
        Settings.HIDE_CONTACTS_TAB.save(true);
        // From Chats on, and from the page after Contacts back.
        assertTrue(MainTabs.blocksSwipe(0, true));
        assertTrue(MainTabs.blocksSwipe(2, false));
        assertTrue(report().contains("swipe to Contacts stopped"));
        // Every other swipe, including off the Contacts page itself, goes on.
        assertFalse(MainTabs.blocksSwipe(1, true));
        assertFalse(MainTabs.blocksSwipe(1, false));
        assertFalse(MainTabs.blocksSwipe(0, false));
        assertFalse(MainTabs.blocksSwipe(2, true));
        assertFalse(MainTabs.blocksSwipe(3, false));
    }

    @Test public void theScreenLeavesContactsOnlyWhenItShowsThatPage() {
        assertEquals(1, MainTabs.CONTACTS);
        assertTrue(MainTabs.landsOnContacts(0, true));
        assertTrue(MainTabs.landsOnContacts(2, false));
        assertFalse(MainTabs.landsOnContacts(1, true));
        assertTrue(MainTabs.leavesContacts(1));
        assertTrue(report().contains("moved from Contacts to Chats"));
        assertFalse(MainTabs.leavesContacts(0));
        assertFalse(MainTabs.leavesContacts(-1));
        assertFalse(MainTabs.leavesContacts(2));
    }

    @Test public void anUnbuiltBarOrANullScreenIsLeftAlone() {
        Settings.HIDE_CONTACTS_TAB.save(true);
        // The unpatched stub finds no bar, the way the first call before the bar is built does.
        MainTabs.tabsShown(new Object(), false);
        MainTabs.tabsShown(new Object(), true);
        MainTabs.tabsShown(null, true);
        assertFalse(report().contains("Contacts tab hidden"));
        assertFalse(report().contains("moved from Contacts to Chats"));
        assertEquals(-1, MainTabs.page(new Object()));
    }

    @Test public void pausingAnEarlyStartOrAnUnreadableSwitchKeepsTheTab() {
        Settings.HIDE_CONTACTS_TAB.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), MainTabs.on());
            assertFalse(reason.name(), MainTabs.blocksSwipe(0, true));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse(MainTabs.blocksSwipe(0, true)));
        SettingReadsForTests.breakReads(Settings.HIDE_CONTACTS_TAB);
        assertFalse(MainTabs.on());
        assertFalse(MainTabs.blocksSwipe(0, true));
        assertFalse(HookStatus.missing(FamilyNames.HIDE_CONTACTS_TAB).isEmpty());
    }
}
