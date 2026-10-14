/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.lang.ref.WeakReference;

import app.hushtelegram.extension.shared.diagnostics.HookStatus;

/**
 * The Chats tab's "+" phone number match, run again right after a username lookup that lost its
 * text. Keep searches on this phone and Search only my chats each hook that call, so with both on,
 * the second hook finds the match already made for that search and leaves it.
 */
final class PhoneMatch {
    private PhoneMatch() {}

    /** The username helper's lookup, parameter for parameter. */
    interface Lookup {
        void run(Object helper, String query, boolean usernames, boolean chats, boolean bots, boolean self,
                 boolean groupsOnly, long channel, boolean phoneNumbers, int type, int searchId, long except,
                 Runnable done);
    }

    /**
     * The helper, search number and text the last match ran for. Telegram counts searches from 1
     * again once the box is cleared, so the number alone repeats; both hooks read the same step's
     * text, the very same string, which a later search doesn't share. Weak, so a closed search
     * screen isn't kept.
     */
    private static WeakReference<Object> lastHelper = new WeakReference<>(null);
    private static WeakReference<String> lastQuery = new WeakReference<>(null);
    private static int lastSearch;

    /** Whether a match could run for this text at all: a "+" number with no channel, which would send its member search to the server. */
    static boolean wanted(Object helper, String query, long channel) {
        return helper != null && query != null && channel == 0 && query.startsWith("+");
    }

    /**
     * Runs the helper's lookup with the text and no username lookup, unless it already ran for this
     * helper's search, and counts it under {@code family}.
     *
     * @return true when the match ran, so the tab has rows to show
     */
    static boolean run(Lookup lookup, String family, Object helper, String query, boolean chats, boolean bots,
                       boolean self, boolean groupsOnly, long channel, boolean phoneNumbers, int type,
                       int searchId, long except, Runnable done) {
        synchronized (PhoneMatch.class) {
            if (lastHelper.get() == helper && lastSearch == searchId && lastQuery.get() == query) return false;
        }
        try {
            lookup.run(helper, query, false, chats, bots, self, groupsOnly, channel, phoneNumbers, type, searchId, except, done);
            synchronized (PhoneMatch.class) {
                lastHelper = new WeakReference<>(helper);
                lastQuery = new WeakReference<>(query);
                lastSearch = searchId;
            }
            HookStatus.counted(family, "phone number matched on the phone");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(family, "search phone match", failure);
            return false;
        }
    }
}
