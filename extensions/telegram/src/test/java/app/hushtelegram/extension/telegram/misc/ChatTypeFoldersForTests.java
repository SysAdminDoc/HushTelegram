/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.telegram.settings.PatchFamily;
import app.hushtelegram.extension.telegram.settings.PatchFamilyForTests;
import app.hushtelegram.extension.telegram.settings.Settings;
import org.robolectric.RuntimeEnvironment;

/** Lets tests outside this package ask the folder switch, and gives them a Telegram that keeps what it's asked. */
public final class ChatTypeFoldersForTests {
    private ChatTypeFoldersForTests() {}

    /**
     * One account's folders, as Telegram's list holds them, every request sent, and a server that
     * answers each one on the main thread the way Telegram's callback does.
     */
    static class Recorder implements ChatTypeFolders.Telegram {
        int account = 0;
        long user = 1000;
        boolean signedIn = true;
        boolean loaded = true;
        int limit = 10;
        final List<ChatTypeFolders.Folder> folders = new ArrayList<>();
        /** Each new folder asked for, as account/id/name/flags. Telegram's list gets it once the server says yes. */
        final List<String> created = new ArrayList<>();
        /** Each folder id asked to be deleted. */
        final List<Integer> deleted = new ArrayList<>();
        /** Each order sent, as the folder ids in the list at the time. */
        final List<List<Integer>> orders = new ArrayList<>();
        int fetches;
        /** Offline: every answer waits for {@link #reconnect}, as Telegram's requests wait for a connection. */
        boolean offline;
        final List<Runnable> held = new ArrayList<>();
        /** What the server says to a create or delete for each folder id when it says no, read when it answers. */
        final Map<Integer, String> refuse = new HashMap<>();
        /** What it says to the order when it says no. */
        String refuseOrder;
        /** The tag color the server gives each new folder, by id. A folder not here isn't in its list. */
        final Map<Integer, Integer> serverColors = new HashMap<>();

        /** A folder Telegram's list already has, with nothing set on it but its name and chat types. */
        Recorder with(int id, int flags, String name) {
            return with(id, flags, name, 0);
        }

        /** {@code changes} as {@link ChatTypeFolders#COLOR} and the rest. */
        Recorder with(int id, int flags, String name, int changes) {
            folders.add(new ChatTypeFolders.Folder(id, flags, name, changes));
            return this;
        }

        /** Swaps a listed folder for one with these details, as an edit in Telegram's folder screen would. */
        void edit(int id, int flags, String name, int changes) {
            folders.removeIf(folder -> folder.id == id);
            with(id, flags, name, changes);
        }

        /** A listed folder's color, as Premium's folder screen sets one, or as the server's list brings one in. */
        void recolor(int id, int color) {
            for (int i = 0; i < folders.size(); i++) {
                ChatTypeFolders.Folder old = folders.get(i);
                if (old.id != id) continue;
                int changes = color >= 0 ? old.changes | ChatTypeFolders.COLOR : old.changes & ~ChatTypeFolders.COLOR;
                folders.set(i, new ChatTypeFolders.Folder(id, old.flags, old.name, changes, color));
            }
        }

        ChatTypeFolders.Folder folder(int id) {
            for (ChatTypeFolders.Folder folder : folders) {
                if (folder.id == id) return folder;
            }
            return null;
        }

        @Override public int account() { return account; }
        @Override public boolean signedIn(int account) { return signedIn; }
        @Override public long user(int account) { return user; }
        @Override public List<ChatTypeFolders.Folder> folders(int account) { return loaded ? new ArrayList<>(folders) : null; }
        @Override public int limit(int account) { return limit; }

        @Override public void create(int account, int id, String name, int flags, ChatTypeFolders.Reply reply) {
            created.add(account + "/" + id + "/" + name + "/" + flags);
            answer(reply, () -> refuse.get(id), () -> Boolean.TRUE);
        }

        @Override public void add(int account, int id, String name, int flags) {
            folders.add(new ChatTypeFolders.Folder(id, flags, name, 0));
        }

        @Override public void delete(int account, int id, ChatTypeFolders.Reply reply) {
            deleted.add(id);
            answer(reply, () -> refuse.get(id), () -> Boolean.TRUE);
        }

        @Override public void drop(int account, int id) {
            folders.removeIf(folder -> folder.id == id);
        }

        @Override public void order(int account, ChatTypeFolders.Reply reply) {
            List<Integer> ids = new ArrayList<>();
            for (ChatTypeFolders.Folder folder : folders) ids.add(folder.id);
            orders.add(ids);
            answer(reply, () -> refuseOrder, () -> Boolean.TRUE);
        }

        @Override public void fetch(int account, ChatTypeFolders.Reply reply) {
            fetches++;
            answer(reply, () -> null, () -> new HashMap<>(serverColors));
        }

        @Override public int serverColor(Object folders, int id) {
            Object color = ((Map<?, ?>) folders).get(id);
            return color == null ? ChatTypeFolders.MISSING : (Integer) color;
        }

        @Override public void color(int account, int id, int color) {
            recolor(id, color);
        }

        /** The server's answer, posted to the main thread as Telegram's callback posts it, or held while offline. */
        private void answer(ChatTypeFolders.Reply reply, Supplier<String> error, Supplier<Object> response) {
            Runnable answer = () -> {
                String no = error.get();
                reply.done(no == null ? response.get() : null, no);
            };
            if (offline) held.add(answer);
            else Utils.runOnMainThread(answer);
        }

        /** Back online: the server answers everything it held, in the order it was asked. */
        void reconnect() {
            offline = false;
            for (Runnable answer : held) Utils.runOnMainThread(answer);
            held.clear();
        }
    }

    /** With no folders yet and none made before, whether flipping the switch to what's saved asks Telegram for any. */
    public static boolean asksForFolders() {
        Context context = RuntimeEnvironment.getApplication();
        ChatTypeFolders.Telegram telegram = ChatTypeFolders.telegram;
        Recorder recorder = new Recorder();
        // Nothing is answered, so the probe leaves no folders or toasts behind.
        recorder.offline = true;
        // A refusal puts the switch back; the probe leaves it as the test set it.
        boolean saved = Settings.CHAT_TYPE_FOLDERS.savedValue();
        ChatTypeFolders.prefs(context).edit().clear().commit();
        ChatTypeFolders.resetForTests();
        ChatTypeFolders.telegram = recorder;
        try {
            PatchFamilyForTests.alsoInBuild(PatchFamily.CHAT_TYPE_FOLDERS, () -> ChatTypeFolders.apply(context) != null);
        } finally {
            ChatTypeFolders.telegram = telegram;
            ChatTypeFolders.resetForTests();
            ChatTypeFolders.prefs(context).edit().clear().commit();
            if (Settings.CHAT_TYPE_FOLDERS.savedValue() != saved) Settings.CHAT_TYPE_FOLDERS.save(saved);
        }
        return !recorder.created.isEmpty() || !recorder.deleted.isEmpty() || !recorder.orders.isEmpty();
    }
}
