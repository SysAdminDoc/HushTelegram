/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.text.SpannableStringBuilder;
import android.text.Spanned;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * A few characters of the person's own, usually an emoji, drawn after the chat list's title. The
 * chat list builds its title once, in createView: Telegram's logo as an ImageSpan over the app
 * name. {@link #decorate} adds a space and the saved text after it, marked with a {@link Suffix}
 * span, before the title reaches the action bar. Nothing goes to Telegram's servers, and the
 * Premium emoji status, which the action bar draws on its own beside the title, is left alone.
 * Independent of the holiday switch: the holiday logo hat finds the logo through
 * {@link #titleEnd}, so the two work together or apart.
 */
public final class TitleEmoji {
    /** The most the text may hold, in Unicode code points: room for a couple of joined emoji. */
    public static final int MAX_CODE_POINTS = 16;
    /** Keeps the text on the logo's line. */
    private static final char SPACE = (char) 0xa0;

    private TitleEmoji() {}

    /** Marks what {@link #decorate} added, so the title Telegram built can still be told apart. */
    static final class Suffix {}

    /** Called with the chat list's title after Telegram spans its logo, before the action bar gets it. */
    public static void decorate(SpannableStringBuilder title) {
        HookStatus.invoked(FamilyNames.HOLIDAY_LOOK);
        if (title == null) return;
        String text = text();
        if (text.isEmpty()) return;
        try {
            title.append(SPACE + text, new Suffix(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            HookStatus.counted(FamilyNames.HOLIDAY_LOOK, "title emoji shown");
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.HOLIDAY_LOOK, "title emoji", t);
        }
    }

    /** The saved text, cleaned, or empty while paused, before the settings load or when unreadable. */
    static String text() {
        try {
            if (!Utils.settingsReady()) return "";
            String cleaned = clean(Settings.TITLE_EMOJI.get());
            return allowed(cleaned) ? cleaned : "";
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.HOLIDAY_LOOK, "title emoji read", t);
            return "";
        }
    }

    /**
     * The text as it would be saved: no control or direction characters (a right-to-left mark
     * would turn the title around), no line breaks, and single spaces with none at either end.
     * Emoji joiners and variation selectors stay, since joined emoji need them.
     */
    public static String clean(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = out.length() > 0;
                continue;
            }
            if (Character.isISOControl(c) || direction(c)) continue;
            if (space) out.append(' ');
            space = false;
            out.appendCodePoint(c);
        }
        return out.toString();
    }

    /** Whether {@code text} can be saved as it is: already clean, and short enough. Empty is allowed: it shows nothing. */
    public static boolean allowed(String text) {
        return text != null && clean(text).equals(text) && text.codePointCount(0, text.length()) <= MAX_CODE_POINTS;
    }

    /**
     * Where the title Telegram built ends: at the one {@link Suffix} this class added when it runs
     * to the end of the text, otherwise at the end itself.
     */
    static int titleEnd(Spanned title) {
        int end = title.length();
        Suffix[] added = title.getSpans(0, end, Suffix.class);
        if (added.length != 1 || title.getSpanEnd(added[0]) != end) return end;
        return title.getSpanStart(added[0]);
    }

    private static boolean direction(int c) {
        return c == 0x061c || c == 0x200e || c == 0x200f || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069);
    }
}
