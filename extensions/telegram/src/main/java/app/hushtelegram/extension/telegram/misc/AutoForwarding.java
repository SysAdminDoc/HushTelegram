/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Auto-forwarding rules. A new message someone else sends in a rule's first chat is forwarded to
 * its second chat on the same account, the way Forward sends it, and a rule can take only photos,
 * videos and files. Your own messages stay, scheduled ones included. A forward one of your signed-in
 * accounts sent, or one that came from the destination, isn't forwarded, so rules pointing at each
 * other don't loop, on one account or across two. A message that comes in twice goes out once, and
 * each account sends at most {@link #PER_MINUTE} forwards a minute. Notifications built from a push
 * aren't forwarded: Telegram hands the same messages over again once it connects. Telegram hands
 * over only unread messages, so one already read on another device before this one connects stays.
 */
public final class AutoForwarding {
    /** The most forwards one account sends in a minute. Past it, new matches aren't forwarded. */
    static final int PER_MINUTE = 20;
    /** How many forwarded messages are remembered, so one that comes in twice goes out once. */
    private static final int REMEMBERED = 512;
    private static final long MINUTE = 60_000L;

    static Telegram telegram = new Stubs();

    private static final Map<String, Boolean> forwarded = new LinkedHashMap<String, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED;
        }
    };
    private static final Map<Integer, ArrayDeque<Long>> sentAt = new HashMap<>();

    private AutoForwarding() {
    }

    /** On the account signed in as [user], new messages in [source] go to [destination]. */
    public static final class Rule {
        public final long user;
        public final long source;
        public final long destination;
        /** Only messages with a photo, video, file, voice message or sticker. */
        public final boolean mediaOnly;

        public Rule(long user, long source, long destination, boolean mediaOnly) {
            this.user = user;
            this.source = source;
            this.destination = destination;
            this.mediaOnly = mediaOnly;
        }

        String line() {
            return user + " " + source + " " + destination + " " + (mediaOnly ? 1 : 0);
        }

        /** The same pair of chats on the same account, whatever it takes. */
        public boolean samePair(Rule other) {
            return user == other.user && source == other.source && destination == other.destination;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Rule && samePair((Rule) other) && mediaOnly == ((Rule) other).mediaOnly;
        }

        @Override
        public int hashCode() {
            return Long.valueOf(user).hashCode() * 31 * 31 + Long.valueOf(source).hashCode() * 31
                    + Long.valueOf(destination).hashCode() + (mediaOnly ? 1 : 0);
        }
    }

    /** A chat the rules page lists: Telegram's dialog id for it, its name and whether you can post there. */
    public static final class Chat {
        public final long id;
        public final String name;
        public final boolean canPost;

        public Chat(long id, String name, boolean canPost) {
            this.id = id;
            this.name = name;
            this.canPost = canPost;
        }
    }

    /** The account shown now: who it's signed in as, its chats and its rules. */
    public static final class Shown {
        public final long user;
        public final List<Chat> chats;
        public final List<Rule> rules;

        public Shown(long user, List<Chat> chats, List<Rule> rules) {
            this.user = user;
            this.chats = chats;
            this.rules = rules;
        }

        /** The chat's name, or its id when it isn't on the list any more. */
        public String name(long id) {
            for (Chat chat : chats) {
                if (chat.id == id) return chat.name;
            }
            return String.valueOf(id);
        }

        /** The chats a rule can watch. Not Saved Messages: Telegram never hands over what's posted there. */
        public List<Chat> sources() {
            List<Chat> sources = new ArrayList<>();
            for (Chat chat : chats) {
                if (chat.id != user) sources.add(chat);
            }
            return sources;
        }

        /** The chats [source]'s messages can go to: any you can post in but [source]. */
        public List<Chat> destinations(long source) {
            List<Chat> destinations = new ArrayList<>();
            for (Chat chat : chats) {
                if (chat.id != source && chat.canPost) destinations.add(chat);
            }
            return destinations;
        }
    }

    /** The saved rules, one a line: user, source, destination, then 1 for media only. Lines that don't read are left out. */
    static List<Rule> parse(@Nullable String saved) {
        List<Rule> rules = new ArrayList<>();
        if (saved == null) return rules;
        for (String line : saved.split("\n")) {
            String[] parts = line.trim().split(" ");
            if (parts.length != 4) continue;
            try {
                long user = Long.parseLong(parts[0]);
                long source = Long.parseLong(parts[1]);
                long destination = Long.parseLong(parts[2]);
                if (user == 0 || source == 0 || destination == 0 || source == destination) continue;
                if (!parts[3].equals("0") && !parts[3].equals("1")) continue;
                Rule rule = new Rule(user, source, destination, parts[3].equals("1"));
                if (!holdsPair(rules, rule)) rules.add(rule);
            } catch (NumberFormatException unreadable) {
                // Left out, like any other line that doesn't read.
            }
        }
        return rules;
    }

    static String format(List<Rule> rules) {
        StringBuilder text = new StringBuilder();
        for (Rule rule : rules) {
            if (text.length() > 0) text.append('\n');
            text.append(rule.line());
        }
        return text.toString();
    }

    private static boolean holdsPair(List<Rule> rules, Rule rule) {
        for (Rule held : rules) {
            if (held.samePair(rule)) return true;
        }
        return false;
    }

    /** The saved rules. Read as saved even while HushTelegram is paused, so a rule added then keeps the rest. */
    public static List<Rule> rules() {
        return parse(Settings.AUTO_FORWARDING_RULES.savedValue());
    }

    /**
     * Saves [rule] after the others. A rule back into its own chat, or for a pair of chats already
     * on the list, isn't saved. The answer is whether the rule was saved.
     */
    public static synchronized boolean add(Rule rule) {
        if (rule.user == 0 || rule.source == 0 || rule.destination == 0 || rule.source == rule.destination) return false;
        List<Rule> rules = rules();
        if (holdsPair(rules, rule)) return false;
        rules.add(rule);
        return Settings.AUTO_FORWARDING_RULES.save(format(rules));
    }

    /** Takes [rule] off the list. The answer is whether it's off the saved list now. */
    public static synchronized boolean remove(Rule rule) {
        List<Rule> rules = rules();
        return !rules.remove(rule) || Settings.AUTO_FORWARDING_RULES.save(format(rules));
    }

    /**
     * The account shown now, with its chats (archived ones too, secret chats and the Archived Chats
     * entry left out) and its rules, or null when they can't be read.
     */
    @Nullable
    public static Shown shown() {
        try {
            int account = telegram.selectedAccount();
            long user = telegram.userId(account);
            List<Rule> mine = new ArrayList<>();
            for (Rule rule : rules()) {
                if (rule.user == user) mine.add(rule);
            }
            Set<Long> ids = new LinkedHashSet<>();
            for (int folder = 0; folder <= 1; folder++) {
                List<?> dialogs = telegram.dialogs(account, folder);
                if (dialogs == null) continue;
                for (Object dialog : dialogs) {
                    if (dialog != null) ids.add(telegram.dialogId(dialog));
                }
            }
            List<Chat> chats = new ArrayList<>();
            for (long id : ids) {
                if (id == 0 || telegram.folder(id) || telegram.secret(id)) continue;
                // Your own chat is Saved Messages, as Telegram lists it, not your name.
                String name = id == user ? L10n.t("Saved Messages") : telegram.peerName(account, id);
                chats.add(new Chat(id, name == null || name.trim().isEmpty() ? String.valueOf(id) : name,
                        telegram.canPost(account, id)));
            }
            return new Shown(user, Collections.unmodifiableList(chats), Collections.unmodifiableList(mine));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AUTO_FORWARDING, "chat list for rules", failure);
            return null;
        }
    }

    /**
     * NotificationsController.processNewMessages(messages, isLast, isFcm, latch) hands over each
     * batch of new messages before it decides on notifications. [push] is isFcm, non-zero for yes.
     */
    public static void onNewMessages(Object messages, int push) {
        try {
            if (!Utils.settingsReady() || !Settings.AUTO_FORWARDING.get()) return;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.AUTO_FORWARDING, "switch read", t);
            return;
        }
        HookStatus.counted(FamilyNames.AUTO_FORWARDING, "messages checked for forwarding");
        if (push != 0 || !(messages instanceof List)) return;
        try {
            List<Rule> rules = rules();
            if (rules.isEmpty()) return;
            Map<String, Batch> batches = matches(new ArrayList<>((List<?>) messages), rules);
            if (batches.isEmpty()) return;
            Utils.runOnMainThreadNowOrLater(() -> {
                for (Batch batch : batches.values()) send(batch);
            });
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AUTO_FORWARDING, "rules", failure);
        }
    }

    /** One account's messages for one destination, in the order they came. */
    static final class Batch {
        final int account;
        final long destination;
        final ArrayList<Object> messages = new ArrayList<>();

        Batch(int account, long destination) {
            this.account = account;
            this.destination = destination;
        }
    }

    /** The messages each destination gets, and each one remembered as forwarded. */
    static synchronized Map<String, Batch> matches(List<?> messages, List<Rule> rules) {
        Map<String, Batch> batches = new LinkedHashMap<>();
        Map<Integer, Long> users = new HashMap<>();
        // Every account a rule is for: a forward one of them sent is a rule's, on whichever account it lands.
        Set<Long> owners = new HashSet<>();
        for (Rule rule : rules) owners.add(rule.user);
        for (Object message : messages) {
            if (message == null) continue;
            int account = telegram.account(message);
            Long user = users.get(account);
            if (user == null) {
                user = telegram.userId(account);
                users.put(account, user);
            }
            long dialog = telegram.dialog(message);
            int id = telegram.id(message);
            for (Rule rule : rules) {
                if (rule.user != user || rule.source != dialog || id <= 0) continue;
                // Telegram hands over your own messages only when a scheduled one goes out, so none go.
                if (telegram.out(message)) continue;
                // A rule's forward landing on another account you're signed in to, or a channel's post
                // its discussion group got, would go back where it came from and round again.
                if (telegram.forwarded(message)
                        && (owners.contains(telegram.sender(message)) || telegram.origin(message) == rule.destination)) continue;
                if (telegram.service(message)) continue;
                if (rule.mediaOnly && !telegram.media(message)) continue;
                String key = account + ":" + dialog + ":" + id + ":" + rule.destination;
                if (forwarded.containsKey(key)) continue;
                if (!allowed(account)) {
                    HookStatus.counted(FamilyNames.AUTO_FORWARDING, "forwards over the per-minute limit");
                    continue;
                }
                forwarded.put(key, Boolean.TRUE);
                String to = account + ":" + rule.destination;
                Batch batch = batches.get(to);
                if (batch == null) {
                    batch = new Batch(account, rule.destination);
                    batches.put(to, batch);
                }
                batch.messages.add(message);
            }
        }
        return batches;
    }

    /** Whether [account] may send one more forward this minute, and if so, counts it. */
    private static boolean allowed(int account) {
        long now = telegram.now();
        ArrayDeque<Long> times = sentAt.get(account);
        if (times == null) {
            times = new ArrayDeque<>();
            sentAt.put(account, times);
        }
        while (!times.isEmpty() && now - times.peekFirst() >= MINUTE) times.pollFirst();
        if (times.size() >= PER_MINUTE) return false;
        times.addLast(now);
        return true;
    }

    private static void send(Batch batch) {
        try {
            int result = telegram.forward(batch.account, batch.messages, batch.destination);
            HookStatus.counted(FamilyNames.AUTO_FORWARDING, result == 0 ? "messages forwarded" : "forwards Telegram refused");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AUTO_FORWARDING, "forward", failure);
        }
    }

    /** Forgets what was forwarded and when, for tests. */
    static synchronized void reset() {
        forwarded.clear();
        sentAt.clear();
    }

    /** What this needs from Telegram. */
    interface Telegram {
        int account(Object message);

        long dialog(Object message);

        int id(Object message);

        boolean out(Object message);

        boolean forwarded(Object message);

        long sender(Object message);

        long origin(Object message);

        boolean service(Object message);

        boolean media(Object message);

        int selectedAccount();

        long userId(int account);

        @Nullable
        List<?> dialogs(int account, int folder);

        long dialogId(Object dialog);

        boolean secret(long dialog);

        boolean folder(long dialog);

        boolean canPost(int account, long id);

        @Nullable
        String peerName(int account, long id);

        int forward(int account, ArrayList<Object> messages, long destination);

        long now();
    }

    private static final class Stubs implements Telegram {
        @Override public int account(Object message) { return AutoForwarding.account(message); }
        @Override public long dialog(Object message) { return AutoForwarding.dialog(message); }
        @Override public int id(Object message) { return AutoForwarding.id(message); }
        @Override public boolean out(Object message) { return AutoForwarding.out(message); }
        @Override public boolean forwarded(Object message) { return AutoForwarding.forwarded(message); }
        @Override public long sender(Object message) { return AutoForwarding.sender(message); }
        @Override public long origin(Object message) { return AutoForwarding.origin(message); }
        @Override public boolean service(Object message) { return AutoForwarding.service(message); }
        @Override public boolean media(Object message) { return AutoForwarding.media(message); }
        @Override public int selectedAccount() { return AutoForwarding.selectedAccount(); }
        @Override public long userId(int account) { return AutoForwarding.userId(account); }
        @Override public List<?> dialogs(int account, int folder) { return AutoForwarding.dialogs(account, folder); }
        @Override public long dialogId(Object dialog) { return AutoForwarding.dialogId(dialog); }
        @Override public boolean secret(long dialog) { return AutoForwarding.secret(dialog); }
        @Override public boolean folder(long dialog) { return AutoForwarding.folder(dialog); }
        @Override public boolean canPost(int account, long id) { return AutoForwarding.canPost(account, id); }
        @Override public String peerName(int account, long id) { return AutoForwarding.peerName(account, id); }
        @Override public int forward(int account, ArrayList<Object> messages, long destination) {
            return AutoForwarding.forward(account, messages, destination);
        }
        @Override public long now() { return SystemClock.elapsedRealtime(); }
    }

    // Stubs. The patch gives each one Telegram's code; until then they do nothing.

    /** {@code message.currentAccount}. */
    public static int account(Object message) { return 0; }

    /** {@code message.getDialogId()}. */
    public static long dialog(Object message) { return 0L; }

    /** {@code message.getId()}. */
    public static int id(Object message) { return 0; }

    /** {@code message.isOut()}. */
    public static boolean out(Object message) { return false; }

    /** {@code message.isForwarded()}. */
    public static boolean forwarded(Object message) { return false; }

    /** {@code message.getFromChatId()}: who sent it, a user or a chat as a dialog id. */
    public static long sender(Object message) { return 0L; }

    /** The dialog id the forward header names as where it came from, or 0 when it names none. */
    public static long origin(Object message) { return 0L; }

    /** Whether the message is a service message: an action other than TL_messageActionEmpty. */
    public static boolean service(Object message) { return false; }

    /** {@code !message.isMediaEmpty()}: a photo, video, file, voice message or sticker, not a link preview. */
    public static boolean media(Object message) { return false; }

    /** {@code UserConfig.selectedAccount}. */
    public static int selectedAccount() { return 0; }

    /** {@code UserConfig.getInstance(account).getClientUserId()}. */
    public static long userId(int account) { return 0L; }

    /** {@code MessagesController.getInstance(account).getDialogs(folder)}. */
    @Nullable
    public static ArrayList<?> dialogs(int account, int folder) { return null; }

    /** {@code dialog.id}, for a TLRPC.Dialog. */
    public static long dialogId(Object dialog) { return 0L; }

    /** {@code DialogObject.isEncryptedDialog(dialog)}. */
    public static boolean secret(long dialog) { return false; }

    /** {@code DialogObject.isFolderDialogId(dialog)}: the Archived Chats entry, not a chat. */
    public static boolean folder(long dialog) { return false; }

    /**
     * Whether you can post in the chat: a user's chat always, a group or channel when you're in it
     * and {@code ChatObject.canSendMessages} says so.
     */
    public static boolean canPost(int account, long id) { return true; }

    /** {@code MessagesController.getInstance(account).getPeerName(id)}. */
    @Nullable
    public static String peerName(int account, long id) { return null; }

    /**
     * {@code SendMessagesHelper.getInstance(account).sendMessage(messages, destination, ...)}, the
     * send Forward makes, with the sender shown and notifying. 0 when Telegram took it.
     */
    public static int forward(int account, ArrayList<Object> messages, long destination) { return 1; }
}
