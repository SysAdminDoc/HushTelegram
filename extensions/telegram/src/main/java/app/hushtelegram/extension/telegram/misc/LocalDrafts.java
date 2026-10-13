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
 * draft still comes back when you reopen the chat or restart. A copy with nothing in it still goes
 * out: that's how Telegram clears a draft, and it also clears one the server kept from before.
 * Drafts the server sends, from another device or saved before the switch was on, are merged the
 * way Telegram always merges them.
 */
public final class LocalDrafts {
    private LocalDrafts() {}

    /** Reads a saveDraft request. Tests swap in their own reader, since only a patched app has the real one. */
    static volatile Predicate<Object> content = LocalDrafts::carries;

    /**
     * In front of Telegram's saveDraft request, after the draft was saved on the phone. True keeps
     * the request from the server.
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
        HookStatus.counted(FamilyNames.DISABLE_DRAFT_PREVIEWS, carries ? "draft kept on the phone" : "empty draft clear sent");
        return carries;
    }

    /**
     * Whether a saveDraft request holds anything: text, formatting, a reply, a suggested post, a rich
     * message, media or a message effect. One with none of them is Telegram clearing the draft. The
     * patch writes this from the request's fields, and until then it holds every draft back.
     */
    public static boolean carries(Object request) {
        return true;
    }
}
