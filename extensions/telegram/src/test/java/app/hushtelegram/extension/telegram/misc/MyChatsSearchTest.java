/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MyChatsSearchTest {
    // Stand-ins for the pager's tabs, in Telegram's order with the public posts tab open.
    private static final Object CHATS = "Chats";
    private static final Object PUBLIC_POSTS = "Public posts";
    private static final Object CHANNELS = "Channels";
    private static final Object APPS = "Apps";
    private static final Object POSTS = "Posts";
    private static final Object MEDIA = "Media";
    private static final Object DOWNLOADS = "Downloads";
    private static final Object LINKS = "Links";
    private static final List<Object> STOCK_TABS = Arrays.asList(CHATS, PUBLIC_POSTS, CHANNELS, APPS, POSTS, MEDIA, DOWNLOADS, LINKS);
    private static final List<Object> PUBLIC = Arrays.asList(PUBLIC_POSTS, CHANNELS, APPS, POSTS);
    private static final Object PAGER = new Object();
    private static final Object CONTROLLER = new Object();
    // The hashtag searches: your own messages, public posts, then posts in one place.
    private static final int MY_MESSAGES = 1;
    private static final int PUBLIC_POSTS_SEARCH = 2;
    private static final int LOCAL_POSTS = 3;
    // A name this phone knows as a group you're in, and one as an account you've talked to.
    private static final List<String> OWN = Arrays.asList("catlovers", "cathy");

    // Stand-ins for what an account search for "cat" answers: your contact Cathy and a group you're
    // in, then a public channel and a stranger, each named by a peer and listed as an account or chat.
    private static final Keyed CATHY = new Keyed("Cathy", 7);
    private static final Keyed STRANGER = new Keyed("Stranger", 9);
    private static final Keyed CAT_GROUP = new Keyed("Cat lovers", -100);
    private static final Keyed CAT_CHANNEL = new Keyed("Cat channel", -200);
    private static final Keyed CATHY_PEER = new Keyed("Cathy's peer", 7);
    private static final Keyed STRANGER_PEER = new Keyed("Stranger's peer", 9);
    private static final Keyed CAT_GROUP_PEER = new Keyed("Cat lovers' peer", -100);
    private static final Keyed CAT_CHANNEL_PEER = new Keyed("Cat channel's peer", -200);

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Predicate<Object> isPublic;
    private Predicate<Object> isDownloads;
    private Function<Object, List<?>> tabsOf;
    private MyChatsSearch.PostsFinisher finisher;
    private Executor later;
    private BiFunction<Object, Integer, String> lastHashtagOf;
    private BiPredicate<Object, String> isOwnChat;
    private Function<Object, ArrayList<?>[]> listsOf;
    private ToLongFunction<Object> keyOf;
    private PhoneMatch.Lookup lookup;
    /** What the username helper's lookup was handed, one line per call. */
    private final List<String> lookups = new ArrayList<>();
    private final Object helper = new Object();
    private final Runnable done = () -> {};
    private final List<Runnable> queued = new ArrayList<>();
    private final List<Object[]> finished = new ArrayList<>();
    private final Map<Integer, String> lastTags = new HashMap<>();

    /** An account, a chat or a peer naming one, keyed the way the patch keys Telegram's. */
    private static final class Keyed {
        final String what;
        final long key;

        Keyed(String what, long key) {
            this.what = what;
            this.key = key;
        }

        @Override public String toString() {
            return what;
        }
    }

    /** A stand-in for an account search answer. */
    private static final class Found {
        final ArrayList<Object> mine = new ArrayList<>();
        final ArrayList<Object> others = new ArrayList<>();
        final ArrayList<Object> users = new ArrayList<>();
        final ArrayList<Object> chats = new ArrayList<>();

        ArrayList<?>[] lists() {
            return new ArrayList<?>[] {mine, others, users, chats};
        }

        List<List<Object>> all() {
            return Arrays.asList(mine, others, users, chats);
        }
    }

    private static Found catSearch() {
        Found found = new Found();
        found.mine.addAll(Arrays.asList(CATHY_PEER, CAT_GROUP_PEER));
        found.others.addAll(Arrays.asList(CAT_CHANNEL_PEER, STRANGER_PEER));
        found.users.addAll(Arrays.asList(CATHY, STRANGER));
        found.chats.addAll(Arrays.asList(CAT_GROUP, CAT_CHANNEL));
        return found;
    }

    @Before public void setUp() {
        Settings.SEARCH_ONLY_MY_CHATS.resetToDefault();
        HookStatus.clear();
        isPublic = MyChatsSearch.isPublic;
        isDownloads = MyChatsSearch.isDownloads;
        tabsOf = MyChatsSearch.tabsOf;
        finisher = MyChatsSearch.finisher;
        later = MyChatsSearch.later;
        lastHashtagOf = MyChatsSearch.lastHashtagOf;
        isOwnChat = MyChatsSearch.isOwnChat;
        listsOf = MyChatsSearch.listsOf;
        keyOf = MyChatsSearch.keyOf;
        lookup = MyChatsSearch.lookup;
        MyChatsSearch.isPublic = PUBLIC::contains;
        MyChatsSearch.isDownloads = tab -> tab == DOWNLOADS;
        MyChatsSearch.tabsOf = pager -> pager == PAGER ? new ArrayList<>(STOCK_TABS) : null;
        MyChatsSearch.finisher = (controller, searchType, update) -> finished.add(new Object[] {controller, searchType, update});
        MyChatsSearch.later = queued::add;
        MyChatsSearch.lastHashtagOf = (controller, searchType) -> controller == CONTROLLER ? lastTags.get(searchType) : null;
        MyChatsSearch.isOwnChat = (controller, name) -> controller == CONTROLLER && OWN.contains(name);
        MyChatsSearch.listsOf = found -> found instanceof Found ? ((Found) found).lists() : null;
        MyChatsSearch.keyOf = item -> ((Keyed) item).key;
        MyChatsSearch.lookup = (on, query, usernames, chats, bots, self, groupsOnly, channel, phoneNumbers, type, searchId, except, end) ->
                lookups.add((on == helper) + " " + query + " " + usernames + " " + chats + " " + bots + " " + self + " " + groupsOnly
                        + " " + channel + " " + phoneNumbers + " " + type + " " + searchId + " " + except + " " + (end == done));
    }

    @After public void tearDown() {
        MyChatsSearch.isPublic = isPublic;
        MyChatsSearch.isDownloads = isDownloads;
        MyChatsSearch.tabsOf = tabsOf;
        MyChatsSearch.finisher = finisher;
        MyChatsSearch.later = later;
        MyChatsSearch.lastHashtagOf = lastHashtagOf;
        MyChatsSearch.isOwnChat = isOwnChat;
        MyChatsSearch.listsOf = listsOf;
        MyChatsSearch.keyOf = keyOf;
        MyChatsSearch.lookup = lookup;
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.SEARCH_ONLY_MY_CHATS);
        Settings.SEARCH_ONLY_MY_CHATS.resetToDefault();
        HookStatus.clear();
    }

    private static ArrayList<Object> stockTabs() {
        return new ArrayList<>(STOCK_TABS);
    }

    /** Every hook with the switch where it is, and whether any of them changed what Telegram does. */
    private static boolean anyChange() {
        ArrayList<Object> tabs = stockTabs();
        MyChatsSearch.dropPublicTabs(tabs);
        Found found = catSearch();
        MyChatsSearch.dropPublicResults(found);
        return MyChatsSearch.keep() || !"cat".equals(MyChatsSearch.sent("cat"))
                || !MyChatsSearch.keepHashtag(CONTROLLER, "#cats", PUBLIC_POSTS_SEARCH)
                || !MyChatsSearch.keepHashtag(CONTROLLER, "#cats@catchannel", LOCAL_POSTS)
                || !found.all().equals(catSearch().all())
                || !tabs.equals(STOCK_TABS) || MyChatsSearch.downloadsAt(PAGER, 6) != 6
                || MyChatsSearch.phones(new Object(), "+15551234", true, true, true, true, false, 0, true, 0, 7, 42L, null);
    }

    /** The Chats tab's second step handing the helper's lookup on, as it does right after the username lookup. */
    private boolean phones(String query, long channel, int searchId) {
        return MyChatsSearch.phones(helper, query, true, true, true, true, false, channel, true, 0, searchId, 42L, done);
    }

    @Test public void offByDefaultSoSearchIsTelegramsOwn() {
        assertFalse(Settings.SEARCH_ONLY_MY_CHATS.get());
        assertFalse(MyChatsSearch.keep());
        assertEquals("cat", MyChatsSearch.sent("cat"));
        assertTrue(MyChatsSearch.keepHashtag(CONTROLLER, "#cats", PUBLIC_POSTS_SEARCH));
        assertTrue(MyChatsSearch.keepHashtag(CONTROLLER, "#cats@catchannel", LOCAL_POSTS));
        Found found = catSearch();
        MyChatsSearch.dropPublicResults(found);
        assertEquals(catSearch().all(), found.all());
        ArrayList<Object> tabs = stockTabs();
        MyChatsSearch.dropPublicTabs(tabs);
        assertEquals(STOCK_TABS, tabs);
        assertEquals(6, MyChatsSearch.downloadsAt(PAGER, 6));
        assertEquals(Collections.singletonList("Hide popular apps: invoked 7, 0 found, 0 missing"), HookStatus.report());
    }

    @Test public void switchedOnThePublicTabsLeaveAndYourOwnStayInOrder() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        ArrayList<Object> tabs = stockTabs();
        MyChatsSearch.dropPublicTabs(tabs);
        assertEquals(Arrays.asList(CHATS, MEDIA, DOWNLOADS, LINKS), tabs);
        // A list already without them, as after a second update, stays as it is and isn't counted again.
        MyChatsSearch.dropPublicTabs(tabs);
        assertEquals(Arrays.asList(CHATS, MEDIA, DOWNLOADS, LINKS), tabs);
        assertEquals(Collections.singletonList("Hide popular apps: invoked 2, 0 found, 0 missing. Counted: "
                + "public search tabs hidden 1"), HookStatus.report());
    }

    @Test public void switchedOnNoPublicLookupLeavesAndEachIsCounted() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        assertTrue(MyChatsSearch.keep());
        assertNull(MyChatsSearch.sent("cat"));
        assertNull(MyChatsSearch.sent("#cats"));
        assertFalse(MyChatsSearch.keepHashtag(CONTROLLER, "#cats", PUBLIC_POSTS_SEARCH));
        assertEquals(Collections.singletonList("Hide popular apps: invoked 4, 0 found, 0 missing. Counted: "
                + "public search lookup skipped 4"), HookStatus.report());
    }

    // The username helper got no text, so its "+" number match against your contacts runs again here.
    @Test public void switchedOnAPhoneNumberIsMatchedAgainWithTheTextAndNoUsernameLookup() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        assertTrue("the tab is told to show the match", phones("+15551234", 0, 7));
        assertEquals(Collections.singletonList("true +15551234 false true true true false 0 true 0 7 42 true"), lookups);
        assertEquals(Collections.singletonList("Hide popular apps: invoked 1, 0 found, 0 missing. Counted: "
                + "phone number matched on the phone 1"), HookStatus.report());
    }

    @Test public void offThePhoneMatchIsTelegramsOwn() {
        assertFalse("nothing new for the tab to show", phones("+15551234", 0, 7));
        assertEquals(Collections.emptyList(), lookups);
        assertEquals(Collections.singletonList("Hide popular apps: invoked 1, 0 found, 0 missing"), HookStatus.report());
    }

    // Only a "+" number has a match on the phone, and a channel would send its member search to the server.
    @Test public void onlyAPhoneNumberWithNoChannelIsMatchedAgain() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        assertFalse(phones("cat", 0, 7));
        assertFalse(phones("", 0, 7));
        assertFalse(phones(null, 0, 7));
        assertFalse(phones("+15551234", 1L, 7));
        assertFalse(MyChatsSearch.phones(null, "+15551234", true, true, true, true, false, 0, true, 0, 7, 42L, done));
        assertEquals(Collections.emptyList(), lookups);
        assertEquals(Collections.emptyList(), HookStatus.report());
    }

    @Test public void aPhoneMatchThatThrowsIsReportedAndSearchGoesOn() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        MyChatsSearch.lookup = (on, query, usernames, chats, bots, self, groupsOnly, channel, phoneNumbers, type, searchId, except, end) -> {
            throw new ClassCastException("not the helper");
        };
        assertFalse("a match that threw has nothing to show", phones("+15551234", 0, 7));
        assertEquals(Collections.singletonList("a working 'search phone match' hook (it threw java.lang.ClassCastException)"),
                HookStatus.missing(FamilyNames.HIDE_POPULAR_APPS));
    }

    @Test public void switchedOnAnAccountSearchKeepsYourOwnMatchesAndWhatTheyName() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        Found found = catSearch();
        MyChatsSearch.dropPublicResults(found);
        assertEquals(Arrays.asList(CATHY_PEER, CAT_GROUP_PEER), found.mine);
        assertEquals(Collections.emptyList(), found.others);
        assertEquals(Collections.singletonList(CATHY), found.users);
        assertEquals(Collections.singletonList(CAT_GROUP), found.chats);
        // An answer with only your own matches stays as it is and isn't counted.
        Found own = new Found();
        own.mine.add(CATHY_PEER);
        own.users.add(CATHY);
        MyChatsSearch.dropPublicResults(own);
        assertEquals(Arrays.asList(Collections.singletonList(CATHY_PEER), Collections.emptyList(),
                Collections.singletonList(CATHY), Collections.emptyList()), own.all());
        assertEquals(Collections.singletonList("Hide popular apps: invoked 2, 0 found, 0 missing. Counted: "
                + "public search results dropped 1"), HookStatus.report());
    }

    // Someone else's match can name an account you also matched, and that account stays for yours.
    @Test public void anAccountYouMatchedStaysEvenWhenAnotherMatchNamesIt() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        Found found = new Found();
        found.mine.add(CATHY_PEER);
        found.others.addAll(Arrays.asList(CATHY_PEER, STRANGER_PEER));
        found.users.addAll(Arrays.asList(STRANGER, CATHY));
        MyChatsSearch.dropPublicResults(found);
        assertEquals(Collections.singletonList(CATHY), found.users);
        assertEquals(Collections.emptyList(), found.others);
    }

    @Test public void anAccountSearchAnswerThatCantBeReadShowsNoneOfTheirsAndSaysSo() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        MyChatsSearch.dropPublicResults(null);
        MyChatsSearch.dropPublicResults(new Object());
        MyChatsSearch.listsOf = found -> new ArrayList<?>[] {null, null, null, null};
        MyChatsSearch.dropPublicResults(new Found());
        assertEquals(Collections.emptyList(), HookStatus.missing(FamilyNames.HIDE_POPULAR_APPS));
        // Names that can't be read: everyone else's matches still go, and the lists are left as read.
        MyChatsSearch.listsOf = found -> ((Found) found).lists();
        MyChatsSearch.keyOf = item -> {
            throw new IllegalStateException("renamed");
        };
        Found found = catSearch();
        MyChatsSearch.dropPublicResults(found);
        assertEquals(Collections.emptyList(), found.others);
        assertEquals(catSearch().users, found.users);
        assertEquals(catSearch().chats, found.chats);
        assertEquals(Collections.singletonList("a working 'public search results' hook (it threw java.lang.IllegalStateException)"),
                HookStatus.missing(FamilyNames.HIDE_POPULAR_APPS));
    }

    @Test public void aHashtagSearchesOnlyAChatOfYoursAndNeverPublicPosts() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        assertFalse("public posts", MyChatsSearch.keepHashtag(CONTROLLER, "#cats", PUBLIC_POSTS_SEARCH));
        assertFalse("public posts in the chat's own search", MyChatsSearch.keepHashtag(CONTROLLER, "#cats", LOCAL_POSTS));
        assertFalse("a channel you haven't joined", MyChatsSearch.keepHashtag(CONTROLLER, "#cats@catchannel", LOCAL_POSTS));
        assertFalse("no name after the @", MyChatsSearch.keepHashtag(CONTROLLER, "#cats@", LOCAL_POSTS));
        assertTrue("a group you're in", MyChatsSearch.keepHashtag(CONTROLLER, "#cats@catlovers", LOCAL_POSTS));
        assertTrue("an account you know", MyChatsSearch.keepHashtag(CONTROLLER, "$CAT@cathy", PUBLIC_POSTS_SEARCH));
        assertEquals(Collections.singletonList("Hide popular apps: invoked 6, 0 found, 0 missing. Counted: "
                + "public search lookup skipped 4"), HookStatus.report());
    }

    @Test public void aHashtagCarriedOnFromLastTimeIsJudgedTheSameWay() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        lastTags.put(LOCAL_POSTS, "#cats@catlovers");
        lastTags.put(PUBLIC_POSTS_SEARCH, "#cats");
        assertTrue(MyChatsSearch.keepHashtag(CONTROLLER, null, LOCAL_POSTS));
        assertFalse(MyChatsSearch.keepHashtag(CONTROLLER, null, PUBLIC_POSTS_SEARCH));
        // No tag now or last time, or an empty one: Telegram stops by itself, so there's nothing to end.
        assertTrue(MyChatsSearch.keepHashtag(CONTROLLER, null, MY_MESSAGES));
        assertTrue(MyChatsSearch.keepHashtag(CONTROLLER, "", PUBLIC_POSTS_SEARCH));
        assertEquals(Collections.singletonList("Hide popular apps: invoked 4, 0 found, 0 missing. Counted: "
                + "public search lookup skipped 1"), HookStatus.report());
    }

    @Test public void aHashtagThatCantBeCheckedEndsEmptyAndSaysSo() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        MyChatsSearch.isOwnChat = (controller, name) -> {
            throw new IllegalStateException("renamed");
        };
        assertFalse(MyChatsSearch.keepHashtag(CONTROLLER, "#cats@catlovers", LOCAL_POSTS));
        MyChatsSearch.lastHashtagOf = (controller, searchType) -> {
            throw new IllegalStateException("renamed");
        };
        assertFalse(MyChatsSearch.keepHashtag(CONTROLLER, null, PUBLIC_POSTS_SEARCH));
        assertEquals(Collections.singletonList("a working 'hashtag search' hook (it threw java.lang.IllegalStateException)"),
                HookStatus.missing(FamilyNames.HIDE_POPULAR_APPS));
    }

    // An empty box is already null in Telegram, and there's nothing to keep back or count.
    @Test public void anEmptyBoxStaysNullAndIsntCounted() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        assertNull(MyChatsSearch.sent(null));
        MyChatsSearch.dropPublicTabs(null);
        assertEquals(Collections.emptyList(), HookStatus.report());
    }

    @Test public void theDownloadsShortcutFindsDownloadsWhereverItNowSits() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        // Telegram counts six tabs in front of Downloads with public posts open; four of them are gone.
        MyChatsSearch.tabsOf = pager -> {
            ArrayList<Object> tabs = stockTabs();
            MyChatsSearch.dropPublicTabs(tabs);
            return tabs;
        };
        assertEquals(2, MyChatsSearch.downloadsAt(PAGER, 6));
        // Switched on after the row was built, the stock row still has Downloads where Telegram counted.
        MyChatsSearch.tabsOf = pager -> stockTabs();
        assertEquals(6, MyChatsSearch.downloadsAt(PAGER, 6));
    }

    @Test public void aRowWithoutDownloadsOrWithoutAListGoesWhereTelegramPicked() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        MyChatsSearch.tabsOf = pager -> Arrays.asList(CHATS, MEDIA, LINKS);
        assertEquals(5, MyChatsSearch.downloadsAt(PAGER, 5));
        MyChatsSearch.tabsOf = pager -> null;
        assertEquals(5, MyChatsSearch.downloadsAt(PAGER, 5));
    }

    @Test public void aHashtagsPublicPostsEndEmptyOnceTheCallReturns() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        assertFalse(MyChatsSearch.keepHashtag(CONTROLLER, "#cats", PUBLIC_POSTS_SEARCH));
        MyChatsSearch.endPublicPosts(CONTROLLER, 41, 2);
        assertTrue("nothing is posted while Telegram's own call is still running", finished.isEmpty());
        assertEquals(1, queued.size());
        queued.get(0).run();
        assertEquals(1, finished.size());
        assertSame(CONTROLLER, finished.get(0)[0]);
        assertEquals(2, finished.get(0)[1]);
        // The screen, no results, nothing more to load, no arrows, the first result and no message to jump to.
        assertArrayEquals(new Object[] {41, 0, Boolean.TRUE, 0, 0, 0}, (Object[]) finished.get(0)[2]);
    }

    @Test public void aFinishThatFailsIsReportedNotThrown() {
        MyChatsSearch.finisher = (controller, searchType, update) -> {
            throw new IllegalStateException("renamed");
        };
        MyChatsSearch.endPublicPosts(CONTROLLER, 41, 2);
        queued.get(0).run();
        MyChatsSearch.later = command -> {
            throw new IllegalStateException("no main thread");
        };
        MyChatsSearch.endPublicPosts(CONTROLLER, 41, 2);
        assertEquals(Collections.singletonList("a working 'public posts finish' hook (it threw java.lang.IllegalStateException)"),
                HookStatus.missing(FamilyNames.HIDE_POPULAR_APPS));
    }

    @Test public void tabHooksThatFailKeepTelegramsRowAndSaySo() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        MyChatsSearch.isPublic = tab -> {
            throw new IllegalStateException("renamed");
        };
        ArrayList<Object> tabs = stockTabs();
        MyChatsSearch.dropPublicTabs(tabs);
        assertEquals(STOCK_TABS, tabs);
        MyChatsSearch.tabsOf = pager -> {
            throw new IllegalStateException("renamed");
        };
        assertEquals(6, MyChatsSearch.downloadsAt(PAGER, 6));
        assertEquals(Arrays.asList("a working 'public search tabs' hook (it threw java.lang.IllegalStateException)",
                "a working 'downloads tab' hook (it threw java.lang.IllegalStateException)"),
                HookStatus.missing(FamilyNames.HIDE_POPULAR_APPS));
    }

    @Test public void everyPauseReasonSearchesAsTelegramDoes() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), anyChange());
            PauseForTests.resume();
        }
        assertTrue(anyChange());
    }

    @Test public void unavailableOrUnreadableSettingsSearchAsTelegramDoesAndSaySo() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        SettingsContextRule.withoutContext(() -> assertFalse(anyChange()));
        SettingReadsForTests.breakReads(Settings.SEARCH_ONLY_MY_CHATS);
        assertFalse(anyChange());
        assertEquals(Collections.singletonList("a working 'search only my chats switch read' hook (it threw java.lang.NullPointerException)"),
                HookStatus.missing(FamilyNames.HIDE_POPULAR_APPS));
    }

    @Test public void untilPatchedTheStubsChangeNothing() {
        assertFalse(MyChatsSearch.publicTab(CHANNELS));
        assertFalse(MyChatsSearch.downloadsTab(DOWNLOADS));
        assertNull(MyChatsSearch.tabs(PAGER));
        assertNull(MyChatsSearch.lastHashtag(CONTROLLER, PUBLIC_POSTS_SEARCH));
        assertFalse(MyChatsSearch.ownChat(CONTROLLER, "catlovers"));
        assertNull(MyChatsSearch.foundLists(catSearch()));
        assertEquals(0L, MyChatsSearch.peerKey(CATHY_PEER));
    }

    // Unpatched, the lookUp stub does nothing, so the runtime can't call anything it doesn't know.
    @Test public void theUnpatchedLookUpStubDoesNothing() {
        Settings.SEARCH_ONLY_MY_CHATS.save(true);
        MyChatsSearch.lookup = lookup;
        assertTrue(phones("+15551234", 0, 7));
        assertEquals(Collections.singletonList("Hide popular apps: invoked 1, 0 found, 0 missing. Counted: "
                + "phone number matched on the phone 1"), HookStatus.report());
    }
}
