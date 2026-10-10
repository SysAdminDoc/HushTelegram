/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.StringSetting;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Messages you'd rather not see, by what they say. With the switch on, an open group or channel
 * leaves out a message whose text or caption matches one of your filters, the way Telegram leaves
 * out a message it can't show. Groups and channels each keep their own list. Private and secret
 * chats and your own messages are never filtered, and nothing is deleted, reported or sent.
 *
 * <p>A filter is one line. A line in slashes, like {@code /free\s+crypto/}, is a regular
 * expression, and any other line matches wherever its text appears. Both ignore case.
 *
 * <p>Android's regular expressions run in ICU, which copies the text and can't be stopped part
 * way, so a time limit can't be put on one. The shapes that can backtrack for seconds or more on a
 * long message are refused instead: a repeat inside a repeated group, a repeated choice like
 * {@code (a|aa)+}, more than one open-ended repeat (or one with many optional parts beside it), a
 * reference back to a group, and a comment or the comments flag, which hide the rest from that
 * check. What's left takes time at worst in proportion to the square of
 * the text, and an expression reads no more than {@link #MAX_EXPRESSION_TEXT} of a message. Plain
 * text reads up to Telegram's longest message.
 */
public final class MessageFilters {
    private MessageFilters() {}

    /** The type Telegram gives a message it doesn't show. */
    static final int HIDDEN = -1;
    /** How many filters one list keeps. */
    public static final int MAX_FILTERS = 100;
    /** How long one filter can be. */
    public static final int MAX_FILTER_CHARS = 200;
    /** How much of a message plain text reads: Telegram's longest message. */
    static final int MAX_TEXT = 4096;
    /**
     * How much of a message an expression reads. With {@link #MAX_TRIES} that holds its worst case
     * under about seventy million steps, on a message written against that one filter.
     */
    static final int MAX_EXPRESSION_TEXT = 2048;
    /**
     * How many ways an expression may try to match at one place in a message: one open-ended
     * repeat with a few optional parts or short counts beside it. Two open-ended repeats are past it.
     */
    static final long MAX_TRIES = MAX_EXPRESSION_TEXT * 16L;
    /**
     * How large one list may be once it's written into a settings file, so a saved list always
     * exports to a file the import takes: two lists of this, the switches and the size stay well
     * under {@code SettingsBackup.MAX_BYTES}.
     */
    public static final int MAX_LIST_BYTES = 24 * 1024;

    private static volatile Parsed groups = Parsed.NONE;
    private static volatile Parsed channels = Parsed.NONE;

    /**
     * Asked where an open chat reads a message's type to decide whether to list it.
     *
     * @param message Telegram's message
     * @param type the message's own type
     * @return the same type, or {@link #HIDDEN} for a message that matches a filter
     */
    public static int type(Object message, int type) {
        if (type < 0 || message == null || !on()) return type;
        try {
            if (!hides(chat(message), out(message), post(message), text(message))) return type;
            HookStatus.counted(FamilyNames.HIDE_BY_KEYWORD, "message hidden");
            return HIDDEN;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_BY_KEYWORD, "message", failure);
            return type;
        }
    }

    /**
     * Whether a message is left out: someone else's, in a group or channel, matching a filter of
     * that kind of chat. Private and secret chats have positive IDs.
     */
    static boolean hides(long chat, boolean out, boolean post, @Nullable String text) {
        if (chat >= 0 || out) return false;
        List<Filter> filters = filters(post);
        return !filters.isEmpty() && matches(filters, text);
    }

    /** Whether the switch is on and HushTelegram isn't paused. */
    static boolean on() {
        HookStatus.invoked(FamilyNames.HIDE_BY_KEYWORD);
        try {
            return Utils.settingsReady() && Settings.HIDE_BY_KEYWORD.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_BY_KEYWORD, "switch", failure);
            return false;
        }
    }

    /** A channel's posts have their own list; everything else in a group uses the groups list. */
    static List<Filter> filters(boolean channel) {
        StringSetting setting = channel ? Settings.MESSAGE_FILTERS_CHANNELS : Settings.MESSAGE_FILTERS_GROUPS;
        String list = setting.get();
        Parsed cached = channel ? channels : groups;
        if (!cached.source.equals(list)) {
            cached = new Parsed(list);
            if (channel) channels = cached;
            else groups = cached;
        }
        return cached.filters;
    }

    /**
     * Whether any filter matches the text: plain text in the first {@link #MAX_TEXT} characters,
     * an expression in the first {@link #MAX_EXPRESSION_TEXT}.
     */
    static boolean matches(List<Filter> filters, @Nullable String text) {
        if (text == null || text.isEmpty()) return false;
        String read = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        String lower = read.toLowerCase(Locale.ROOT);
        for (Filter filter : filters) {
            if (filter.pattern != null ? found(filter.pattern, read) : lower.contains(filter.words)) return true;
        }
        return false;
    }

    /**
     * Whether an expression matches in the first {@link #MAX_EXPRESSION_TEXT} characters. The text
     * past that point still counts as being there, so {@code $} doesn't match at the cut and
     * {@code \b} doesn't see a word end in the middle of one.
     */
    private static boolean found(Pattern pattern, String text) {
        if (text.length() <= MAX_EXPRESSION_TEXT) return pattern.matcher(text).find();
        // Three characters past the cut are more than the longest line break, so $ can't match at
        // it. A look ahead at the cut can read them too, and nothing past them.
        Matcher matcher = pattern.matcher(text.substring(0, Math.min(text.length(), MAX_EXPRESSION_TEXT + 3)));
        return matcher.region(0, MAX_EXPRESSION_TEXT).useTransparentBounds(true).useAnchoringBounds(false).find();
    }

    /** One line of a list, ready to match. */
    static final class Filter {
        @Nullable final String words;
        @Nullable final Pattern pattern;

        private Filter(@Nullable String words, @Nullable Pattern pattern) {
            this.words = words;
            this.pattern = pattern;
        }
    }

    /** What's wrong with a line, so the editor can say. */
    public enum Problem {
        /** Longer than {@link #MAX_FILTER_CHARS}. */
        TOO_LONG,
        /** An expression that doesn't compile. */
        BROKEN,
        /** An expression that can take too long on a long message, by the shapes {@link #slow} refuses. */
        SLOW
    }

    /**
     * The lines of a list that hold a filter: trimmed, without blanks, in order. Any line break
     * ends a line, so a pasted carriage return never stays inside one.
     */
    public static List<String> lines(@Nullable String list) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<String> lines = new ArrayList<>();
        for (String line : list.split("\r\n|\r|\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }
        return lines;
    }

    /** A list the way it's saved: one filter a line, no blanks. */
    public static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    /** Whether a list fits in a settings file: no more than {@link #MAX_LIST_BYTES} written out. */
    public static boolean fits(List<String> lines) {
        return new JSONArray(lines).toString().getBytes(StandardCharsets.UTF_8).length <= MAX_LIST_BYTES;
    }

    /** What's wrong with one line, or null when it can be used. */
    @Nullable
    public static Problem problem(String line) {
        if (line.length() > MAX_FILTER_CHARS) return Problem.TOO_LONG;
        String expression = expression(line);
        if (expression == null) return null;
        try {
            Pattern.compile(expression);
        } catch (IllegalArgumentException broken) {
            // PatternSyntaxException, or the odd flag or length refusal, which is one too.
            return Problem.BROKEN;
        }
        try {
            return slow(expression) ? Problem.SLOW : null;
        } catch (RuntimeException unread) {
            // An expression the check can't read through isn't one it can vouch for.
            return Problem.SLOW;
        }
    }

    /** The expression inside a line in slashes, or null for a line of plain text. */
    @Nullable
    static String expression(String line) {
        return line.length() > 2 && line.startsWith("/") && line.endsWith("/") ? line.substring(1, line.length() - 1) : null;
    }

    /**
     * Whether an expression repeats a group that holds a repeat or a choice of its own, like
     * {@code (a+)+}, {@code (a?a)+}, {@code (a|aa)+} or {@code (.|\s)*}; refers back to a group, like
     * {@code (a)\1}; or has repeats that together leave more than {@link #MAX_TRIES} ways to match
     * at one place, which two open-ended repeats like {@code .*.*x} always do. Those are what can
     * backtrack for seconds or minutes on a long message. The ways of a choice's sides add up, since
     * they're tried one after the other, so {@code spam.*|scam.*} is fine, and a list of plain words
     * like {@code (?:buy|sell)} counts once, since only one of them can match at a place. A look
     * behind is tried from each place its text could start, so a repeat of more than one length
     * inside one is refused, and {@code {0,1}} is read as the {@code ?} it is. Escapes, quoted text and
     * character classes are read past, so {@code [+*]}, {@code \+} and {@code \x{61}} hold no repeat,
     * and a {@code ?} or {@code +} right after a repeat makes it lazy or possessive rather than
     * repeating again. A comment, or the x flag that turns on comments and drops spaces, hides what
     * follows from this reading, so either one is refused too.
     */
    static boolean slow(String expression) {
        // For each open group: whether something inside it repeats or is optional, whether it holds a
        // choice, and whether it looks behind.
        List<boolean[]> open = new ArrayList<>();
        // For the whole expression and each open group: the tries of its choices read so far, added
        // up, of the choice being read, multiplied, and where a plain group's text starts (-1 for
        // any other group).
        List<long[]> cost = new ArrayList<>();
        cost.add(new long[]{0, 1, -1});
        boolean[] closedJustBefore = null;
        boolean openedJustBefore = false;
        boolean afterRepeat = false;
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            boolean[] closed = null;
            boolean opened = false;
            boolean repeat = false;
            long[] current = cost.get(cost.size() - 1);
            if (c == '\\') {
                if (i + 1 < n) {
                    char next = expression.charAt(i + 1);
                    if (next >= '1' && next <= '9' || next == 'k') return true;
                }
                i = escapeEnd(expression, i);
            } else if (c == '[') {
                i = classEnd(expression, i);
            } else if (c == '(') {
                // A comment, or a flag that turns spaces and # into something else, reads text this
                // scan can't follow.
                if (expression.startsWith("(?#", i) || commentsFlag(expression, i)) return true;
                boolean behind = expression.startsWith("(?<=", i) || expression.startsWith("(?<!", i);
                open.add(new boolean[]{false, false, behind});
                long start = expression.startsWith("(?:", i) ? i + 3 : expression.startsWith("(?", i) ? -1 : i + 1;
                cost.add(new long[]{0, 1, start});
                opened = true;
                i++;
            } else if (c == ')') {
                if (!open.isEmpty()) {
                    closed = open.remove(open.size() - 1);
                    if (!open.isEmpty()) {
                        boolean[] outer = open.get(open.size() - 1);
                        outer[0] |= closed[0];
                        outer[1] |= closed[1];
                    }
                }
                if (cost.size() > 1) {
                    long[] group = cost.remove(cost.size() - 1);
                    long[] outer = cost.get(cost.size() - 1);
                    // Plain words no one of which starts another can't both match at one place, so
                    // a list of them is tried once.
                    long tries = group[2] >= 0 && distinctWords(expression.substring((int) group[2], i)) ? 1 : group[0] + group[1];
                    outer[1] = capped(outer[1] * capped(tries));
                    if (outer[1] > MAX_TRIES) return true;
                }
                i++;
            } else if (c == '|') {
                if (!open.isEmpty()) open.get(open.size() - 1)[1] = true;
                // Choices are tried one after another, so their tries add up.
                current[0] = capped(current[0] + current[1]);
                current[1] = 1;
                if (current[0] > MAX_TRIES) return true;
                i++;
            } else if (c == '?' && openedJustBefore) {
                // (?: (?= (?<name> and the like say how a group works; they don't repeat anything.
                i++;
            } else if ((c == '?' || c == '+') && afterRepeat) {
                // Lazy or possessive: the same repeat, not another one.
                i++;
            } else if (c == '*' || c == '+' || c == '?' || c == '{' && repeats(expression, i)) {
                long[] count = c == '{' ? bounds(expression, i) : null;
                // An optional group is tried once or not at all, so only a repeated one is refused here.
                boolean optional = c == '?' || count != null && count[1] == 1;
                if (!optional && closedJustBefore != null && (closedJustBefore[0] || closedJustBefore[1])) return true;
                // A look behind is tried from every place its text could start, which a repeat of
                // more than one length multiplies.
                if ((count == null || count[0] != count[1]) && behind(open)) return true;
                current[1] = capped(current[1] * (c == '?' ? 2 : c == '{' ? spread(expression, i) : MAX_EXPRESSION_TEXT));
                if (current[0] + current[1] > MAX_TRIES) return true;
                if (!open.isEmpty()) open.get(open.size() - 1)[0] = true;
                repeat = true;
                i = c == '{' ? expression.indexOf('}', i) + 1 : i + 1;
            } else {
                i++;
            }
            closedJustBefore = closed;
            openedJustBefore = opened;
            afterRepeat = repeat;
        }
        long[] whole = cost.get(0);
        return whole[0] + whole[1] > MAX_TRIES;
    }

    /** Tries held just past {@link #MAX_TRIES}, so multiplying two never overflows. */
    private static long capped(long tries) {
        return Math.min(tries, MAX_TRIES + 1);
    }

    /**
     * Where an escape at {@code at} ends. {@code \x{..}}, {@code \p{..}}, {@code \P{..}} and
     * {@code \N{..}} end past their brace, so a count-like {@code {61}} inside one isn't read as a
     * count; {@code \c} names the character after it, which may be a bracket; {@code \Q} quotes
     * everything up to {@code \E}.
     */
    private static int escapeEnd(String expression, int at) {
        int n = expression.length();
        if (at + 1 >= n) return n;
        char next = expression.charAt(at + 1);
        if (next == 'Q') {
            int end = expression.indexOf("\\E", at + 2);
            return end < 0 ? n : end + 2;
        }
        if (next == 'c') return Math.min(n, at + 3);
        if ((next == 'x' || next == 'p' || next == 'P' || next == 'N') && at + 2 < n && expression.charAt(at + 2) == '{') {
            int close = expression.indexOf('}', at + 3);
            return close < 0 ? n : close + 1;
        }
        return at + 2;
    }

    /**
     * Whether a group at {@code at} sets flags, like {@code (?i)} or {@code (?x-s:...)}, and one of
     * them is x, which makes spaces and # mean something else.
     */
    private static boolean commentsFlag(String expression, int at) {
        if (!expression.startsWith("(?", at)) return false;
        boolean comments = false;
        for (int i = at + 2; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == ':' || c == ')') return comments;
            if (!Character.isLetter(c) && c != '-') return false;
            if (c == 'x') comments = true;
        }
        return false;
    }

    /**
     * How many ways a count at {@code at}, already known to repeat, gives: {2,5} gives four, {3}
     * gives one, and one with no end gives as many as {@code *}.
     */
    private static long spread(String expression, int at) {
        long[] count = bounds(expression, at);
        if (count[1] < 0) return MAX_EXPRESSION_TEXT;
        return Math.max(1, Math.min(count[1] - count[0] + 1, MAX_EXPRESSION_TEXT));
    }

    /**
     * Whether a brace at {@code at} is a count that allows more than one, like {2,} or {1,5}, or
     * leaves a choice of how many, like {0,1}, which is the same as {@code ?}.
     */
    private static boolean repeats(String expression, int at) {
        long[] count = bounds(expression, at);
        return count != null && (count[1] < 0 || count[1] > 1 || count[1] > count[0]);
    }

    /** The least and most a count at {@code at} allows, the most being -1 for one with no end, or null for a brace that isn't a count. */
    @Nullable
    private static long[] bounds(String expression, int at) {
        int close = expression.indexOf('}', at);
        if (close < 0) return null;
        String count = expression.substring(at + 1, close);
        if (!count.matches("\\d+(,\\d*)?")) return null;
        int comma = count.indexOf(',');
        if (comma < 0) return new long[]{number(count), number(count)};
        long least = number(count.substring(0, comma));
        return new long[]{least, comma == count.length() - 1 ? -1 : number(count.substring(comma + 1))};
    }

    /** Whether the place being read is inside a look behind. */
    private static boolean behind(List<boolean[]> open) {
        for (boolean[] group : open) if (group[2]) return true;
        return false;
    }

    /**
     * Whether a group's text is a choice of plain words, none of which starts another once case is
     * set aside, like {@code buy|sell|earn}. At most one of them matches at any place.
     */
    private static boolean distinctWords(String text) {
        if (text.indexOf('|') < 0) return false;
        for (int k = 0; k < text.length(); k++) if ("\\[](){}.*+?^$".indexOf(text.charAt(k)) >= 0) return false;
        String[] words = text.toLowerCase(Locale.ROOT).split("\\|", -1);
        for (int k = 0; k < words.length; k++) {
            if (words[k].isEmpty()) return false;
            for (int m = 0; m < words.length; m++) if (m != k && words[m].startsWith(words[k])) return false;
        }
        return true;
    }

    /** A count's digits as a number, held to a billion so a long run of digits can't overflow. */
    private static long number(String digits) {
        long value = 0;
        for (int k = 0; k < digits.length(); k++) value = Math.min(value * 10 + digits.charAt(k) - '0', 1_000_000_000L);
        return value;
    }

    /** Where a character class that opens at {@code at} ends, past its closing bracket. */
    private static int classEnd(String expression, int at) {
        int i = at + 1;
        if (i < expression.length() && expression.charAt(i) == '^') i++;
        // A bracket first in a class is a character, not its end.
        if (i < expression.length() && expression.charAt(i) == ']') i++;
        int depth = 1;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c == '\\') {
                i = escapeEnd(expression, i);
                continue;
            }
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return i + 1;
            i++;
        }
        return expression.length();
    }

    /** A list read once and kept until it changes. */
    private static final class Parsed {
        static final Parsed NONE = new Parsed("");

        final String source;
        final List<Filter> filters;

        Parsed(String source) {
            this.source = source;
            List<Filter> filters = new ArrayList<>();
            for (String line : lines(source)) {
                if (filters.size() == MAX_FILTERS) break;
                // A line that can't be used, from a file edited by hand, is left out.
                if (problem(line) != null) continue;
                String expression = expression(line);
                filters.add(expression == null
                        ? new Filter(line.toLowerCase(Locale.ROOT), null)
                        : new Filter(null, Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));
            }
            this.filters = Collections.unmodifiableList(filters);
        }
    }

    /** The message's chat. Replaced when patching. */
    public static long chat(Object message) { return 0L; }

    /** Whether you sent the message. Replaced when patching. */
    public static boolean out(Object message) { return false; }

    /** Whether the message is a channel post. Replaced when patching. */
    public static boolean post(Object message) { return false; }

    /** The message's text or caption. Replaced when patching. */
    @Nullable
    public static String text(Object message) { return null; }
}
