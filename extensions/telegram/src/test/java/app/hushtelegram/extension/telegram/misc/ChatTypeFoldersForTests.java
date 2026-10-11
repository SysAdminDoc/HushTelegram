/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import app.hushtelegram.extension.telegram.settings.PatchFamily;
import app.hushtelegram.extension.telegram.settings.PatchFamilyForTests;
import app.hushtelegram.extension.telegram.settings.Settings;
import org.robolectric.RuntimeEnvironment;

/** Lets tests outside this package ask the folder switch, and gives them a Telegram that keeps what it's asked. */
public final class ChatTypeFoldersForTests {
    private ChatTypeFoldersForTests() {}

    /** One account's folders, as Telegram's list holds them, and every request sent. */
    static class Recorder implements ChatTypeFolders.Telegram {
        int account = 0;
        long user = 1000;
        boolean signedIn = true;
        boolean loaded = true;
        int limit = 10;
        final List<ChatTypeFolders.Folder> folders = new ArrayList<>();
        final List<String> created = new ArrayList<>();
        final List<Integer> deleted = new ArrayList<>();

        /** A folder Telegram's list already has, with nothing set on it but its name and chat types. */
        Recorder with(int id, int flags, String name) {
            return with(id, flags, name, true);
        }

        Recorder with(int id, int flags, String name, boolean plain) {
            folders.add(new ChatTypeFolders.Folder(id, flags, name, plain));
            return this;
        }

        /** Swaps a listed folder for one with these details, as an edit in Telegram's folder screen would. */
        void edit(int id, int flags, String name, boolean plain) {
            folders.removeIf(folder -> folder.id == id);
            with(id, flags, name, plain);
        }

        @Override public int account() { return account; }
        @Override public boolean signedIn(int account) { return signedIn; }
        @Override public long user(int account) { return user; }
        @Override public List<ChatTypeFolders.Folder> folders(int account) { return loaded ? new ArrayList<>(folders) : null; }
        @Override public int limit(int account) { return limit; }

        @Override public void create(int account, int id, String name, int flags) {
            created.add(account + "/" + id + "/" + name + "/" + flags);
            folders.add(new ChatTypeFolders.Folder(id, flags, name, true));
        }

        @Override public void delete(int account, int id) {
            deleted.add(id);
            folders.removeIf(folder -> folder.id == id);
        }
    }

    /** With no folders yet and none made before, whether flipping the switch to what's saved asks Telegram for any. */
    public static boolean asksForFolders() {
        Context context = RuntimeEnvironment.getApplication();
        ChatTypeFolders.Telegram telegram = ChatTypeFolders.telegram;
        Recorder recorder = new Recorder();
        // A refusal puts the switch back; the probe leaves it as the test set it.
        boolean saved = Settings.CHAT_TYPE_FOLDERS.savedValue();
        ChatTypeFolders.prefs(context).edit().clear().commit();
        ChatTypeFolders.telegram = recorder;
        try {
            PatchFamilyForTests.alsoInBuild(PatchFamily.CHAT_TYPE_FOLDERS, () -> ChatTypeFolders.apply(context) != null);
        } finally {
            ChatTypeFolders.telegram = telegram;
            ChatTypeFolders.prefs(context).edit().clear().commit();
            if (Settings.CHAT_TYPE_FOLDERS.savedValue() != saved) Settings.CHAT_TYPE_FOLDERS.save(saved);
        }
        return !recorder.created.isEmpty() || !recorder.deleted.isEmpty();
    }
}
