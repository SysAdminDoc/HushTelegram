/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Keeps search to your own chats. Search's Channels, Apps and Posts tabs, and the public posts tab
 * a hashtag opens there, leave the tab row, and the Downloads shortcut finds its tab where it now
 * sits. In the Chats tab, public accounts, sponsored accounts and public posts under a hashtag are
 * never asked for, each taking a way past its request Telegram already has. Your chats, contacts and
 * messages from chats you're in still show, and the media tabs stay. A hashtag opened in a chat
 * still searches that chat and your messages, and its public posts tab finishes empty without asking.
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

    /** At the start of a hashtag's public posts search. True ends it through {@link #endPublicPosts} instead. */
    public static boolean skipPublicPosts() {
        if (!on()) return false;
        HookStatus.counted(FamilyNames.HIDE_POPULAR_APPS, "public search lookup skipped");
        return true;
    }

    /**
     * Ends a hashtag's public posts search with nothing found, the way Telegram ends one for a
     * channel name it can't find. It runs once the call that started the search has returned, so
     * the screen that asked has set itself up for an answer.
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
