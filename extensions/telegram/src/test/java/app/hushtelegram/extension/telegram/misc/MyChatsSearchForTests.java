/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Asks {@link MyChatsSearch} about its tab hooks with stand-in tabs, since its tab stubs answer no until patched. */
public final class MyChatsSearchForTests {
    private MyChatsSearchForTests() {}

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
