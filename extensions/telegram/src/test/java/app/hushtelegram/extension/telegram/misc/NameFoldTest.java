/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class NameFoldTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final String BOLD = "𝐉𝐨𝐡𝐧 𝐒𝐦𝐢𝐭𝐡";

    @Before public void setUp() {
        Settings.SEARCH_STYLED_NAMES.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.SEARCH_STYLED_NAMES);
        Settings.SEARCH_STYLED_NAMES.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultNamesTransliterateAsTelegramDoes() {
        assertFalse(Settings.SEARCH_STYLED_NAMES.get());
        assertStock();
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("Counted:"));
    }

    /** Each style becomes the plain lowercase letters search compares a lowercased query with. */
    @Test public void onStyledLettersComeOutPlain() {
        Settings.SEARCH_STYLED_NAMES.save(true);
        Map<String, String> folds = new LinkedHashMap<>();
        folds.put(BOLD, "john smith");
        folds.put("𝑀𝑎𝑟𝑖𝑎 ℎ", "maria h");        // italic, with Planck's h filling its gap
        folds.put("𝘐𝘵𝘢𝘭𝘪𝘤", "italic");            // sans-serif italic
        folds.put("𝔄𝔫𝔫𝔞 ℭ", "anna c");             // Fraktur, and its letterlike C
        folds.put("𝔸𝕟𝕟𝕒 ℂℍ", "anna ch");           // double-struck
        folds.put("𝒜ℬ𝒸", "abc");                    // script
        folds.put("Ｗｉｄｅ", "wide");              // fullwidth
        folds.put("Ⓐⓝⓝ", "ann");                   // circled
        folds.put("🄰🄽🄽", "ann");                   // squared
        folds.put("ᵗⁱⁿʸ", "tiny");                  // superscript
        folds.put("𝐀нна 😀 Bob", "aнна 😀 Bob");   // only the styled letter changes
        for (Map.Entry<String, String> fold : folds.entrySet()) {
            assertEquals(fold.getKey(), fold.getValue(), NameFold.fold(fold.getKey(), true, false));
        }
        // Telegram lowercases the name first, which leaves styled letters as they are, and then
        // looks for the query at the start of a word in what this gives back.
        String name = BOLD.toLowerCase(Locale.ROOT);
        assertFalse(name.contains(" smith"));
        assertTrue(NameFold.fold(name, true, false).contains(" smith"));
        assertTrue(HookStatus.report().toString(),
                HookStatus.report().toString().contains(NameFold.FOLDED + " " + (folds.size() + 1)));
    }

    /** Digits, other scripts and symbols that fold to more than one letter are left as written. */
    @Test public void onDigitsLookalikesAndSymbolsStay() {
        Settings.SEARCH_STYLED_NAMES.save(true);
        for (String kept : new String[]{"𝟏𝟐𝟑", "１２３", "+1 555 0100", "Аnna", "Αlpha", "𝚨𝚤", "№ 5", "Shop™", "℃",
                "Анна", "😀", "", "plain ascii"}) {
            assertSame(kept, kept, NameFold.fold(kept, true, false));
        }
        assertEquals("ann 𝟏𝟐", NameFold.fold("𝐚𝐧𝐧 𝟏𝟐", true, false));
    }

    /** Passport's transliteration keeps case and isn't search, so it's never folded. */
    @Test public void passportsCaseKeepingPathIsLeftAlone() {
        Settings.SEARCH_STYLED_NAMES.save(true);
        assertSame(BOLD, NameFold.fold(BOLD, true, true));
        assertSame(BOLD, NameFold.fold(BOLD, false, true));
        assertEquals("john smith", NameFold.fold(BOLD, false, false));
        assertNull(NameFold.fold(null, true, false));
    }

    @Test public void pauseMissingContextAndBrokenReadsLeaveItStock() {
        Settings.SEARCH_STYLED_NAMES.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertStock();
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(this::assertStock);
        SettingReadsForTests.breakReads(Settings.SEARCH_STYLED_NAMES);
        assertStock();
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("styled names switch"));
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("Counted:"));
    }

    private void assertStock() {
        assertSame(BOLD, NameFold.fold(BOLD, true, false));
        assertSame("Ｗｉｄｅ", NameFold.fold("Ｗｉｄｅ", true, false));
        assertNull(NameFold.fold(null, true, false));
    }
}
