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
 * Keeps what you type in search's Chats tab on this phone. That tab looks through the chats and
 * contacts stored here and, in stock Telegram, also sends the text to the server five ways: for
 * public usernames, for messages in every chat, for messages in an open forum, for sponsored
 * accounts and for public posts under a hashtag. With the switch on none of the five goes out, each
 * taking a way past its request that Telegram already has, so the waiting count still runs down and
 * the spinner stops. The chats, contacts and recent searches on the phone show as before. The other
 * tabs, search inside a chat and contact pickers are Telegram's own.
 */
public final class LocalSearch {
    private LocalSearch() {}

    /**
     * In front of a request the Chats tab is about to build or start: global messages, an open
     * forum's messages, sponsored accounts or public posts. True takes Telegram's own way past it.
     */
    public static boolean keep() {
        if (!on()) return false;
        HookStatus.counted(FamilyNames.DISABLE_DRAFT_PREVIEWS, "search lookup kept on the phone");
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
        HookStatus.counted(FamilyNames.DISABLE_DRAFT_PREVIEWS, "search lookup kept on the phone");
        return null;
    }

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
