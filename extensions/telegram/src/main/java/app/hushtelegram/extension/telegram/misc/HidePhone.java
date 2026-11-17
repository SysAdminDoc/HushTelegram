/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;
import java.util.function.IntFunction;

/**
 * Telegram formats a phone number for the screen in one place, and every screen that shows one
 * (the side menu, Settings, profiles, the add contact bar, contact cards) asks it. Each ask comes
 * here instead, and a number that belongs to one of your signed-in accounts comes back with its
 * digits covered. Other people's numbers, and the contact cards Telegram sends, stay as they are.
 */
public final class HidePhone {
    private HidePhone() {}

    /**
     * Asked in place of Telegram's phone formatter.
     *
     * @param formatter Telegram's formatter
     * @param raw the number as stored
     * @return the number as the screen shows it
     */
    public static String shown(Object formatter, String raw) {
        String formatted = stockFormat(formatter, raw);
        return formatted != null && hides(raw) ? cover(formatted) : formatted;
    }

    /**
     * Asked with the number Telegram's "Is this still your number?" card is about to put in its
     * question, right after formatting it. The card asks you to check the number, so it gets the
     * digits back: the number this thread just covered, when [shown] is that number covered. Two
     * accounts' numbers can cover the same way, which is why it's the one just formatted.
     */
    public static String asked(String shown) {
        String real = COVERED.get();
        COVERED.remove();
        if (real == null || shown == null || !mask(real).equals(shown)) return shown;
        HookStatus.counted(FamilyNames.HIDE_PHONE_NUMBER, "number shown in the card asking about it");
        return real;
    }

    /** The last number each thread covered, kept for [asked]. */
    private static final ThreadLocal<String> COVERED = new ThreadLocal<>();

    /** [formatted] with its digits covered, remembered on this thread until [asked]. */
    static String cover(String formatted) {
        COVERED.set(formatted);
        return mask(formatted);
    }

    static boolean hides(String raw) {
        if (!on()) return false;
        try {
            if (!own(raw, HidePhone::ownPhone, accounts())) return false;
            HookStatus.counted(FamilyNames.HIDE_PHONE_NUMBER, "own number covered");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_PHONE_NUMBER, "own number", failure);
            return false;
        }
    }

    /** Whether the switch is on and HushTelegram isn't paused. */
    static boolean on() {
        HookStatus.invoked(FamilyNames.HIDE_PHONE_NUMBER);
        try {
            return Utils.settingsReady() && Settings.HIDE_PHONE_NUMBER.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_PHONE_NUMBER, "switch", failure);
            return false;
        }
    }

    /** Whether [raw] has the same digits as the number of one of the first [count] accounts. */
    static boolean own(String raw, IntFunction<String> phones, int count) {
        String digits = digits(raw);
        if (digits.isEmpty()) return false;
        for (int account = 0; account < count; account++) {
            if (digits.equals(digits(phones.apply(account)))) return true;
        }
        return false;
    }

    /** Every digit becomes a dot, so the plus sign and the spacing still read as a phone number. */
    static String mask(String formatted) {
        StringBuilder out = new StringBuilder(formatted.length());
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            out.append(Character.isDigit(c) ? '•' : c);
        }
        return out.toString();
    }

    private static String digits(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') out.append(c);
        }
        return out.toString();
    }

    /** Telegram's own formatting. Replaced with the formatter's method when patching. */
    public static String stockFormat(Object formatter, String raw) { return raw; }

    /** The number of a signed-in account, or null. Replaced when patching. */
    public static String ownPhone(int account) { return null; }

    /** How many accounts Telegram can hold. Replaced when patching. */
    public static int accounts() { return 0; }
}
