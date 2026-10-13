/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.util.function.Predicate;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Keeps unsent drafts on this phone. Telegram saves a draft here first and then sends a copy to its
 * server so your other devices show it. The hook sits between the two, on that copy only, so the
 * draft still comes back when you reopen the chat or restart. A copy with something in it goes out
 * emptied, as the clear Telegram sends when you empty the text box: the server drops any copy it had
 * from before, so your other devices don't show an old one and the server has none to put back over
 * this phone's. A copy with nothing in it goes out as it is. Drafts the server sends, from another
 * device, are merged the way Telegram always merges them.
 */
public final class LocalDrafts {
    private LocalDrafts() {}

    /** Reads a saveDraft request. Tests swap in their own reader, since only a patched app has the real one. */
    static volatile Predicate<Object> content = LocalDrafts::carries;

    /** Empties a saveDraft request, saying whether it did. Tests swap in their own, as for {@link #content}. */
    static volatile Predicate<Object> emptier = LocalDrafts::clear;

    /**
     * In front of Telegram's saveDraft request, after the draft was saved on the phone. With the switch
     * on, a request with content is emptied before it goes. True keeps the request from the server
     * altogether, for a draft that can't be read or emptied.
     */
    public static boolean keepOnPhone(Object request) {
        HookStatus.invoked(FamilyNames.DISABLE_DRAFT_PREVIEWS);
        try {
            if (!Utils.settingsReady() || !Settings.LOCAL_DRAFTS.get()) return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.DISABLE_DRAFT_PREVIEWS, "local drafts switch read", t);
            return false;
        }
        boolean carries;
        try {
            carries = content.test(request);
        } catch (Throwable t) {
            // The switch is on, so a draft that can't be read stays on the phone too.
            HookStatus.threw(FamilyNames.DISABLE_DRAFT_PREVIEWS, "draft read", t);
            return true;
        }
        if (!carries) {
            HookStatus.counted(FamilyNames.DISABLE_DRAFT_PREVIEWS, "empty draft clear sent");
            return false;
        }
        HookStatus.counted(FamilyNames.DISABLE_DRAFT_PREVIEWS, "draft kept on the phone");
        try {
            return !emptier.test(request);
        } catch (Throwable t) {
            // Partly emptied or not, it isn't sent.
            HookStatus.threw(FamilyNames.DISABLE_DRAFT_PREVIEWS, "draft emptying", t);
            return true;
        }
    }

    /**
     * Whether a saveDraft request holds anything: text, formatting, a reply to a message, a suggested
     * post, a rich message, media or a message effect. One with none of them is Telegram clearing the
     * draft. The patch writes this from the request's fields, and until then it holds every draft back.
     */
    public static boolean carries(Object request) {
        return true;
    }

    /**
     * Takes everything {@link #carries} looks at out of a saveDraft request, leaving the chat it's for,
     * and answers true. The patch writes this from the request's fields. Until then it empties nothing
     * and answers false, so a draft with something in it isn't sent at all.
     */
    public static boolean clear(Object request) {
        return false;
    }
}
