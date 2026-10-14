/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Asks {@link MyChatsSearch} about its tab and account search hooks with stand-ins, since its stubs
 * answer no until patched.
 */
public final class MyChatsSearchForTests {
    private MyChatsSearchForTests() {}

    /**
     * Whether an account search answer of one of your own matches and one of everyone else's keeps
     * only yours, and only the account yours names.
     */
    public static boolean dropsPublicResults() {
        ArrayList<Object> others = new ArrayList<>(Collections.singletonList(2L));
        ArrayList<Object> users = new ArrayList<>(Arrays.asList(1L, 2L));
        ArrayList<?>[] lists = {new ArrayList<>(Collections.singletonList(1L)), others, users, new ArrayList<>()};
        Function<Object, ArrayList<?>[]> was = MyChatsSearch.listsOf;
        ToLongFunction<Object> keys = MyChatsSearch.keyOf;
        MyChatsSearch.listsOf = found -> lists;
        MyChatsSearch.keyOf = item -> (Long) item;
        try {
            MyChatsSearch.dropPublicResults(new Object());
            return others.isEmpty() && users.equals(Collections.singletonList(1L));
        } finally {
            MyChatsSearch.listsOf = was;
            MyChatsSearch.keyOf = keys;
        }
    }

    /** Whether a public tab leaves a list of one public tab and one of your own. */
    public static boolean dropsPublicTabs() {
        Object chats = new Object();
        Object channels = new Object();
        Predicate<Object> was = MyChatsSearch.isPublic;
        MyChatsSearch.isPublic = tab -> tab == channels;
        try {
            ArrayList<Object> tabs = new ArrayList<>(Arrays.asList(chats, channels));
            MyChatsSearch.dropPublicTabs(tabs);
            return tabs.size() == 1;
        } finally {
            MyChatsSearch.isPublic = was;
        }
    }

    /** Whether the Downloads shortcut goes to the tab Downloads is on rather than the place Telegram picked. */
    public static boolean movesDownloads() {
        Object downloads = new Object();
        Function<Object, List<?>> tabs = MyChatsSearch.tabsOf;
        Predicate<Object> found = MyChatsSearch.isDownloads;
        MyChatsSearch.tabsOf = pager -> Arrays.asList(new Object(), new Object(), downloads);
        MyChatsSearch.isDownloads = tab -> tab == downloads;
        try {
            return MyChatsSearch.downloadsAt(new Object(), 5) == 2;
        } finally {
            MyChatsSearch.tabsOf = tabs;
            MyChatsSearch.isDownloads = found;
        }
    }
}
