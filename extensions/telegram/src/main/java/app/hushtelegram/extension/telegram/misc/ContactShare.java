/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Adding someone who isn't a contact yet from their chat, Telegram offers to share your phone
 * number with them and ticks that box for you. With the switch on the box starts clear, so your
 * number goes only if you tick it. The box, and what saving sends, stay Telegram's own: saving
 * still passes whatever the box shows.
 */
public final class ContactShare {
    private ContactShare() {}

    /** Add contact screens whose box was already set up, so one whose view is built again keeps what you chose. */
    private static final Map<Object, Boolean> SET_UP = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * From the add contact screen as it builds its view, right after Telegram ticks the box.
     *
     * @param screen  the add contact screen
     * @param stock   what Telegram just put in the box
     * @param current what the box held before that: unticked on a new screen, your choice on one built again
     * @return what the box holds now
     */
    public static boolean startsTicked(Object screen, boolean stock, boolean current) {
        if (!on()) return stock;
        try {
            if (SET_UP.put(screen, Boolean.TRUE) != null) return current;
            if (stock) HookStatus.counted(FamilyNames.HIDE_PHONE_NUMBER, "number sharing started unticked");
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_PHONE_NUMBER, "share box", failure);
            return stock;
        }
    }

    private static boolean on() {
        HookStatus.invoked(FamilyNames.HIDE_PHONE_NUMBER);
        try {
            return Utils.settingsReady() && Settings.SHARE_NUMBER_UNTICKED.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_PHONE_NUMBER, "share box switch read", failure);
            return false;
        }
    }

    static void resetForTests() {
        SET_UP.clear();
    }
}
