/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Keeps what you type in main search on this phone. The Chats tab looks through the chats and
 * contacts stored here and, in stock Telegram, also sends the text to the server five ways: for
 * public usernames, for messages in every chat, for messages in an open forum, for sponsored
 * accounts and for public posts under a hashtag. With the switch on none of the five goes out, each
 * taking a way past its request that Telegram already has, so the waiting count still runs down and
 * the spinner stops. The chats, contacts and recent searches on the phone show as before, and so do
 * contacts found by a "+" phone number, which Telegram matches on the phone.
 *
 * <p>The Channels and Apps tabs take the path Telegram takes for an empty box, which sends nothing
 * and shows no results. The media tabs (Media, Links, Files, Music, Voice) and the Chats tab with
 * a chat or date filter don't start their message search, so they keep what they last showed. The
 * Posts tab is a search of Telegram's server you start yourself, and stays as it is, as do search
 * inside a chat and contact pickers.
 */
public final class LocalSearch {
    private static final String KEPT = "search lookup kept on the phone";

    /** The helper's lookup {@link #phones} runs. Tests swap in their own to see what it's handed. */
    static volatile PhoneMatch.Lookup lookup = LocalSearch::lookUp;

    private LocalSearch() {}

    /**
     * In front of a request a search tab is about to build or start: the Chats tab's global
     * messages, an open forum's messages, sponsored accounts or public posts, and the Channels and
     * Apps tabs' searches. True takes Telegram's own way past it.
     */
    public static boolean keep() {
        if (!on()) return false;
        HookStatus.counted(FamilyNames.DISABLE_DRAFT_PREVIEWS, KEPT);
        return true;
    }

    /**
     * In front of the Chats tab's call to the username helper. Null is what Telegram passes for an
     * empty box, so the helper clears its server results and answers at once.
     *
     * @return {@code query} as it is, or null while searches stay on the phone
     */
    public static String sent(String query) {
        if (query == null || !on()) return query;
        HookStatus.counted(FamilyNames.DISABLE_DRAFT_PREVIEWS, KEPT);
        return null;
    }

    /**
     * In front of a message search with a chat, date or media filter, which a media tab or a
     * filtered Chats tab starts. An empty box still searches as Telegram does, since it sends no text.
     *
     * @return true to skip the search, so the tab keeps what it showed
     */
    public static boolean keepsText(String text) {
        if (text == null || text.isEmpty() || !on()) return false;
        HookStatus.counted(FamilyNames.DISABLE_DRAFT_PREVIEWS, KEPT);
        return true;
    }

    /**
     * Right after the Chats tab's call to the username helper, with what that call was handed and
     * the text it would have had. The helper matches a "+" phone number against your contacts on the
     * phone, which the empty box it got skipped, so the match runs again here with the text and no
     * username lookup. It sends nothing and leaves the waiting count alone. The helper doesn't tell
     * the tab about a phone match, and the tab's own update came before it, so on true the hook has
     * the tab show its rows again. With Search only my chats also on, whichever hook comes second
     * finds the match made and leaves it.
     *
     * @return true when the match ran, so the tab has rows to show
     */
    public static boolean phones(Object helper, String query, boolean usernames, boolean chats, boolean bots,
                                 boolean self, boolean groupsOnly, long channel, boolean phoneNumbers, int type,
                                 int searchId, long except, Runnable done) {
        if (!PhoneMatch.wanted(helper, query, channel) || !on()) return false;
        return PhoneMatch.run(lookup, FamilyNames.DISABLE_DRAFT_PREVIEWS, helper, query, chats, bots, self, groupsOnly,
                channel, phoneNumbers, type, searchId, except, done);
    }

    /**
     * The username helper's lookup on {@code helper}, written in when the patch is applied, since
     * Telegram renames the helper in each build. Until then it does nothing.
     */
    public static void lookUp(Object helper, String query, boolean usernames, boolean chats, boolean bots,
                              boolean self, boolean groupsOnly, long channel, boolean phoneNumbers, int type,
                              int searchId, long except, Runnable done) {}

    /**
     * The line under "No results" in the Chats tab, which gains one saying where the search looked.
     *
     * @param stock Telegram's own line for this query
     */
    public static String emptyText(String stock) {
        if (stock == null || !on()) return stock;
        try {
            return stock + "\n" + L10n.t("Only chats and contacts on this phone were searched.");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DISABLE_DRAFT_PREVIEWS, "search empty line", failure);
            return stock;
        }
    }

    private static boolean on() {
        HookStatus.invoked(FamilyNames.DISABLE_DRAFT_PREVIEWS);
        try {
            return Utils.settingsReady() && Settings.LOCAL_SEARCH.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DISABLE_DRAFT_PREVIEWS, "local search switch read", failure);
            return false;
        }
    }
}
