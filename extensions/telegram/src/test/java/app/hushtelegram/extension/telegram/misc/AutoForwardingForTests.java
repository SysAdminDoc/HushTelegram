/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushtelegram.extension.telegram.settings.Settings;

/** Lets tests give auto-forwarding a Telegram, and see what it sends. */
public final class AutoForwardingForTests {
    private AutoForwardingForTests() {}

    private static AutoForwarding.Telegram saved;

    /** One new message, as the stubs read it. */
    public static final class Message {
        public final int account;
        public final long dialog;
        public final int id;
        public boolean out;
        public boolean forwarded;
        /** Who sent it, as a dialog id. */
        public long sender;
        /** Where a forward came from, as a dialog id, or 0. */
        public long origin;
        public boolean service;
        public boolean media;

        public Message(int account, long dialog, int id) {
            this.account = account;
            this.dialog = dialog;
            this.id = id;
        }

        @Override public String toString() {
            return dialog + "/" + id;
        }
    }

    /** Telegram with each account's user and chats, a clock to move and every forward it was asked for. */
    public static class Fake implements AutoForwarding.Telegram {
        public int selected = 0;
        public final Map<Integer, Long> users = new HashMap<>();
        /** Each account's chat list (folder 0) and archive (folder 1), as dialog ids. */
        public final Map<String, List<Long>> chats = new HashMap<>();
        public final Map<Long, String> names = new HashMap<>();
        public final List<Long> secret = new ArrayList<>();
        public final List<Long> folders = new ArrayList<>();
        /** Chats you can't post in. */
        public final List<Long> readOnly = new ArrayList<>();
        /** Each forward as "account: messages to destination". */
        public final List<String> sent = new ArrayList<>();
        public long now = 1_000_000L;
        public int answer = 0;

        public Fake chats(int account, int folder, Long... ids) {
            List<Long> list = new ArrayList<>();
            for (Long id : ids) list.add(id);
            chats.put(account + "/" + folder, list);
            return this;
        }

        @Override public int account(Object message) { return ((Message) message).account; }
        @Override public long dialog(Object message) { return ((Message) message).dialog; }
        @Override public int id(Object message) { return ((Message) message).id; }
        @Override public boolean out(Object message) { return ((Message) message).out; }
        @Override public boolean forwarded(Object message) { return ((Message) message).forwarded; }
        @Override public long sender(Object message) { return ((Message) message).sender; }
        @Override public long origin(Object message) { return ((Message) message).origin; }
        @Override public boolean service(Object message) { return ((Message) message).service; }
        @Override public boolean media(Object message) { return ((Message) message).media; }
        @Override public int selectedAccount() { return selected; }
        @Override public long userId(int account) { return users.getOrDefault(account, 0L); }
        @Override public List<?> dialogs(int account, int folder) { return chats.get(account + "/" + folder); }
        @Override public long dialogId(Object dialog) { return (Long) dialog; }
        @Override public boolean secret(long dialog) { return secret.contains(dialog); }
        @Override public boolean folder(long dialog) { return folders.contains(dialog); }
        @Override public boolean canPost(int account, long id) { return !readOnly.contains(id); }
        @Override public String peerName(int account, long id) { return names.get(id); }
        @Override public long now() { return now; }

        @Override public int forward(int account, ArrayList<Object> messages, long destination) {
            sent.add(account + ": " + messages + " to " + destination);
            return answer;
        }
    }

    /** Gives auto-forwarding [telegram]. {@link #restore} undoes it. */
    public static Fake use(Fake telegram) {
        if (saved == null) saved = AutoForwarding.telegram;
        AutoForwarding.telegram = telegram;
        AutoForwarding.reset();
        return telegram;
    }

    /** Back to the stubs, with no rules and nothing remembered. */
    public static void restore() {
        if (saved != null) AutoForwarding.telegram = saved;
        saved = null;
        AutoForwarding.reset();
        Settings.AUTO_FORWARDING_RULES.resetToDefault();
    }
}
