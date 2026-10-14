/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Keeps search to your own chats. Search's Channels, Apps and Posts tabs, and the public posts tab
 * a hashtag opens there, leave the tab row, and the Downloads shortcut finds its tab where it now
 * sits. In the Chats tab, public accounts, sponsored accounts and public posts under a hashtag are
 * never asked for, each taking a way past its request Telegram already has. Your chats, contacts and
 * messages from chats you're in still show, contacts found by a "+" phone number among them, and the
 * media tabs stay.
 *
 * <p>Everywhere else too: whichever screen looks up accounts by name (New Message, the contact,
 * forward, member, gift and giveaway pickers), the answer keeps only your own contacts and chats.
 * A hashtag opened in a chat still searches that chat and your messages, and a {@code #tag@name}
 * still searches a channel you're in, while public posts, channels you haven't joined and public
 * stories under the tag finish empty without asking.
 */
public final class MyChatsSearch {
    private MyChatsSearch() {}

    /** Whether a tab of search is a public one. Tests swap in their own, since only a patched app has the real one. */
    static volatile Predicate<Object> isPublic = MyChatsSearch::publicTab;

    /** Whether a tab of search is Downloads. Tests swap in their own, as for {@link #isPublic}. */
    static volatile Predicate<Object> isDownloads = MyChatsSearch::downloadsTab;

    /** Search's tab list, from the pager that shows it. Tests swap in their own, as for {@link #isPublic}. */
    static volatile Function<Object, List<?>> tabsOf = MyChatsSearch::tabs;

    /** Finishes a hashtag's public posts search. Tests swap in their own, as for {@link #isPublic}. */
    static volatile PostsFinisher finisher = MyChatsSearch::finishPublicPosts;

    /** Where the finish runs: after the call that started the search returns. Tests run it at once. */
    static volatile Executor later = Utils::runOnMainThread;

    /** The tag a hashtag search of a kind last asked for. Tests swap in their own, as for {@link #isPublic}. */
    static volatile BiFunction<Object, Integer, String> lastHashtagOf = MyChatsSearch::lastHashtag;

    /** Whether a name is an account or a chat you're in. Tests swap in their own, as for {@link #isPublic}. */
    static volatile BiPredicate<Object, String> isOwnChat = MyChatsSearch::ownChat;

    /** An account search answer's lists. Tests swap in their own, as for {@link #isPublic}. */
    static volatile Function<Object, ArrayList<?>[]> listsOf = MyChatsSearch::foundLists;

    /** One number per account or chat, from either or from a peer naming it. Tests swap in their own, as for {@link #isPublic}. */
    static volatile ToLongFunction<Object> keyOf = MyChatsSearch::peerKey;

    /** The username helper's lookup {@link #phones} runs. Tests swap in their own, as for {@link #isPublic}. */
    static volatile PhoneMatch.Lookup lookup = MyChatsSearch::lookUp;

    interface PostsFinisher {
        void finish(Object controller, int searchType, Object[] update);
    }

    /**
     * In front of a public lookup the Chats tab is about to start: sponsored accounts, or public
     * posts under a hashtag. True takes Telegram's own way past it.
     */
    public static boolean keep() {
        if (!on()) return false;
        HookStatus.counted(FamilyNames.HIDE_POPULAR_APPS, "public search lookup skipped");
        return true;
    }

    /**
     * In front of the Chats tab's call to the username helper. Null is what Telegram passes for an
     * empty box, so the helper asks for no public accounts and answers at once.
     *
     * @return {@code query} as it is, or null while search keeps to your chats
     */
    public static String sent(String query) {
        if (query == null || !on()) return query;
        HookStatus.counted(FamilyNames.HIDE_POPULAR_APPS, "public search lookup skipped");
        return null;
    }

    /**
     * Right after the Chats tab's call to the username helper, with what that call was handed and the
     * text it would have had. The empty box {@link #sent} handed the helper also skipped its match of
     * a "+" phone number against your contacts, which needs no server, so the match runs again here
     * with the text and no username lookup. On true the hook has the tab show its rows again, since
     * the helper doesn't tell it about a phone match. With Keep searches on this phone also on,
     * whichever hook comes second finds the match made and leaves it.
     *
     * @return true when the match ran, so the tab has rows to show
     */
    public static boolean phones(Object helper, String query, boolean usernames, boolean chats, boolean bots,
                                 boolean self, boolean groupsOnly, long channel, boolean phoneNumbers, int type,
                                 int searchId, long except, Runnable done) {
        if (!PhoneMatch.wanted(helper, query, channel) || !on()) return false;
        return PhoneMatch.run(lookup, FamilyNames.HIDE_POPULAR_APPS, helper, query, chats, bots, self, groupsOnly,
                channel, phoneNumbers, type, searchId, except, done);
    }

    /**
     * The username helper's lookup on {@code helper}, written in when the patch is applied, since
     * Telegram renames the helper in each build. Until then it does nothing.
     */
    public static void lookUp(Object helper, String query, boolean usernames, boolean chats, boolean bots,
                              boolean self, boolean groupsOnly, long channel, boolean phoneNumbers, int type,
                              int searchId, long except, Runnable done) {}

    /** At the end of the pager's tab list update: the public tabs leave the list it just built. */
    public static void dropPublicTabs(ArrayList<Object> tabs) {
        if (tabs == null || !on()) return;
        try {
            if (tabs.removeIf(isPublic)) HookStatus.counted(FamilyNames.HIDE_POPULAR_APPS, "public search tabs hidden");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_POPULAR_APPS, "public search tabs", failure);
        }
    }

    /**
     * The Downloads shortcut opens search at a fixed place in Telegram's own tab row. With the public
     * tabs gone that's another tab, so it goes to wherever Downloads now is.
     *
     * @param stock the place Telegram picked
     */
    public static int downloadsAt(Object pager, int stock) {
        if (!on()) return stock;
        try {
            List<?> tabs = tabsOf.apply(pager);
            if (tabs == null) return stock;
            for (int at = 0; at < tabs.size(); at++) {
                if (isDownloads.test(tabs.get(at))) return at;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_POPULAR_APPS, "downloads tab", failure);
        }
        return stock;
    }

    /**
     * At the start of any hashtag search but the one through your own messages. With search kept to
     * your chats, it goes ahead only for a {@code #tag@name} whose name is an account or a chat you're
     * in that this phone already knows. Public posts, a channel you haven't joined and a name only
     * Telegram's server could look up end through {@link #endPublicPosts} instead.
     *
     * @param controller Telegram's hashtag search
     * @param hashtag the tag asked for, or null when Telegram carries on with the last one
     * @param searchType which of the hashtag's searches it is
     * @return true to let Telegram search
     */
    public static boolean keepHashtag(Object controller, String hashtag, int searchType) {
        if (!on()) return true;
        try {
            String tag = hashtag != null ? hashtag : lastHashtagOf.apply(controller, searchType);
            // Telegram returns at once for no tag at all, so there's nothing to keep back.
            if (tag == null || tag.isEmpty()) return true;
            int at = tag.indexOf('@');
            String name = at < 0 ? "" : tag.substring(at + 1);
            if (!name.isEmpty() && isOwnChat.test(controller, name)) return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_POPULAR_APPS, "hashtag search", failure);
        }
        HookStatus.counted(FamilyNames.HIDE_POPULAR_APPS, "public search lookup skipped");
        return false;
    }

    /**
     * Once an account search's answer has been read, before any screen sees it: everyone else's
     * matches leave, and so do the accounts and chats only they named. Your own matches stay, with
     * the accounts and chats they name.
     *
     * @param found Telegram's answer, a {@code contacts.found}
     */
    public static void dropPublicResults(Object found) {
        if (found == null || !on()) return;
        try {
            ArrayList<?>[] lists = listsOf.apply(found);
            if (lists == null || lists.length != 4) return;
            // Everyone else's matches go first: they need no names, so even an answer whose names
            // can't be read shows none of them.
            boolean dropped = lists[1] != null && !lists[1].isEmpty();
            if (dropped) lists[1].clear();
            Set<Long> mine = new HashSet<>();
            if (lists[0] != null) {
                for (Object peer : lists[0]) {
                    if (peer != null) mine.add(keyOf.applyAsLong(peer));
                }
            }
            Predicate<Object> others = item -> item == null || !mine.contains(keyOf.applyAsLong(item));
            for (int list = 2; list < 4; list++) {
                if (lists[list] != null && lists[list].removeIf(others)) dropped = true;
            }
            if (dropped) HookStatus.counted(FamilyNames.HIDE_POPULAR_APPS, "public search results dropped");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_POPULAR_APPS, "public search results", failure);
        }
    }

    /**
     * Ends a hashtag search with nothing found, the way Telegram ends one for a channel name it
     * can't find. It runs once the call that started the search has returned, so the screen that
     * asked has set itself up for an answer.
     *
     * @param controller Telegram's hashtag search
     * @param guid the screen that asked
     * @param searchType which of the hashtag's searches it is
     */
    public static void endPublicPosts(Object controller, int guid, int searchType) {
        try {
            Object[] update = emptyUpdate(guid);
            later.execute(() -> {
                try {
                    finisher.finish(controller, searchType, update);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.HIDE_POPULAR_APPS, "public posts finish", failure);
                }
            });
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_POPULAR_APPS, "public posts finish", failure);
        }
    }

    /**
     * What Telegram tells the screen when a hashtag search ends with nothing: the screen, no
     * results, nothing more to load, no arrows, the first result picked and no message to jump to.
     */
    static Object[] emptyUpdate(int guid) {
        return new Object[] {guid, 0, Boolean.TRUE, 0, 0, 0};
    }

    /** Whether {@code tab} is Channels, Apps, Posts or public posts. The patch writes this from the pager's titles. */
    public static boolean publicTab(Object tab) {
        return false;
    }

    /** Whether {@code tab} is Downloads. The patch writes this from the pager's titles. */
    public static boolean downloadsTab(Object tab) {
        return false;
    }

    /** The tab list of search's pager. The patch writes this from the pager's fields; until then there's none. */
    public static ArrayList<?> tabs(Object pager) {
        return null;
    }

    /**
     * Clears the hashtag search's results for {@code searchType}, marks it done and posts
     * {@code update} to the screens. The patch writes this from Telegram's hashtag search.
     */
    public static void finishPublicPosts(Object controller, int searchType, Object[] update) {
    }

    /** The tag the hashtag search of {@code searchType} last asked for. The patch writes this; until then there's none. */
    public static String lastHashtag(Object controller, int searchType) {
        return null;
    }

    /**
     * Whether {@code name} is an account or a chat you're in that this phone already knows. The
     * patch writes this from Telegram's own lookup; until then nothing is.
     */
    public static boolean ownChat(Object controller, String name) {
        return false;
    }

    /**
     * An account search answer's lists: your own matches, everyone else's, then the accounts and the
     * chats they name. The patch writes this from the answer's fields; until then there are none.
     */
    public static ArrayList<?>[] foundLists(Object found) {
        return null;
    }

    /**
     * One number for an account, a chat, or a peer naming either: an account's id, or a chat's id
     * below zero. The patch writes this from Telegram's ids; until then everything is 0.
     */
    public static long peerKey(Object peer) {
        return 0L;
    }

    private static boolean on() {
        HookStatus.invoked(FamilyNames.HIDE_POPULAR_APPS);
        try {
            return Utils.settingsReady() && Settings.SEARCH_ONLY_MY_CHATS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_POPULAR_APPS, "search only my chats switch read", failure);
            return false;
        }
    }
}
