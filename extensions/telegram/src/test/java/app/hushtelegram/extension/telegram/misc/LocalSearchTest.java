/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LocalSearchTest {
    private static final String STOCK = "There were no results for \"cat\".\nTry a new search.";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Settings.LOCAL_SEARCH.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.LOCAL_SEARCH);
        Settings.LOCAL_SEARCH.resetToDefault();
        LocalSearch.lookup = LocalSearch::lookUp;
        HookStatus.clear();
    }

    /** What the helper's lookup was handed, one line per call. */
    private final List<String> lookups = new ArrayList<>();
    private final Object helper = new Object();
    private final Runnable done = () -> {};

    private void watchLookups() {
        LocalSearch.lookup = (on, query, usernames, chats, bots, self, groupsOnly, channel, phoneNumbers, type, searchId, except, end) ->
                lookups.add((on == helper) + " " + query + " " + usernames + " " + chats + " " + bots + " " + self + " " + groupsOnly
                        + " " + channel + " " + phoneNumbers + " " + type + " " + searchId + " " + except + " " + (end == done));
    }

    /** The Chats tab's second step handing the helper's lookup on, as it does right after the username lookup. */
    private boolean phones(String query, long channel) {
        return LocalSearch.phones(helper, query, true, true, true, true, false, channel, true, 0, 7, 42L, done);
    }

    @Test public void offTheFilteredTabsAndThePhoneMatchAreTelegramsOwn() {
        watchLookups();
        assertFalse(LocalSearch.keepsText("cat"));
        assertFalse("nothing new for the tab to show", phones("+15551234", 0));
        assertEquals(Collections.emptyList(), lookups);
        assertEquals(Collections.singletonList("Disable draft link previews: invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    @Test public void switchedOnAFilteredSearchOfTypedTextIsSkippedAndCounted() {
        Settings.LOCAL_SEARCH.save(true);
        assertTrue(LocalSearch.keepsText("cat"));
        assertEquals(Collections.singletonList("Disable draft link previews: invoked 1, 0 found, 0 missing. Counted: "
                + "search lookup kept on the phone 1"), HookStatus.report());
    }

    // An empty box sends no text, so a media tab or a filtered Chats tab still lists what it finds.
    @Test public void anEmptyFilteredSearchGoesAsTelegramSendsIt() {
        Settings.LOCAL_SEARCH.save(true);
        assertFalse(LocalSearch.keepsText(""));
        assertFalse(LocalSearch.keepsText(null));
        assertEquals(Collections.emptyList(), HookStatus.report());
    }

    @Test public void switchedOnAPhoneNumberIsMatchedAgainWithTheTextAndNoUsernameLookup() {
        Settings.LOCAL_SEARCH.save(true);
        watchLookups();
        assertTrue("the tab is told to show the match", phones("+15551234", 0));
        assertEquals(Collections.singletonList("true +15551234 false true true true false 0 true 0 7 42 true"), lookups);
        assertEquals(Collections.singletonList("Disable draft link previews: invoked 1, 0 found, 0 missing. Counted: "
                + "phone number matched on the phone 1"), HookStatus.report());
    }

    // Only a "+" number has a match on the phone, and a channel would send its member search to the server.
    @Test public void onlyAPhoneNumberWithNoChannelIsMatchedAgain() {
        Settings.LOCAL_SEARCH.save(true);
        watchLookups();
        assertFalse(phones("cat", 0));
        assertFalse(phones("", 0));
        assertFalse(phones(null, 0));
        assertFalse(phones("+15551234", 1L));
        assertFalse(LocalSearch.phones(null, "+15551234", true, true, true, true, false, 0, true, 0, 7, 42L, done));
        assertEquals(Collections.emptyList(), lookups);
        assertEquals(Collections.emptyList(), HookStatus.report());
    }

    @Test public void aPhoneMatchThatThrowsIsReportedAndSearchGoesOn() {
        Settings.LOCAL_SEARCH.save(true);
        LocalSearch.lookup = (on, query, usernames, chats, bots, self, groupsOnly, channel, phoneNumbers, type, searchId, except, end) -> {
            throw new ClassCastException("not the helper");
        };
        assertFalse("a match that threw has nothing to show", phones("+15551234", 0));
        assertEquals(Collections.singletonList("a working 'search phone match' hook (it threw java.lang.ClassCastException)"),
                HookStatus.missing(FamilyNames.DISABLE_DRAFT_PREVIEWS));
    }

    // Unpatched, the stub does nothing, so the runtime can't call anything it doesn't know.
    @Test public void theUnpatchedLookUpStubDoesNothing() {
        Settings.LOCAL_SEARCH.save(true);
        phones("+15551234", 0);
        assertEquals(Collections.singletonList("Disable draft link previews: invoked 1, 0 found, 0 missing. Counted: "
                + "phone number matched on the phone 1"), HookStatus.report());
    }

    @Test public void offByDefaultSoEveryLookupGoesAsTelegramSendsIt() {
        assertFalse(Settings.LOCAL_SEARCH.get());
        assertFalse(LocalSearch.keep());
        assertEquals("cat", LocalSearch.sent("cat"));
        assertEquals(STOCK, LocalSearch.emptyText(STOCK));
        assertEquals(Collections.singletonList("Disable draft link previews: invoked 3, 0 found, 0 missing"), HookStatus.report());
    }

    @Test public void switchedOnNoLookupLeavesAndEachIsCounted() {
        Settings.LOCAL_SEARCH.save(true);
        assertTrue(LocalSearch.keep());
        assertNull(LocalSearch.sent("cat"));
        assertNull(LocalSearch.sent("#cats"));
        assertEquals(Collections.singletonList("Disable draft link previews: invoked 3, 0 found, 0 missing. Counted: "
                + "search lookup kept on the phone 3"), HookStatus.report());
    }

    // An empty box is already null in Telegram, and there's nothing to keep back or count.
    @Test public void anEmptyBoxStaysNullAndIsntCounted() {
        Settings.LOCAL_SEARCH.save(true);
        assertNull(LocalSearch.sent(null));
        assertNull(LocalSearch.emptyText(null));
        assertEquals(Collections.emptyList(), HookStatus.report());
    }

    @Test public void theEmptyResultSaysWhereItLooked() {
        Settings.LOCAL_SEARCH.save(true);
        assertEquals(STOCK + "\nOnly chats and contacts on this phone were searched.", LocalSearch.emptyText(STOCK));
    }

    @Test public void everyPauseReasonSearchesAsTelegramDoes() {
        Settings.LOCAL_SEARCH.save(true);
        watchLookups();
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), LocalSearch.keep());
            assertEquals(reason.name(), "cat", LocalSearch.sent("cat"));
            assertEquals(reason.name(), STOCK, LocalSearch.emptyText(STOCK));
            assertFalse(reason.name(), LocalSearch.keepsText("cat"));
            phones("+15551234", 0);
            assertEquals(reason.name(), Collections.emptyList(), lookups);
            PauseForTests.resume();
        }
        assertTrue(LocalSearch.keep());
        assertTrue(LocalSearch.keepsText("cat"));
    }

    @Test public void unavailableOrUnreadableSettingsSearchAsTelegramDoesAndSaySo() {
        Settings.LOCAL_SEARCH.save(true);
        watchLookups();
        SettingsContextRule.withoutContext(() -> {
            assertFalse(LocalSearch.keep());
            assertEquals("cat", LocalSearch.sent("cat"));
            assertEquals(STOCK, LocalSearch.emptyText(STOCK));
            assertFalse(LocalSearch.keepsText("cat"));
            phones("+15551234", 0);
        });
        SettingReadsForTests.breakReads(Settings.LOCAL_SEARCH);
        assertFalse(LocalSearch.keep());
        assertEquals("cat", LocalSearch.sent("cat"));
        assertEquals(STOCK, LocalSearch.emptyText(STOCK));
        assertFalse(LocalSearch.keepsText("cat"));
        phones("+15551234", 0);
        assertEquals(Collections.emptyList(), lookups);
        assertEquals(Collections.singletonList("a working 'local search switch read' hook (it threw java.lang.NullPointerException)"),
                HookStatus.missing(FamilyNames.DISABLE_DRAFT_PREVIEWS));
    }
}
