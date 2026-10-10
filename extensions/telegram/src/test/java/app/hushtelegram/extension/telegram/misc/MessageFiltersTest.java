/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MessageFiltersTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long GROUP = -1001234567890L;

    @Before public void reset() { restore(); }
    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.HIDE_BY_KEYWORD);
        Settings.HIDE_BY_KEYWORD.resetToDefault();
        Settings.MESSAGE_FILTERS_GROUPS.resetToDefault();
        Settings.MESSAGE_FILTERS_CHANNELS.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultEveryMessageKeepsItsType() {
        assertFalse(Settings.HIDE_BY_KEYWORD.get());
        assertEquals("", Settings.MESSAGE_FILTERS_GROUPS.get());
        assertEquals(0, MessageFilters.type(new Object(), 0));
        assertEquals("Telegram's own hidden messages stay hidden", -1, MessageFilters.type(new Object(), -1));
        assertEquals(5, MessageFilters.type(null, 5));
        assertTrue(String.join("\n", HookStatus.report()).contains(FamilyNames.HIDE_BY_KEYWORD));
    }

    @Test public void eachKindOfChatUsesItsOwnListAndNeverAPrivateChatOrYourOwnMessage() {
        Settings.HIDE_BY_KEYWORD.save(true);
        Settings.MESSAGE_FILTERS_GROUPS.save("free crypto");
        Settings.MESSAGE_FILTERS_CHANNELS.save("sponsored");
        assertTrue(MessageFilters.on());
        assertTrue("a group", MessageFilters.hides(GROUP, false, false, "Get FREE Crypto now"));
        assertTrue("a basic group", MessageFilters.hides(-42L, false, false, "free crypto"));
        assertFalse("the channels list in a group", MessageFilters.hides(GROUP, false, false, "sponsored"));
        assertTrue("a channel's post", MessageFilters.hides(GROUP, false, true, "This post is Sponsored"));
        assertFalse("the groups list on a channel's post", MessageFilters.hides(GROUP, false, true, "free crypto"));
        assertFalse("a private chat", MessageFilters.hides(7L, false, false, "free crypto"));
        assertFalse("a secret chat", MessageFilters.hides(0x4000000000000007L, false, false, "free crypto"));
        assertFalse("your own message", MessageFilters.hides(GROUP, true, false, "free crypto"));
        assertFalse("a message without text", MessageFilters.hides(GROUP, false, false, null));
        assertFalse(MessageFilters.hides(GROUP, false, false, ""));
        assertFalse(MessageFilters.hides(GROUP, false, false, "free stuff, crypto later"));
        // The unpatched stubs put every message in a private chat, so it keeps its type.
        assertEquals(3, MessageFilters.type(new Object(), 3));
    }

    @Test public void wordsMatchAnywhereAndSlashesMakeAnExpressionBothIgnoringCase() {
        List<MessageFilters.Filter> filters = parsed("Überweisung\n/\\bgive\\s*away\\b/\n  padded  ");
        assertEquals(3, filters.size());
        assertTrue(MessageFilters.matches(filters, "bitte ÜBERWEISUNG heute"));
        assertTrue(MessageFilters.matches(filters, "Big GiveAway today"));
        assertTrue(MessageFilters.matches(filters, "give away"));
        assertFalse("a word boundary", MessageFilters.matches(filters, "giveaways"));
        assertTrue("trimmed", MessageFilters.matches(filters, "...padded..."));
        assertFalse(MessageFilters.matches(filters, "nothing here"));
        assertTrue("plain text isn't an expression", MessageFilters.matches(parsed("a.b"), "x a.b y"));
        assertFalse(MessageFilters.matches(parsed("a.b"), "axb"));
        assertTrue("slashes inside a line are text", MessageFilters.matches(parsed("1/2/3"), "on 1/2/3"));
    }

    @Test public void aFilterReadsNoFurtherThanTelegramsLongestMessage() {
        String padding = new String(new char[MessageFilters.MAX_TEXT - 4]).replace('\0', ' ');
        assertTrue(MessageFilters.matches(parsed("spam"), padding + "spam"));
        assertFalse(MessageFilters.matches(parsed("spam"), padding + " spam"));
        assertFalse(MessageFilters.matches(parsed("/spam$/"), padding + " spam"));
    }

    @Test public void aLineThatCantBeUsedIsSaidAndLeftOutOfTheChat() {
        assertNull(MessageFilters.problem("plain words"));
        assertNull(MessageFilters.problem("/(ab)+c/"));
        assertNull(MessageFilters.problem("(a+)+ as text"));
        assertEquals(MessageFilters.Problem.BROKEN, MessageFilters.problem("/(unclosed/"));
        assertEquals(MessageFilters.Problem.BROKEN, MessageFilters.problem("/[a-/"));
        assertEquals(MessageFilters.Problem.TOO_LONG, MessageFilters.problem(repeat('x', MessageFilters.MAX_FILTER_CHARS + 1)));
        assertNull(MessageFilters.problem(repeat('x', MessageFilters.MAX_FILTER_CHARS)));
        for (String slow : new String[]{"/(a+)+/", "/(a*)*b/", "/(\\w+\\s?)+$/", "/((ab)*)+/", "/(a{2,})+/", "/(?:x+y?)*/",
                "/(a)\\1/", "/(?<w>a)\\k<w>/", "/(a+){2,5}/"}) {
            assertEquals(slow, MessageFilters.Problem.SLOW, MessageFilters.problem(slow));
        }
        for (String fine : new String[]{"/[+*]+/", "/\\(a+\\)+/", "/(a+)?/", "/(a{1})+/", "/(?:foo|bar)+/", "/\\Q(a+)+\\E/",
                "/a{2}b+/", "/[(]a+[)]+/"}) {
            assertNull(fine, MessageFilters.problem(fine));
        }
        List<MessageFilters.Filter> kept = parsed("/(unclosed/\n/(a+)+/\nspam");
        assertEquals("only the line that works", 1, kept.size());
        assertTrue(MessageFilters.matches(kept, "spam"));
    }

    @Test public void aListIsItsTrimmedLinesUpToItsLimit() {
        assertEquals(Collections.emptyList(), MessageFilters.lines(null));
        assertEquals(Collections.emptyList(), MessageFilters.lines(" \n\n "));
        assertEquals(Arrays.asList("a", "b c"), MessageFilters.lines(" a \r\n\n b c "));
        assertEquals("a\nb c", MessageFilters.join(MessageFilters.lines(" a \r\n\n b c ")));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < MessageFilters.MAX_FILTERS + 5; i++) many.add("word" + i);
        assertEquals(MessageFilters.MAX_FILTERS, parsed(MessageFilters.join(many)).size());
    }

    @Test public void aChangedListIsReadAgain() {
        Settings.MESSAGE_FILTERS_GROUPS.save("first");
        assertSame("read once and kept", MessageFilters.filters(false), MessageFilters.filters(false));
        assertTrue(MessageFilters.matches(MessageFilters.filters(false), "first"));
        Settings.MESSAGE_FILTERS_GROUPS.save("second");
        assertFalse(MessageFilters.matches(MessageFilters.filters(false), "first"));
        assertTrue(MessageFilters.matches(MessageFilters.filters(false), "second"));
        assertTrue(MessageFilters.filters(true).isEmpty());
    }

    @Test public void pausingAnEarlyStartOrAnUnreadableSwitchKeepsTheMessage() {
        Settings.HIDE_BY_KEYWORD.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), MessageFilters.on());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse(MessageFilters.on()));
        SettingReadsForTests.breakReads(Settings.HIDE_BY_KEYWORD);
        assertFalse(MessageFilters.on());
        assertFalse(HookStatus.missing(FamilyNames.HIDE_BY_KEYWORD).isEmpty());
    }

    private static List<MessageFilters.Filter> parsed(String list) {
        Settings.MESSAGE_FILTERS_GROUPS.save(list);
        return MessageFilters.filters(false);
    }

    private static String repeat(char c, int count) {
        return new String(new char[count]).replace('\0', c);
    }
}
