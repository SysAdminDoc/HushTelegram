/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import android.text.SpannableStringBuilder;
import android.text.Spanned;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The text beside the chat list's title. Pause is covered here rather than in PausedHooksTest,
 * which turns every switch on and off: this is text, and its paused answer is empty text.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TitleEmojiTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String TREE = text(0x1F384);
    private static final String SPACE = text(0xa0);

    @Before public void reset() { restore(); }
    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.TITLE_EMOJI);
        Settings.TITLE_EMOJI.resetToDefault();
        Settings.HOLIDAY_LOOK.resetToDefault();
        HookStatus.clear();
    }

    private static String text(int... codePoints) { return new String(codePoints, 0, codePoints.length); }
    private static String report() { return String.join("\n", HookStatus.report()); }

    /** The chat list's title as Telegram builds it, with a stand-in for the logo's span. */
    private static SpannableStringBuilder title(Object logo) {
        SpannableStringBuilder title = new SpannableStringBuilder("Telegram");
        title.setSpan(logo, 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return title;
    }

    private static void assertUntouched(String why) {
        SpannableStringBuilder title = title(new Object());
        TitleEmoji.decorate(title);
        assertEquals(why, "Telegram", title.toString());
        assertEquals(why, 0, title.getSpans(0, title.length(), TitleEmoji.Suffix.class).length);
    }

    @Test public void emptyByDefaultTheTitleIsTelegramsOwn() {
        assertEquals("", Settings.TITLE_EMOJI.get());
        assertUntouched("default");
        SpannableStringBuilder title = title(new Object());
        assertEquals(title.length(), TitleEmoji.titleEnd(title));
        assertTrue(report().contains(FamilyNames.HOLIDAY_LOOK));
        assertFalse(report().contains("title emoji shown"));
    }

    @Test public void theSavedTextFollowsTheTitleAndTheLogoKeepsTheAppName() {
        Settings.TITLE_EMOJI.save(TREE);
        Object logo = new Object();
        SpannableStringBuilder title = title(logo);
        TitleEmoji.decorate(title);
        // A space that doesn't break keeps the text on the logo's line.
        assertEquals("Telegram" + SPACE + TREE, title.toString());
        assertEquals(0, title.getSpanStart(logo));
        assertEquals(8, title.getSpanEnd(logo));
        assertEquals(8, TitleEmoji.titleEnd(title));
        // The title view may copy the text with its spans, and the end still reads the same.
        assertEquals(8, TitleEmoji.titleEnd(new SpannableStringBuilder(title)));
        assertTrue(report().contains("title emoji shown"));
        // Text after the emoji that this class didn't add isn't Telegram's title any more.
        title.append("!");
        assertEquals(title.length(), TitleEmoji.titleEnd(title));
        TitleEmoji.decorate(null);
    }

    @Test public void theEmojiWorksWithTheHolidaySwitchOnOrOff() {
        Settings.TITLE_EMOJI.save(TREE);
        for (boolean holiday : new boolean[]{false, true}) {
            Settings.HOLIDAY_LOOK.save(holiday);
            SpannableStringBuilder title = title(new Object());
            TitleEmoji.decorate(title);
            assertEquals("holiday " + holiday, "Telegram" + SPACE + TREE, title.toString());
        }
        Settings.TITLE_EMOJI.save("");
        assertUntouched("holiday on, no emoji");
    }

    @Test public void pausingAnEarlyStartOrAnUnreadableSettingLeavesTheTitleAlone() {
        Settings.TITLE_EMOJI.save(TREE);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertUntouched(reason.name());
            // What the person saved is kept for after the pause.
            assertEquals(TREE, Settings.TITLE_EMOJI.savedValue());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertUntouched("before the settings are ready"));
        SettingReadsForTests.breakReads(Settings.TITLE_EMOJI);
        assertUntouched("unreadable");
        SettingReadsForTests.mend(Settings.TITLE_EMOJI);
        SpannableStringBuilder title = title(new Object());
        TitleEmoji.decorate(title);
        assertEquals("Telegram" + SPACE + TREE, title.toString());
    }

    @Test public void cleaningKeepsJoinedEmojiAndDropsWhatWouldBreakTheTitle() {
        assertEquals("", TitleEmoji.clean(null));
        assertEquals("", TitleEmoji.clean(" \n\t" + SPACE));
        assertEquals("a b", TitleEmoji.clean("  a \n\t b  "));
        // A right-to-left mark or override would turn the title around, and a control draws as a box.
        assertEquals("ab", TitleEmoji.clean("a" + text(0x202e) + "b" + text(0x200f, 0x7, 0x2066)));
        // Joined emoji keep their joiners, and a heart keeps the selector that makes it colored.
        String family = text(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467);
        String heart = text(0x2764, 0xFE0F);
        assertEquals(family, TitleEmoji.clean(family));
        assertEquals(heart + " " + TREE, TitleEmoji.clean(" " + heart + "\n" + TREE + " "));
    }

    @Test public void onlyCleanShortTextCanBeSavedOrShown() {
        assertTrue("empty shows nothing", TitleEmoji.allowed(""));
        assertTrue(TitleEmoji.allowed(TREE));
        assertFalse(TitleEmoji.allowed(null));
        assertFalse("not cleaned", TitleEmoji.allowed(" " + TREE));
        assertFalse("not cleaned", TitleEmoji.allowed("a\nb"));
        StringBuilder longest = new StringBuilder();
        for (int i = 0; i < TitleEmoji.MAX_CODE_POINTS; i++) longest.append(TREE);
        // Counted in code points: sixteen trees are thirty-two chars.
        assertTrue(TitleEmoji.allowed(longest.toString()));
        assertFalse(TitleEmoji.allowed(longest + TREE));
        // A stored value the editor wouldn't take, say from an older build, shows nothing rather than part of it.
        Settings.TITLE_EMOJI.save(longest + TREE);
        assertUntouched("too long");
        // One that only needs cleaning shows cleaned.
        Settings.TITLE_EMOJI.save("  " + TREE + text(0x200f) + " ");
        SpannableStringBuilder title = title(new Object());
        TitleEmoji.decorate(title);
        assertEquals("Telegram" + SPACE + TREE, title.toString());
    }
}
