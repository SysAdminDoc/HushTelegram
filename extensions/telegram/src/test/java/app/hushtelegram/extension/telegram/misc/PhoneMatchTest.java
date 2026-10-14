/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/** Keep searches on this phone and Search only my chats both hooked after the same username lookup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PhoneMatchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Which runtime's lookup ran, and for which helper and search, one line per call. */
    private final List<String> lookups = new ArrayList<>();
    private final Object helper = new Object();
    private PhoneMatch.Lookup local;
    private PhoneMatch.Lookup mine;

    @Before public void setUp() {
        Settings.LOCAL_SEARCH.save(true);
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        HookStatus.clear();
        local = LocalSearch.lookup;
        mine = MyChatsSearch.lookup;
        LocalSearch.lookup = watch("local");
        MyChatsSearch.lookup = watch("mine");
    }

    @After public void tearDown() {
        LocalSearch.lookup = local;
        MyChatsSearch.lookup = mine;
        Settings.LOCAL_SEARCH.resetToDefault();
        Settings.SEARCH_ONLY_MY_CHATS.resetToDefault();
        HookStatus.clear();
    }

    private PhoneMatch.Lookup watch(String who) {
        return (on, query, usernames, chats, bots, self, groupsOnly, channel, phoneNumbers, type, searchId, except, end) ->
                lookups.add(who + " " + (on == helper) + " " + searchId);
    }

    private boolean localPhones(Object on, int searchId) {
        return LocalSearch.phones(on, "+15551234", true, true, true, true, false, 0, true, 0, searchId, 0L, null);
    }

    private boolean myPhones(Object on, int searchId) {
        return MyChatsSearch.phones(on, "+15551234", true, true, true, true, false, 0, true, 0, searchId, 0L, null);
    }

    @Test public void withBothOnTheMatchRunsOncePerSearchWhicheverHookComesFirst() {
        assertTrue("Keep searches matches first", localPhones(helper, 7));
        assertFalse("Search only my chats finds it made", myPhones(helper, 7));
        assertTrue("the next search matches again", myPhones(helper, 8));
        assertFalse("and Keep searches finds that one made", localPhones(helper, 8));
        assertEquals(Arrays.asList("local true 7", "mine true 8"), lookups);
    }

    // Another account's search screen has its own helper, whose searches count from their own start.
    @Test public void anotherHelpersSearchOfTheSameNumberStillMatches() {
        Object other = new Object();
        assertTrue(localPhones(helper, 7));
        assertTrue(myPhones(other, 7));
        assertEquals(Arrays.asList("local true 7", "mine false 7"), lookups);
    }

    // A match that threw made nothing, so the other hook still tries.
    @Test public void aMatchThatThrewLeavesTheOtherHookToTry() {
        LocalSearch.lookup = (on, query, usernames, chats, bots, self, groupsOnly, channel, phoneNumbers, type, searchId, except, end) -> {
            throw new ClassCastException("not the helper");
        };
        assertFalse(localPhones(helper, 7));
        assertTrue(myPhones(helper, 7));
        assertEquals(Collections.singletonList("mine true 7"), lookups);
        assertEquals(Collections.singletonList("a working 'search phone match' hook (it threw java.lang.ClassCastException)"),
                HookStatus.missing(FamilyNames.DISABLE_DRAFT_PREVIEWS));
    }

    // Telegram counts searches from 1 again once the box is cleared, so a later search can carry a
    // number already matched. Its text is a new string, so it matches again, with both switches on
    // and with Keep searches alone.
    @Test public void aLaterSearchWithARepeatedNumberStillMatches() {
        String first = new String("+15551234");
        assertTrue(LocalSearch.phones(helper, first, true, true, true, true, false, 0, true, 0, 1, 0L, null));
        assertFalse(MyChatsSearch.phones(helper, first, true, true, true, true, false, 0, true, 0, 1, 0L, null));
        String again = new String("+15551234");
        assertTrue("the cleared box's next search was skipped",
                LocalSearch.phones(helper, again, true, true, true, true, false, 0, true, 0, 1, 0L, null));
        assertFalse(MyChatsSearch.phones(helper, again, true, true, true, true, false, 0, true, 0, 1, 0L, null));

        Settings.SEARCH_ONLY_MY_CHATS.save(false);
        assertTrue(LocalSearch.phones(helper, new String("+15559876"), true, true, true, true, false, 0, true, 0, 1, 0L, null));
        assertTrue(LocalSearch.phones(helper, new String("+15559876"), true, true, true, true, false, 0, true, 0, 1, 0L, null));
        assertEquals(Arrays.asList("local true 1", "local true 1", "local true 1", "local true 1"), lookups);
    }

    // With one switch off, its hook leaves the match to the other, which still makes it.
    @Test public void withOneOffTheOtherMatchesAlone() {
        Settings.LOCAL_SEARCH.save(false);
        assertFalse(localPhones(helper, 7));
        assertTrue(myPhones(helper, 7));
        Settings.LOCAL_SEARCH.save(true);
        Settings.SEARCH_ONLY_MY_CHATS.save(false);
        assertFalse(myPhones(helper, 8));
        assertTrue(localPhones(helper, 8));
        assertEquals(Arrays.asList("mine true 7", "local true 8"), lookups);
    }
}
