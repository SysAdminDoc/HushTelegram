/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import androidx.annotation.Nullable;

import java.text.Normalizer;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Lets search on this phone find names written in styled letters, such as 𝐁𝐨𝐥𝐝 or Ｗｉｄｅ, from
 * plain ones.
 *
 * <p>Telegram's local search lowercases each name and the query, then also compares the form its
 * transliteration gives them, so a Latin query finds a Cyrillic name. That transliteration is where
 * this sits: with the switch on, a styled letter comes out as its plain lowercase letter, so the
 * transliterated name holds the letters someone would type. The name shown is never changed, and a
 * name found only this way shows without highlighting, as a transliterated match does.
 *
 * <p>A letter folds only where Unicode's compatibility form of it is a single plain Latin letter:
 * mathematical, letterlike, fullwidth, circled, squared, superscript and subscript ones. Digits stay
 * as they are, so phone numbers match as before, and letters from another script that only look
 * alike are never treated as Latin. Passport's transliteration, which keeps case, isn't touched.
 */
public final class NameFold {
    private NameFold() {}

    /** What the diagnostic report counts each folded name as. */
    static final String FOLDED = "name in styled letters searched as plain letters";

    /** Below this nothing folds, so most names are passed over after one look at each character. */
    private static final int FIRST_STYLED = 0x02B0;

    /**
     * Injected at the start of LocaleController.getTranslitString(src, ru, onlyEnglish).
     *
     * @return [src] with its styled letters as plain lowercase ones while the switch is on, otherwise [src]
     */
    @Nullable
    public static String fold(@Nullable String src, boolean ru, boolean onlyEnglish) {
        HookStatus.invoked(FamilyNames.HIDE_POPULAR_APPS);
        // onlyEnglish is the case-keeping path Passport uses, not search. A name with nothing past
        // FIRST_STYLED is passed over before the switch is read or the table is built.
        if (src == null || onlyEnglish || !reachesStyled(src)) return src;
        try {
            if (!Utils.settingsReady() || !Settings.SEARCH_STYLED_NAMES.get()) return src;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_POPULAR_APPS, "styled names switch", failure);
            return src;
        }
        try {
            String plain = plain(src);
            if (plain != src) HookStatus.counted(FamilyNames.HIDE_POPULAR_APPS, FOLDED);
            return plain;
        } catch (Throwable failure) {
            // Search goes on with the name as written rather than failing on it.
            HookStatus.threw(FamilyNames.HIDE_POPULAR_APPS, "styled names", failure);
            return src;
        }
    }

    /** Whether [src] has any character a styled letter could start at. */
    private static boolean reachesStyled(String src) {
        for (int at = 0; at < src.length(); at++) {
            if (src.charAt(at) >= FIRST_STYLED) return true;
        }
        return false;
    }

    /** [src] with each styled letter as its plain lowercase letter, or [src] itself when it has none. */
    static String plain(String src) {
        int start = firstStyled(src);
        if (start < 0) return src;
        StringBuilder out = new StringBuilder(src.length());
        out.append(src, 0, start);
        for (int at = start; at < src.length(); ) {
            int point = src.codePointAt(at);
            char letter = Letters.of(point);
            if (letter != 0) out.append(letter);
            else out.appendCodePoint(point);
            at += Character.charCount(point);
        }
        return out.toString();
    }

    /** Where the first styled letter starts, or -1. */
    private static int firstStyled(String src) {
        for (int at = 0; at < src.length(); ) {
            char unit = src.charAt(at);
            if (unit < FIRST_STYLED) {
                at++;
                continue;
            }
            int point = src.codePointAt(at);
            if (Letters.of(point) != 0) return at;
            at += Character.charCount(point);
        }
        return -1;
    }

    /** The folds, worked out once from Unicode's compatibility forms, block by block. */
    private static final class Letters {
        /** First and last code point of each block a styled letter can come from. */
        private static final int[][] BLOCKS = {
                {0x02B0, 0x02E4},   // modifier letters: ʰ ʲ ʳ ʷ ʸ ˡ ˢ ˣ
                {0x1D2C, 0x1D6A},   // phonetic superscripts and subscripts: ᴬ ᵃ ᵇ ᵢ
                {0x2070, 0x209C},   // superscripts and subscripts: ⁱ ⁿ ₐ ₑ
                {0x2100, 0x214F},   // letterlike symbols: ℂ ℍ ℕ ℓ ℯ
                {0x24B6, 0x24E9},   // circled letters: Ⓐ ⓐ
                {0xFF21, 0xFF5A},   // fullwidth letters: Ａ ａ
                {0x1D400, 0x1D6A5}, // mathematical letters: 𝐀 𝑨 𝒜 𝔄 𝔸 𝖠 𝙰
                {0x1F130, 0x1F149}, // squared letters: 🄰
        };
        private static final char[][] FOLDS = new char[BLOCKS.length][];

        static {
            for (int block = 0; block < BLOCKS.length; block++) {
                int first = BLOCKS[block][0];
                char[] folds = new char[BLOCKS[block][1] - first + 1];
                for (int point = first; point <= BLOCKS[block][1]; point++) {
                    String form = Normalizer.normalize(new String(Character.toChars(point)), Normalizer.Form.NFKC);
                    if (form.length() != 1) continue;
                    char letter = form.charAt(0);
                    if ((letter >= 'A' && letter <= 'Z') || (letter >= 'a' && letter <= 'z')) {
                        folds[point - first] = Character.toLowerCase(letter);
                    }
                }
                FOLDS[block] = folds;
            }
        }

        /** The plain lowercase letter [point] is a styled form of, or 0. */
        static char of(int point) {
            for (int block = 0; block < BLOCKS.length; block++) {
                if (point < BLOCKS[block][0]) return 0;
                if (point <= BLOCKS[block][1]) return FOLDS[block][point - BLOCKS[block][0]];
            }
            return 0;
        }
    }
}
