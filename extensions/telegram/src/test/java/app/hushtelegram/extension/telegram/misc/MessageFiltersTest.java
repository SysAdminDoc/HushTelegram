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

    @Test public void anExpressionReadsLessOfAMessageThanPlainText() {
        String padding = new String(new char[MessageFilters.MAX_EXPRESSION_TEXT - 4]).replace('\0', ' ');
        assertTrue(MessageFilters.matches(parsed("/spam/"), padding + "spam"));
        assertFalse(MessageFilters.matches(parsed("/spam/"), padding + " spam"));
        assertTrue("plain text reads on", MessageFilters.matches(parsed("spam"), padding + " spam"));
        assertTrue("the expression still finds what's early", MessageFilters.matches(parsed("/^x.*/"), "x" + padding + "  spam"));
        assertTrue("the end of a message", MessageFilters.matches(parsed("/spam$/"), padding + "spam"));
        assertFalse("the cut isn't the end", MessageFilters.matches(parsed("/spam$/"), padding + "spam tail"));
        assertFalse("nor a line break with more after it", MessageFilters.matches(parsed("/spam$/"), padding + "spam\r\ntail"));
        assertTrue(MessageFilters.matches(parsed("/\\bspam\\b/"), padding + "spam tail"));
        assertFalse("a word that goes on past the cut", MessageFilters.matches(parsed("/\\bspam\\b/"), padding + "spammy"));
    }

    /**
     * The editor's tester asks which line would hide a message. It gets the chat's answer: the first
     * line that matches, with a line that can't be used passed over but still counted, case set
     * aside in every script, and the same cut-offs.
     */
    @Test public void theFirstLineThatWouldHideAMessageIsNamed() {
        List<String> lines = Arrays.asList("airdrop", "/(a+)+$/", "/free\\s+crypto/", "crypto");
        assertEquals(MessageFilters.Problem.SLOW, MessageFilters.problem(lines.get(1)));
        assertEquals("the first that matches wins", 0, MessageFilters.firstHiding(lines, "crypto AIRDROP"));
        assertEquals("the lines after an unusable one keep their places", 2, MessageFilters.firstHiding(lines, "Get FREE   crypto"));
        assertEquals(3, MessageFilters.firstHiding(lines, "crypto news"));
        assertEquals("the unusable line is never run", -1, MessageFilters.firstHiding(lines, "aaaa"));
        assertEquals(-1, MessageFilters.firstHiding(lines, ""));
        assertEquals(-1, MessageFilters.firstHiding(Collections.emptyList(), "crypto"));

        assertEquals(0, MessageFilters.firstHiding(Collections.singletonList("über"), "ÜBER alles"));
        assertEquals(0, MessageFilters.firstHiding(Collections.singletonList("/привет\\s+мир/"), "ПРИВЕТ МИР"));

        String padding = repeat(' ', MessageFilters.MAX_EXPRESSION_TEXT);
        List<String> both = Arrays.asList("/spam/", "spam");
        assertEquals("an expression stops reading at its cut, plain text reads on", 1, MessageFilters.firstHiding(both, padding + "spam"));
        assertEquals(-1, MessageFilters.firstHiding(both, repeat(' ', MessageFilters.MAX_TEXT) + "spam"));
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
                "/(a)\\1/", "/(?<w>a)\\k<w>/", "/(a+){2,5}/",
                // A repeated choice, or a repeated group with an optional part, splits a run of text many ways.
                "/(.|\\s)*x/", "/(a|aa)+b/", "/(?:foo|bar)+/", "/(a?a)+/", "/((a|b)c)*/",
                // Two open-ended repeats, lazy or not, or one with too many optional parts beside it.
                "/.*.*x/", "/\\w+\\s*\\w+!/", "/.*?x.*?y/", "/a+a{1,}/", "/x+\\w{1,99}/", "/.{0,99}.{0,99}.{0,99}/",
                "/a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?a?/",
                // What's quoted in a class, a control character and a code point hide no bracket or count.
                "/[\\Q[\\E](a|aa)+b/", "/\\c[(a|aa)+b/", "/\\x{61}+\\x{61}+\\x{61}+b/",
                // The comments flag changes what spaces and # mean.
                "/(?x)(a|aa) +b/", "/(?x)a#{99999999999}/", "/(?ix-s:(a|aa) +)b/",
                // {0,1} is a ?, so these are (a?a)+ and twenty optional parts.
                "/(a{0,1}a)+b/", "/" + times("a{0,1}", 20) + times("a", 20) + "b/",
                // A look behind tries each place its text could start.
                "/(?<=.{0,1000}c)x/", "/(?<!\\d{1,3})x/", "/(?<=a?b)x/",
                // Choices that can match at the same place still multiply.
                "/" + times("(?:a|a)", 16) + "b/", "/" + times("(?:a|ab)", 16) + "b/"}) {
            assertEquals(slow, MessageFilters.Problem.SLOW, MessageFilters.problem(slow));
        }
        assertTrue("ICU reads a comment", MessageFilters.slow("(?#[)(a|aa)+b"));
        assertNotNull(MessageFilters.problem("/(?#[)(a|aa)+b/"));
        // Read without overflowing, and refused for what it would walk rather than passed as one try.
        assertTrue("a count past any number", MessageFilters.slow("a{0,99999999999999999999}"));
        assertTrue(MessageFilters.slow("a{99999999999}b+"));
        for (String fine : new String[]{"/[+*]+/", "/\\(a?\\)+/", "/(a+)?/", "/(a{1})+/", "/(?:foo|bar) now/", "/\\Q(a+)+\\E/",
                "/a{2}b+/", "/[(]a?[)]+/", "/(ab)+c/", "/https?:\\/\\/\\S+/", "/a+?b/", "/a*+b/", "/(?i)x+/",
                "/\\d{3}[-.]?\\d{4}/", "/\\bfree\\s{1,9}crypto\\s{1,9}now\\b/", "/\\(?\\d{3}\\)? ?\\d+/",
                "/\\b(?:buy|sell)\\s+crypto\\b/",
                // A choice's sides are tried one after the other.
                "/spam.*|scam.*/", "/(?:foo|bar).*/", "/(?:buy.*|sell.*) now/",
                "/[\\Q]\\E]+x/", "/\\x{61}+b/", "/\\p{L}+ crypto/",
                // Only one plain word of a list can match at a place.
                "/(?:buy|sell|earn|win|get) ?\\$? ?\\d+/", "/(?:free|cheap|bonus) (?:btc|eth|usdt|sol)\\s+now/",
                "/(a+){0,1}b/", "/x{0,1}y+/", "/(?<=\\s)crypto/", "/(?<!\\d{3})x+/"}) {
            assertNull(fine, MessageFilters.problem(fine));
        }
        List<MessageFilters.Filter> kept = parsed("/(unclosed/\n/(a+)+/\nspam");
        assertEquals("only the line that works", 1, kept.size());
        assertTrue(MessageFilters.matches(kept, "spam"));
    }

    /**
     * A count used to cost one try however large it was, so {@code (?:(?=x)){16777215}(?!x)} passed
     * and ran its assertion sixteen million times at a place without ever matching "x". A count is
     * now walked as many times as it says, for each way of getting to it.
     */
    @Test public void aCountIsWalkedAsOftenAsItSaysSoALargeOneIsRefused() {
        for (String slow : new String[]{"/(?:(?=x)){16777215}(?!x)/", "/a{16777215}/", "/a{50000}/", "/(?:ab){20000}/",
                // After a repeat, a count is walked again for each length the repeat tries.
                "/.*a{1000}y/", "/.*\\d{40}/", "/\\w+x{17}/"}) {
            assertEquals(slow, MessageFilters.Problem.SLOW, MessageFilters.problem(slow));
        }
        // An assertion or an empty group reads no text, so the text never ends its count early. A
        // spread is tried from its largest number, and a count with no end has to reach its least.
        for (String slow : new String[]{"(?=x){16777215}y", "(?:\\b){16777215}x", "(?:^){40000}x", "(){16777215}x",
                "(?:){16777215}x", "(?:(?=x)){0,16777215}(?!x)", "(?:(?=x)){40000,}(?!x)", "[a-f0-9]{40000}",
                // The same count on a longer group is more walking.
                "(?:(?=a)(?=b)(?=c)(?=d)){2048}x", "a?b?c?d?e?(?:(?=x)){2000}"}) {
            assertTrue(slow, MessageFilters.slow(slow));
        }
        for (String fine : new String[]{"/\\d{16}/", "/0x[a-f0-9]{40}/", "/[a-f0-9]{64}/", "/(?:ha){3}/",
                "/\\d{4}-\\d{2}-\\d{2}/", "/\\d{3}\\.\\d{3}\\.\\d{3}\\.\\d{3}/", "/.{1000}/", "/^.{500,}/", "/@\\w{5,32}/",
                "/\\b\\w{2}\\b/",
                // A count before the repeat is walked once, and a short one after it a few times.
                "/0x[a-f0-9]{40}.*airdrop/", "/\\d{40}.*/", "/.*\\d{8}/", "/free\\s+\\d{2}%/"}) {
            assertNull(fine, MessageFilters.problem(fine));
        }
        assertFalse("a small count on an assertion", MessageFilters.slow("(?=x){3}x"));
        assertFalse(MessageFilters.slow("(?:(?=x)){200}(?!x)"));
        assertTrue("an assertion's count runs at every place of every message", MessageFilters.slow("(?:(?=x)){2048}(?!x)"));
    }

    /**
     * ICU drops an empty \Q\E before it reads the expression, so {@code (a+)\Q\E{2}b} runs as
     * {@code (a+){2}b}. The check read the quote as a character of its own and let the count
     * through as if it were on that.
     */
    @Test public void anEmptyQuoteDoesntPartAGroupFromItsCount() {
        for (String slow : new String[]{"/(a+)\\Q\\E{2}b/", "/(a+)\\Q\\E{3}b/", "/(?:a|aa)\\Q\\E{30}x/",
                "/" + times("(?=x)", 10) + "\\Q\\E{32000}/", "/(?:(?=x))\\Q\\E\\Q\\E{32000}/"}) {
            assertEquals(slow, MessageFilters.Problem.SLOW, MessageFilters.problem(slow));
        }
        // A quote with something in it is that text, and a count after it is on its last character.
        assertNull(MessageFilters.problem("/(ab)\\Qc\\E{2}/"));
        assertNull(MessageFilters.problem("/\\Qa+\\E{2}b/"));
    }

    /**
     * An empty group weighed one pass, so {@code (){32000}x} fit a line, and six of them a list:
     * about sixty-five million empty passes a line on any long message, since nothing reading text
     * can run out and end the count early.
     */
    @Test public void aCountOnSomethingThatReadsNoTextWeighsMore() {
        for (String slow : new String[]{"/(){32000}x/", "/(?:){300}x/", "/(?:\\b){300}x/", "/(?:^){300}x/", "/(?:\\b(?=a)){300}a/",
                "/(?:(?<=a)){300}b/"}) {
            assertEquals(slow, MessageFilters.Problem.SLOW, MessageFilters.problem(slow));
        }
        for (String fine : new String[]{"/(){2}x/", "/(?:\\b){3}x/", "/(?=x){3}x/", "/(?:a(?=b)){300}/", "/(?:(?=a)a){300}/"}) {
            assertNull(fine, MessageFilters.problem(fine));
        }
    }

    /**
     * {@code \s+} before {@code 0x} can only stop where the spaces do, since a shorter run leaves a
     * space where the 0 has to be. Reading it as two thousand ways of reaching the count after it
     * refused ordinary filters for wallet addresses, and a list saved before quietly stopped using them.
     */
    @Test public void aRepeatThatCanOnlyStopInOnePlaceIsOneWayToTheCountAfterIt() {
        for (String fine : new String[]{"/airdrop\\s+0x[a-f0-9]{40}/", "/wallet\\s+[13][a-km-zA-HJ-NP-Z1-9]{25,34}/",
                "/\\w+ x{17}/", "/\\d+[a-z]{30}/", "/[a-f]+-\\d{40}/", "/\\s+?0x{40}/", "/\\s++0x{40}/",
                // A run of a's ends where the b's start, so this walks no more than .*x does.
                "/a+b{1,}/", "/x+y{1,99}/"}) {
            assertNull(fine, MessageFilters.problem(fine));
        }
        for (String slow : new String[]{
                // The next character could be one of the repeat's own.
                "/\\w+x{17}/", "/\\s+\\s[a-f]{40}/", "/.+0x{40}/", "/\\S+0x{40}/",
                // Case is set aside, so these two classes share every letter.
                "/[a-z]+[A-Z]{40}/",
                // What comes next may not be there at all.
                "/\\s+0?x{40}/", "/\\s+0*x{40}/", "/\\s+0{0,2}x{40}/",
                // A class that could take in a character past the first plane counts as sharing one.
                "/[^a]+[\\x{1D400}]{40}/",
                // A character named by its number is that character, not its last digit: these are a+a{1000}.
                "/\\x61+a{1000}!/", "/\\u0061+a{1000}!/", "/\\0141+a{1000}!/",
                // Ignoring case, ß matches ss, so a run of s's could stop in more than one place.
                "/s+ß{40}!/", "/ß+s{40}!/",
                // Capital ẞ stays one letter both ways, but it lowercases to ß, so it matches ss too.
                "/s+ẞ{40}!/", "/ẞ+s{40}!/", "/[ẞ]+s{40}!/"}) {
            assertEquals(slow, MessageFilters.Problem.SLOW, MessageFilters.problem(slow));
        }
        List<MessageFilters.Filter> kept = parsed("/airdrop\\s+0x[a-f0-9]{40}/");
        assertEquals("a saved filter for an address is used again", 1, kept.size());
        assertTrue(MessageFilters.matches(kept, "Claim your AIRDROP   0x" + repeat('a', 40)));
        assertFalse(MessageFilters.matches(kept, "airdrop 0x" + repeat('a', 39)));
    }

    /**
     * A hundred wallet lines ask whether a space can be a 0 a hundred times, and the list is read on
     * the thread that reads a message's type. The answer is worked out once and kept, and the list
     * still keeps every line its tries allow. What's kept stays bounded however many pairs a list asks.
     */
    @Test public void aHundredWalletLinesReadTheirPairOnce() {
        MessageFilters.forgetPairs();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) lines.add("/w" + i + "\\s+0x[a-f0-9]{40}/");
        List<MessageFilters.Filter> kept = parsed(String.join("\n", lines));
        // Each line's 2,089 tries leave room for 98 in the list's share.
        assertEquals(98, kept.size());
        assertEquals("the pair was read more than once", 1, MessageFilters.pairsRead());
        assertEquals(1, MessageFilters.pairsKnown());
        assertTrue(MessageFilters.matches(kept, "w97 0x" + repeat('a', 40)));

        for (char c = '\u4E00'; c < '\u4E00' + MessageFilters.NEVER_BOTH_KEPT + 20; c++) {
            assertNull(MessageFilters.problem("/\\s+" + c + "{40}/"));
        }
        assertEquals(MessageFilters.NEVER_BOTH_KEPT, MessageFilters.pairsKnown());
        assertEquals(1 + MessageFilters.NEVER_BOTH_KEPT + 20, MessageFilters.pairsRead());
    }

    /**
     * Each of a hundred lines with a count just under the limit passed alone, and together they
     * were a hundred times the work on every message. A list's expressions share one number, and a
     * line past it is left out like any other that can't be used.
     */
    @Test public void aListsExpressionsShareTheirTries() {
        // One open-ended repeat with four optional parts is as much as one line may hold.
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 7; i++) lines.add("/x.*a?b?c?d?" + i + "/");
        long[] spent = new long[1];
        for (String line : lines.subList(0, 6)) assertNull(line, MessageFilters.problem(line, spent));
        assertEquals(6 * MessageFilters.MAX_TRIES, spent[0]);
        assertEquals("the line the list runs out at", MessageFilters.Problem.SLOW, MessageFilters.problem(lines.get(6), spent));
        assertEquals("a refused line spends nothing", 6 * MessageFilters.MAX_TRIES, spent[0]);
        assertNull("it's fine on its own", MessageFilters.problem(lines.get(6)));
        assertNull("plain text costs nothing", MessageFilters.problem("spam", spent));
        assertEquals(6 * MessageFilters.MAX_TRIES, spent[0]);
        assertNull("a cheaper expression still fits", MessageFilters.problem("/scam\\d/", spent));
        assertEquals(6 * MessageFilters.MAX_TRIES + 1, spent[0]);

        lines.add("spam");
        lines.add("/scam\\d/");
        List<MessageFilters.Filter> kept = parsed(MessageFilters.join(lines));
        assertEquals("six that fit, then the words and the cheap expression", 8, kept.size());
        assertTrue(MessageFilters.matches(kept, "x and 5"));
        assertFalse("the seventh was never built", MessageFilters.matches(kept, "x and 6"));
        assertTrue(MessageFilters.matches(kept, "scam7"));

        List<String> full = new ArrayList<>();
        for (int i = 0; i < MessageFilters.MAX_FILTERS; i++) full.add("/word" + i + "\\s+now/");
        assertEquals("a full list with one open-ended repeat a line", MessageFilters.MAX_FILTERS,
                parsed(MessageFilters.join(full)).size());
        full.set(0, "/word0\\s+now\\d?/");
        assertEquals("one more optional part and the last line no longer fits", MessageFilters.MAX_FILTERS - 1,
                parsed(MessageFilters.join(full)).size());

        List<String> hundred = new ArrayList<>();
        for (int count = 16777000; count < 16777100; count++) hundred.add("/(?:(?=x)){" + count + "}(?!x)/");
        assertTrue(parsed(MessageFilters.join(hundred)).isEmpty());
    }

    /**
     * A list saved before the check, or brought in with a settings file, keeps its text for the
     * editor. In the chat the slow line has no expression built for it, so there's nothing to run.
     */
    @Test public void aSlowLineAlreadySavedIsKeptAsTextAndNeverBuiltOrRun() {
        String saved = "/(?:(?=x)){16777215}(?!x)/\nspam";
        Settings.HIDE_BY_KEYWORD.save(true);
        Settings.MESSAGE_FILTERS_GROUPS.save(saved);
        List<MessageFilters.Filter> kept = MessageFilters.filters(false);
        assertEquals(1, kept.size());
        assertNull("only the plain line is left", kept.get(0).pattern);
        assertFalse(MessageFilters.hides(GROUP, false, false, "x"));
        assertTrue(MessageFilters.hides(GROUP, false, false, "more spam"));
        assertEquals(saved, Settings.MESSAGE_FILTERS_GROUPS.savedValue());
    }

    @Test public void aListIsItsTrimmedLinesUpToItsLimit() {
        assertEquals(Collections.emptyList(), MessageFilters.lines(null));
        assertEquals(Collections.emptyList(), MessageFilters.lines(" \n\n "));
        assertEquals(Arrays.asList("a", "b c"), MessageFilters.lines(" a \r\n\n b c "));
        assertEquals("a\nb c", MessageFilters.join(MessageFilters.lines(" a \r\n\n b c ")));
        assertEquals("a carriage return alone ends a line too", Arrays.asList("a", "b", "c"),
                MessageFilters.lines("a\rb\r\rc"));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < MessageFilters.MAX_FILTERS + 5; i++) many.add("word" + i);
        assertEquals(MessageFilters.MAX_FILTERS, parsed(MessageFilters.join(many)).size());
    }

    @Test public void aListFitsASettingsFileByItsWrittenSizeNotItsLength() {
        assertTrue(MessageFilters.fits(Collections.emptyList()));
        assertTrue("full of short Latin lines", MessageFilters.fits(lines(MessageFilters.MAX_FILTERS, 'x', MessageFilters.MAX_FILTER_CHARS)));
        assertFalse("each character takes three bytes",
                MessageFilters.fits(lines(MessageFilters.MAX_FILTERS, '字', MessageFilters.MAX_FILTER_CHARS)));
        assertTrue(MessageFilters.fits(lines(MessageFilters.MAX_FILTERS, '字', 60)));
        assertFalse("a slash is written as two characters",
                MessageFilters.fits(lines(MessageFilters.MAX_FILTERS, '/', MessageFilters.MAX_FILTER_CHARS)));
    }

    private static List<String> lines(int count, char c, int length) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) lines.add(repeat(c, length));
        return lines;
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

    private static String times(String text, int count) {
        return String.join("", Collections.nCopies(count, text));
    }

    private static String repeat(char c, int count) {
        return new String(new char[count]).replace('\0', c);
    }
}
