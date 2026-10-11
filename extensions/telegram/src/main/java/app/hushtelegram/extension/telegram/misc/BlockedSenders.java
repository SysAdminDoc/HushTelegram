/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.os.SystemClock;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Telegram still shows what someone you've blocked writes in a group you share. With the switch
 * on, an open group or supergroup leaves their messages out of its list, the way Telegram leaves
 * out a message it can't show, someone else's reply drops its quote of one the way a reply to a
 * deleted message does, and a new one from them doesn't notify. Nothing is deleted or
 * reported, and private chats and a channel's own posts stay as they are. Telegram loads only
 * the first 20 people you've blocked until its Blocked users page scrolls, so while the switch
 * is on, group messages also ask Telegram for the rest of the list a page at a time.
 */
public final class BlockedSenders {
    private BlockedSenders() {}

    /** The shortest gap between two asks for another page of the blocked list. */
    static final long PAGE_GAP_MS = 2000L;

    /** When the last ask for another page went out, or 0 before the first. */
    private static volatile long lastPage;

    /** The type Telegram gives a message it doesn't show. */
    static final int HIDDEN = -1;

    /**
     * Asked where an open chat reads a message's type to decide whether to list it.
     *
     * @param message Telegram's message
     * @param type the message's own type
     * @return the same type, or {@link #HIDDEN} for a blocked person's message in a group
     */
    public static int type(Object message, int type) {
        if (type < 0 || message == null || !on()) return type;
        try {
            long sender = sender(message);
            if (!listedInGroup(chat(message), post(message), sender)) return type;
            topUp(message);
            if (!blocked(message, sender)) return type;
            HookStatus.counted(FamilyNames.HIDE_BLOCKED_IN_GROUPS, "message hidden");
            return HIDDEN;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_BLOCKED_IN_GROUPS, "message", failure);
            return type;
        }
    }

    /**
     * Asked where Telegram decides a new message doesn't notify, right after its first reason
     * (an imported forward), so a blocked person's message in a group is skipped the same way.
     *
     * @param message Telegram's message
     * @param skipped whether Telegram skips it already
     * @return true when the message shouldn't notify
     */
    public static boolean skipNotification(Object message, boolean skipped) {
        if (skipped || message == null || !on()) return skipped;
        try {
            long sender = sender(message);
            if (!listedInGroup(chat(message), post(message), sender)) return false;
            topUp(message);
            if (!blocked(message, sender)) return false;
            HookStatus.counted(FamilyNames.HIDE_BLOCKED_IN_GROUPS, "notification skipped");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_BLOCKED_IN_GROUPS, "notification", failure);
            return false;
        }
    }

    /**
     * Asked where a message decides its reply header has a message to show, once Telegram has
     * said yes. A yes here leaves the header out, as Telegram does for a reply to a deleted
     * message. The chat's message cell asks while it draws, so this stays a few field reads and a
     * lookup, and the blocked list's next page is left to {@link #type}.
     *
     * @param message Telegram's message, the reply
     * @return true when the message replied to is a blocked person's in a group
     */
    public static boolean hideReply(Object message) {
        if (message == null || !on()) return false;
        try {
            Object quoted = quoted(message);
            if (quoted == null) return false;
            long sender = sender(quoted);
            if (!listedInGroup(chat(quoted), post(quoted), sender) || !blocked(quoted, sender)) return false;
            HookStatus.counted(FamilyNames.HIDE_BLOCKED_IN_GROUPS, "reply quote hidden");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_BLOCKED_IN_GROUPS, "reply quote", failure);
            return false;
        }
    }

    /**
     * Asks Telegram for the next page of the blocked list, at most once every
     * {@link #PAGE_GAP_MS}, on the main thread where Telegram asks for its own pages.
     */
    static void topUp(Object message) {
        long now = SystemClock.uptimeMillis();
        if (lastPage != 0L && now - lastPage < PAGE_GAP_MS) return;
        lastPage = now;
        Utils.runOnMainThread(() -> {
            try {
                if (loadMoreBlocked(message)) HookStatus.counted(FamilyNames.HIDE_BLOCKED_IN_GROUPS, "blocked list page asked");
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.HIDE_BLOCKED_IN_GROUPS, "blocked list", failure);
            }
        });
    }

    /** Forgets when the last page was asked for. */
    static void resetPaging() {
        lastPage = 0L;
    }

    /**
     * A person's message in a group or supergroup. Private and secret chats have positive IDs, a
     * channel's posts carry Telegram's post flag, and a sender with a negative ID is a chat or
     * channel speaking, never a person.
     */
    static boolean listedInGroup(long chat, boolean post, long sender) {
        return chat < 0 && !post && sender > 0;
    }

    /** Whether the switch is on and HushTelegram isn't paused. */
    static boolean on() {
        HookStatus.invoked(FamilyNames.HIDE_BLOCKED_IN_GROUPS);
        try {
            return Utils.settingsReady() && Settings.HIDE_BLOCKED_IN_GROUPS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_BLOCKED_IN_GROUPS, "switch", failure);
            return false;
        }
    }

    /** The message's chat. Replaced when patching. */
    public static long chat(Object message) { return 0L; }

    /** Who sent the message. Replaced when patching. */
    public static long sender(Object message) { return 0L; }

    /** Whether the message is a channel post. Replaced when patching. */
    public static boolean post(Object message) { return false; }

    /** The message this one replies to, or null before it's loaded. Replaced when patching. */
    public static Object quoted(Object message) { return null; }

    /**
     * Asks the message's account for the next page of its blocked list unless the list is
     * complete or a page is on its way. Replaced when patching.
     *
     * @return whether a page was asked for
     */
    public static boolean loadMoreBlocked(Object message) { return false; }

    /** Whether the message's account has blocked {@code peer}. Replaced when patching. */
    public static boolean blocked(Object message, long peer) { return false; }
}
