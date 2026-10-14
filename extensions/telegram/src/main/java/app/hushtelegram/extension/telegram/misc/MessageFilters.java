/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
 * check. A count like {@code {500}} is walked that many times whether or not the text moves on, so
 * a large one is refused too, and a list's expressions share {@link #MAX_LIST_TRIES}. What's left
 * takes time at worst in proportion to the square of
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
    public static final int MAX_TEXT = 4096;
    /**
     * How much of a message an expression reads. With {@link #MAX_TRIES} that holds its worst case
     * under about seventy million steps, on a message written against that one filter.
     */
    static final int MAX_EXPRESSION_TEXT = 2048;
    /**
     * How many ways an expression may try to match at one place in a message: one open-ended
     * repeat with a few optional parts or short counts beside it. Two open-ended repeats are past it.
     * A count's own repeats come out of the same number.
     */
    static final long MAX_TRIES = MAX_EXPRESSION_TEXT * 16L;
    /**
     * How many tries the expressions of one list may add up to: a full list with one open-ended
     * repeat on every line. A hundred lines each at {@link #MAX_TRIES} would be read one after the
     * other on every message.
     */
    static final long MAX_LIST_TRIES = (long) MAX_FILTERS * MAX_EXPRESSION_TEXT;
    /**
     * What one pass of a count weighs when what it repeats reads no text, like {@code (?=x)},
     * {@code \b} or an empty group. Text never runs out under it, so it runs in full at every place
     * of every message rather than only on one written against it: {@code (){32000}} would be
     * sixty-five million passes on any long message.
     */
    static final long ZERO_WIDTH_PASS = MAX_EXPRESSION_TEXT / 16;
    /** How a filter's expression is built: case set aside, in every script. */
    static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
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
        return firstMatch(filters, text) != null;
    }

    /** The first filter that matches the text, read the way {@link #matches} reads it, or null. */
    @Nullable
    static Filter firstMatch(List<Filter> filters, @Nullable String text) {
        if (text == null || text.isEmpty()) return null;
        String read = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        String lower = read.toLowerCase(Locale.ROOT);
        for (Filter filter : filters) {
            if (filter.pattern != null ? found(filter.pattern, read) : lower.contains(filter.words)) return filter;
        }
        return null;
    }

    /**
     * Which of {@code lines}, as {@link #lines} gives them, would hide a message saying {@code text},
     * counted from zero, or -1 for none. The list is read the way a chat reads it, so the first line
     * that matches wins and a line that can't be used is passed over without being run.
     */
    public static int firstHiding(List<String> lines, String text) {
        Filter hit = firstMatch(new Parsed(join(lines)).filters, text);
        return hit == null ? -1 : hit.line;
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
        /** Which of its list's lines it is, counted from zero, lines that can't be used included. */
        final int line;

        private Filter(@Nullable String words, @Nullable Pattern pattern, int line) {
            this.words = words;
            this.pattern = pattern;
            this.line = line;
        }
    }

    /** What's wrong with a line, so the editor can say. */
    public enum Problem {
        /** Longer than {@link #MAX_FILTER_CHARS}. */
        TOO_LONG,
        /** An expression that doesn't compile. */
        BROKEN,
        /**
         * An expression that can take too long on a long message, by the shapes {@link #slow}
         * refuses, or one its list has no tries left for.
         */
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

    /** What's wrong with one line on its own, or null when it can be used. */
    @Nullable
    public static Problem problem(String line) {
        return problem(line, new long[1]);
    }

    /**
     * What's wrong with one line of a list, or null when it can be used. {@code spent} holds the
     * tries of the list's lines before this one and takes this line's when it can be used, so a line
     * that would carry the list past {@link #MAX_LIST_TRIES} is slow as well.
     */
    @Nullable
    public static Problem problem(String line, long[] spent) {
        if (line.length() > MAX_FILTER_CHARS) return Problem.TOO_LONG;
        String expression = expression(line);
        if (expression == null) return null;
        try {
            Pattern.compile(expression);
        } catch (IllegalArgumentException broken) {
            // PatternSyntaxException, or the odd flag or length refusal, which is one too.
            return Problem.BROKEN;
        }
        long tries;
        try {
            tries = tries(expression, false);
            // The closer reading tries every character, so only a line the first one refuses gets it.
            if (tries > MAX_TRIES || spent[0] + tries > MAX_LIST_TRIES) tries = tries(expression, true);
        } catch (RuntimeException unread) {
            // An expression the check can't read through isn't one it can vouch for.
            return Problem.SLOW;
        }
        if (tries > MAX_TRIES || spent[0] + tries > MAX_LIST_TRIES) return Problem.SLOW;
        spent[0] += tries;
        return null;
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
     *
     * <p>A count is walked as many times as it says, once for each way of getting to it. Those
     * walks count as tries, so {@code a{50000}} and {@code .*\d{40}} are refused, and
     * {@code \d{40}.*}, which walks its count once, isn't. An assertion like {@code (?=x)} or
     * {@code \b}, or an empty group, reads no text that could run out first, so each of its passes
     * weighs {@link #ZERO_WIDTH_PASS}. An empty {@code \Q\E} is nothing, so it doesn't part a group
     * from the count after it. A repeat whose character can't be the one after it, like the spaces
     * in {@code \s+0x[a-f0-9]{40}}, can only stop in one place, so it isn't another way of getting
     * to that count. It still walks its run once for each way of getting to it.
     */
    static boolean slow(String expression) {
        return tries(expression, false) > MAX_TRIES && tries(expression, true) > MAX_TRIES;
    }

    /**
     * The ways an expression may try to match at one place plus the walks its counts make there, or
     * more than {@link #MAX_TRIES} for one of the shapes {@link #slow} refuses. With {@code exact},
     * a repeat whose character can't be the next one counts as one way rather than many.
     */
    private static long tries(String expression, boolean exact) {
        // For each open group: whether something inside it repeats or is optional, whether it holds a
        // choice, whether it looks behind, whether it reads text, and whether it only looks.
        List<boolean[]> open = new ArrayList<>();
        // For the whole expression and each open group: the tries of its choices read so far, added
        // up, of the choice being read, multiplied, where a plain group's text starts (-1 for
        // any other group), and where the group opens.
        List<long[]> cost = new ArrayList<>();
        cost.add(new long[]{0, 1, -1, -1});
        // A flag set inside the expression can change what a character matches, so the closer
        // reading leaves those alone.
        boolean closer = exact && !setsFlags(expression);
        boolean[] closedJustBefore = null;
        boolean afterRepeat = false;
        // How much of the expression a count right here would repeat: the group that just closed,
        // or one character, class or escape.
        long lengthJustBefore = 1;
        // Whether that reads no text, and the one character, class or escape it is, if it is one.
        boolean zeroWidthJustBefore = false;
        String atomJustBefore = null;
        // What the counts read so far walk at one place.
        long walks = 0;
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            boolean[] closed = null;
            boolean repeat = false;
            boolean zeroWidth = false;
            String atom = null;
            long length = 1;
            long[] current = cost.get(cost.size() - 1);
            if (c == '\\') {
                char next = i + 1 < n ? expression.charAt(i + 1) : 0;
                if (next >= '1' && next <= '9' || next == 'k') return REFUSED;
                int end = escapeEnd(expression, i);
                // ICU drops an empty \Q\E, and a lone \E, before it reads the rest, so a count after
                // one is on whatever came before it.
                if (next == 'E' || next == 'Q' && (end >= n ? i + 2 >= n : end == i + 4)) {
                    i = end;
                    continue;
                }
                if ("bBAzZG".indexOf(next) >= 0) {
                    zeroWidth = true;
                } else {
                    reads(open);
                    if ("QRX".indexOf(next) < 0) atom = expression.substring(i, end);
                }
                i = end;
            } else if (c == '[') {
                int end = classEnd(expression, i);
                reads(open);
                atom = expression.substring(i, end);
                i = end;
            } else if (c == '(') {
                // A comment, or a flag that turns spaces and # into something else, reads text this
                // scan can't follow.
                if (expression.startsWith("(?#", i) || commentsFlag(expression, i)) return REFUSED;
                boolean behind = expression.startsWith("(?<=", i) || expression.startsWith("(?<!", i);
                boolean looks = behind || expression.startsWith("(?=", i) || expression.startsWith("(?!", i);
                open.add(new boolean[]{false, false, behind, false, looks});
                long start = expression.startsWith("(?:", i) ? i + 3 : expression.startsWith("(?", i) ? -1 : i + 1;
                cost.add(new long[]{0, 1, start, i});
                // Past (?: (?= (?<name> (?i: and the like, which say how the group works. Read one
                // character at a time, their : or = counted as text the group reads.
                i = expression.startsWith("(?", i) ? groupBody(expression, i) : i + 1;
            } else if (c == ')') {
                if (!open.isEmpty()) {
                    closed = open.remove(open.size() - 1);
                    if (!open.isEmpty()) {
                        boolean[] outer = open.get(open.size() - 1);
                        outer[0] |= closed[0];
                        outer[1] |= closed[1];
                        // A look puts back what it read, so the group around it hasn't read it.
                        if (!closed[4]) outer[3] |= closed[3];
                    }
                    zeroWidth = closed[4] || !closed[3];
                }
                if (cost.size() > 1) {
                    long[] group = cost.remove(cost.size() - 1);
                    long[] outer = cost.get(cost.size() - 1);
                    // Plain words no one of which starts another can't both match at one place, so
                    // a list of them is tried once.
                    long tries = group[2] >= 0 && distinctWords(expression.substring((int) group[2], i)) ? 1 : group[0] + group[1];
                    outer[1] = capped(outer[1] * capped(tries));
                    if (outer[1] > MAX_TRIES) return REFUSED;
                    length = Math.max(1, i - group[3] - 1);
                }
                i++;
            } else if (c == '|') {
                if (!open.isEmpty()) open.get(open.size() - 1)[1] = true;
                // Choices are tried one after another, so their tries add up.
                current[0] = capped(current[0] + current[1]);
                current[1] = 1;
                if (current[0] > MAX_TRIES) return REFUSED;
                i++;
            } else if ((c == '?' || c == '+') && afterRepeat) {
                // Lazy or possessive: the same repeat, not another one.
                i++;
            } else if (c == '*' || c == '+' || c == '?' || c == '{' && repeats(expression, i)) {
                long[] count = c == '{' ? bounds(expression, i) : null;
                // An optional group is tried once or not at all, so only a repeated one is refused here.
                boolean optional = c == '?' || count != null && count[1] == 1;
                if (!optional && closedJustBefore != null && (closedJustBefore[0] || closedJustBefore[1])) return REFUSED;
                // A look behind is tried from every place its text could start, which a repeat of
                // more than one length multiplies.
                if ((count == null || count[0] != count[1]) && behind(open)) return REFUSED;
                // A count walks what it's on as many times as it says, text or no text to stop it,
                // and again for each way of getting this far. A spread is tried from its largest
                // number down, and one with no end has to reach its least.
                if (count != null && !optional) {
                    long ways = 1;
                    for (long[] group : cost) ways = capped(ways * group[1]);
                    long pass = zeroWidthJustBefore ? Math.max(lengthJustBefore, ZERO_WIDTH_PASS) : lengthJustBefore;
                    walks = capped(walks + ways * capped(count[1] < 0 ? count[0] : count[1]) * pass);
                    if (walks > MAX_TRIES) return REFUSED;
                }
                int after = c == '{' ? expression.indexOf('}', i) + 1 : i + 1;
                long ways = c == '?' ? 2 : c == '{' ? spread(expression, i) : MAX_EXPRESSION_TEXT;
                // A repeat of a character that can't be the next one stops in one place only: any
                // shorter run leaves one of its own characters where the next one has to match. It
                // still walks its run once for each way of getting to it, so \w+\s*\w+! is refused.
                if (closer && c != '?' && ways > 1 && atomJustBefore != null && stopsBefore(expression, after, atomJustBefore)) {
                    long reached = 1;
                    for (long[] group : cost) reached = capped(reached * group[1]);
                    walks = capped(walks + reached * ways);
                    if (walks > MAX_TRIES) return REFUSED;
                    ways = 1;
                }
                current[1] = capped(current[1] * ways);
                if (current[0] + current[1] > MAX_TRIES) return REFUSED;
                if (!open.isEmpty()) open.get(open.size() - 1)[0] = true;
                repeat = true;
                i = after;
            } else {
                if (c == '^' || c == '$') {
                    zeroWidth = true;
                } else {
                    reads(open);
                    if (!Character.isSurrogate(c)) atom = String.valueOf(c);
                }
                i++;
            }
            closedJustBefore = closed;
            afterRepeat = repeat;
            lengthJustBefore = length;
            zeroWidthJustBefore = zeroWidth;
            atomJustBefore = atom;
        }
        long[] whole = cost.get(0);
        return capped(whole[0] + whole[1] + walks);
    }

    /**
     * Where the text of a group opened at {@code at} with {@code (?} starts: past {@code (?:},
     * {@code (?=}, {@code (?!}, {@code (?>}, {@code (?<=}, {@code (?<!}, {@code (?<name>}, or flags up
     * to the colon that opens their text. Flags alone, like {@code (?i)}, end at their bracket.
     */
    private static int groupBody(String expression, int at) {
        int n = expression.length();
        int k = at + 2;
        if (k >= n) return n;
        char c = expression.charAt(k);
        if (c == ':' || c == '=' || c == '!' || c == '>') return k + 1;
        if (c == '<') {
            if (k + 1 < n && (expression.charAt(k + 1) == '=' || expression.charAt(k + 1) == '!')) return k + 2;
            int close = expression.indexOf('>', k);
            return close < 0 ? n : close + 1;
        }
        while (k < n && expression.charAt(k) != ':' && expression.charAt(k) != ')') k++;
        return k < n && expression.charAt(k) == ':' ? k + 1 : k;
    }

    /** The innermost open group reads text. */
    private static void reads(List<boolean[]> open) {
        if (!open.isEmpty()) open.get(open.size() - 1)[3] = true;
    }

    /** Whether a group sets flags anywhere, like {@code (?i)} or {@code (?u:...)}. */
    private static boolean setsFlags(String expression) {
        for (int at = expression.indexOf("(?"); at >= 0; at = expression.indexOf("(?", at + 1)) {
            if (at + 2 < expression.length()) {
                char c = expression.charAt(at + 2);
                if (Character.isLetter(c) || c == '-') return true;
            }
        }
        return false;
    }

    /**
     * Whether a repeat of {@code atom} that ends at {@code at} can only stop in one place: what
     * comes next is one character, class or escape that has to match, and no character is both.
     */
    private static boolean stopsBefore(String expression, int at, String atom) {
        int n = expression.length();
        // Lazy or possessive.
        if (at < n && (expression.charAt(at) == '?' || expression.charAt(at) == '+')) at++;
        if (at >= n) return false;
        char c = expression.charAt(at);
        int end;
        if (c == '\\') {
            char next = at + 1 < n ? expression.charAt(at + 1) : 0;
            if ("QEbBAzZGRXk123456789".indexOf(next) >= 0) return false;
            end = escapeEnd(expression, at);
        } else if (c == '[') {
            end = classEnd(expression, at);
        } else if ("()|^$*+?{}".indexOf(c) >= 0 || Character.isSurrogate(c)) {
            return false;
        } else {
            end = at + 1;
        }
        // What comes next has to match at least once.
        if (end < n) {
            char after = expression.charAt(end);
            if (after == '?' || after == '*') return false;
            if (after == '{') {
                long[] count = bounds(expression, end);
                if (count != null && count[0] == 0) return false;
            }
        }
        return neverBoth(atom, expression.substring(at, end));
    }

    /** Code points past the first plane that a class could take in without naming them. */
    private static final int[] PAST_FIRST_PLANE = {0x10000, 0x1D400, 0x1F3FB, 0x1F600, 0x20000, 0xE0041, 0x10FFFD};

    /**
     * How many pairs {@link #NEVER_BOTH} keeps: more than a full list's repeats. Both atoms come from
     * one line, so a key is no longer than a line, and the whole map about 100 KB at worst.
     */
    static final int NEVER_BOTH_KEPT = 256;

    /**
     * Answers {@link #neverBoth} already worked out, by the two atoms, the least recently asked
     * dropped first. A list of wallet lines asks the same pair a hundred times, and a list is read
     * again on each change, so each pair is read once.
     */
    private static final Map<String, Boolean> NEVER_BOTH = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > NEVER_BOTH_KEPT;
                }
            });

    /**
     * Whether no character matches both {@code first} and {@code second}, each one character,
     * class or escape, the way a filter matches them. Every character of the first plane is tried,
     * and either one taking in a character past it counts as sharing one. A character named by its
     * number, or one whose case folds to more than one letter, like ß to ss, isn't read this way:
     * ignoring case, ß matches the two letters ss, so a single character can't stand for it.
     */
    private static boolean neverBoth(String first, String second) {
        for (String atom : new String[]{first, second}) {
            for (int k = 0; k < atom.length(); k++) {
                if (Character.isSurrogate(atom.charAt(k)) || foldsToMore(atom.charAt(k))) return false;
            }
            if (atom.contains("\\x") || atom.contains("\\u") || atom.contains("\\U") || atom.contains("\\0")
                    || atom.contains("\\p") || atom.contains("\\P") || atom.contains("\\N") || atom.contains("&&")
                    || atom.contains("--") || atom.indexOf('[', 1) >= 0) {
                return false;
            }
        }
        String key = first.length() + ":" + first + second;
        Boolean known = NEVER_BOTH.get(key);
        if (known != null) return known;
        PAIRS_READ.incrementAndGet();
        boolean answer;
        try {
            BitSet one = matched(Pattern.compile(first, FLAGS));
            BitSet other = one == null ? null : matched(Pattern.compile(second, FLAGS));
            answer = other != null && !one.intersects(other);
        } catch (RuntimeException unread) {
            answer = false;
        }
        NEVER_BOTH.put(key, answer);
        return answer;
    }

    /** How many times {@link #neverBoth} worked a pair out rather than answering from what it kept. */
    private static final AtomicInteger PAIRS_READ = new AtomicInteger();

    /** How many pairs {@link #neverBoth} has kept answers for. */
    static int pairsKnown() {
        return NEVER_BOTH.size();
    }

    /** How many pairs {@link #neverBoth} has worked out since the last {@link #forgetPairs}. */
    static int pairsRead() {
        return PAIRS_READ.get();
    }

    /** Forgets every kept pair, so a test starts from none. */
    static void forgetPairs() {
        NEVER_BOTH.clear();
        PAIRS_READ.set(0);
    }

    /**
     * The first-plane characters {@code atom} matches on its own, or null when it takes in one past
     * that plane or matches anything but a single character. One find over every character reads
     * them all, where matching each alone took tens of thousands of calls into Android's regex.
     */
    @Nullable
    private static BitSet matched(Pattern atom) {
        for (int codePoint : PAST_FIRST_PLANE) {
            if (atom.matcher(new String(Character.toChars(codePoint))).matches()) return null;
        }
        StringBuilder plane = new StringBuilder(0x10000);
        for (int k = 0; k <= 0xFFFF; k++) {
            if (k < Character.MIN_SURROGATE || k > Character.MAX_SURROGATE) plane.append((char) k);
        }
        BitSet chars = new BitSet(0x10000);
        Matcher each = atom.matcher(plane);
        while (each.find()) {
            if (each.end() != each.start() + 1) return null;
            chars.set(plane.charAt(each.start()));
        }
        return chars;
    }

    /**
     * Whether a character's case folds to more than one, like ß to SS or İ to i and a dot. Capital
     * ẞ is one letter upper and lower, but its lower ß is two upper, so the round trip is tried too.
     */
    private static boolean foldsToMore(char c) {
        String one = String.valueOf(c);
        String lower = one.toLowerCase(Locale.ROOT);
        return one.toUpperCase(Locale.ROOT).length() > 1 || lower.length() > 1
                || lower.toUpperCase(Locale.ROOT).length() > 1;
    }

    /** What {@link #tries} answers for an expression it refuses outright. */
    private static final long REFUSED = MAX_TRIES + 1;

    /** Tries held just past {@link #MAX_TRIES}, so multiplying two never overflows. */
    private static long capped(long tries) {
        return Math.min(tries, REFUSED);
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
        // A backslash then x, u, U or 0 names one character with the 2, 4, 8 or 3 digits after it.
        if (next == 'x') return digitsEnd(expression, at + 2, 2, 16);
        if (next == 'u') return digitsEnd(expression, at + 2, 4, 16);
        if (next == 'U') return digitsEnd(expression, at + 2, 8, 16);
        if (next == '0') return digitsEnd(expression, at + 2, 3, 8);
        return at + 2;
    }

    /** Where a run of at most {@code most} digits in {@code radix} that starts at {@code from} ends. */
    private static int digitsEnd(String expression, int from, int most, int radix) {
        int end = from;
        while (end < expression.length() && end - from < most && Character.digit(expression.charAt(end), radix) >= 0) end++;
        return end;
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
            long[] spent = new long[1];
            List<String> lines = lines(source);
            for (int index = 0; index < lines.size(); index++) {
                if (filters.size() == MAX_FILTERS) break;
                String line = lines.get(index);
                // A line that can't be used, from a file edited by hand or a settings file brought
                // in, is left out, so its expression is never built or run.
                if (problem(line, spent) != null) continue;
                String expression = expression(line);
                filters.add(expression == null
                        ? new Filter(line.toLowerCase(Locale.ROOT), null, index)
                        : new Filter(null, Pattern.compile(expression, FLAGS), index));
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
