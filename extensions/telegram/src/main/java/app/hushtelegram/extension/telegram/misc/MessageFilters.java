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
 * {@code (a|aa)+}, more than one open-ended repeat (or one with many optional parts beside it),
 * and a reference back to a group. What's left takes time at worst in proportion to the square of
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
    /** How much of a message an expression reads, which keeps its worst case to a few million steps. */
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
        String shorter = read.length() > MAX_EXPRESSION_TEXT ? read.substring(0, MAX_EXPRESSION_TEXT) : read;
        for (Filter filter : filters) {
            if (filter.pattern != null ? filter.pattern.matcher(shorter).find() : lower.contains(filter.words)) return true;
        }
        return false;
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
        return slow(expression) ? Problem.SLOW : null;
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
     * backtrack for seconds or minutes on a long message. Escapes and character classes are read
     * past, so {@code [+*]} and {@code \+} are ordinary characters, and a {@code ?} or {@code +}
     * right after a repeat makes it lazy or possessive rather than repeating again.
     */
    static boolean slow(String expression) {
        // For each open group: whether something inside it repeats or is optional, and whether it holds a choice.
        List<boolean[]> open = new ArrayList<>();
        boolean[] closedJustBefore = null;
        boolean openedJustBefore = false;
        boolean afterRepeat = false;
        long tries = 1;
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            boolean[] closed = null;
            boolean opened = false;
            boolean repeat = false;
            if (c == '\\') {
                if (i + 1 < n) {
                    char next = expression.charAt(i + 1);
                    if (next >= '1' && next <= '9' || next == 'k') return true;
                    if (next == 'Q') {
                        int end = expression.indexOf("\\E", i + 2);
                        i = end < 0 ? n : end + 2;
                        continue;
                    }
                }
                i += 2;
            } else if (c == '[') {
                i = classEnd(expression, i);
            } else if (c == '(') {
                open.add(new boolean[2]);
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
                i++;
            } else if (c == '|') {
                if (!open.isEmpty()) open.get(open.size() - 1)[1] = true;
                i++;
            } else if (c == '?' && openedJustBefore) {
                // (?: (?= (?<name> and the like say how a group works; they don't repeat anything.
                i++;
            } else if ((c == '?' || c == '+') && afterRepeat) {
                // Lazy or possessive: the same repeat, not another one.
                i++;
            } else if (c == '*' || c == '+' || c == '?' || c == '{' && repeats(expression, i)) {
                // An optional group is tried once or not at all, so only a repeated one is refused here.
                if (c != '?' && closedJustBefore != null && (closedJustBefore[0] || closedJustBefore[1])) return true;
                tries *= c == '?' ? 2 : c == '{' ? spread(expression, i) : MAX_EXPRESSION_TEXT;
                if (tries > MAX_TRIES) return true;
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
        return false;
    }

    /**
     * How many ways a count at {@code at}, already known to repeat, gives: {2,5} gives four, {3}
     * gives one, and one with no end gives as many as {@code *}.
     */
    private static long spread(String expression, int at) {
        String count = expression.substring(at + 1, expression.indexOf('}', at));
        int comma = count.indexOf(',');
        if (comma < 0) return 1;
        if (comma == count.length() - 1) return MAX_EXPRESSION_TEXT;
        long ways = Long.parseLong(count.substring(comma + 1)) - Long.parseLong(count.substring(0, comma)) + 1;
        return Math.max(1, Math.min(ways, MAX_EXPRESSION_TEXT));
    }

    /** Whether a brace at {@code at} is a count that allows more than one, like {2,} or {1,5}. */
    private static boolean repeats(String expression, int at) {
        int close = expression.indexOf('}', at);
        if (close < 0) return false;
        String count = expression.substring(at + 1, close);
        if (!count.matches("\\d+(,\\d*)?")) return false;
        int comma = count.indexOf(',');
        if (comma < 0) return Integer.parseInt(count) > 1;
        return comma == count.length() - 1 || Integer.parseInt(count.substring(comma + 1)) > 1;
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
                i += 2;
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
