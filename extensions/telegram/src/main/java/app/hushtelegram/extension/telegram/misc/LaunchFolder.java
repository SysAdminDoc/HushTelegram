/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Opens the chat list on a folder you picked instead of All chats.
 *
 * <p>Telegram builds the folder tabs above the chat list once it has more than one folder to show,
 * and the first tab it adds is the one selected. Just before that first build, with the switch on,
 * this selects the folder picked for the account the list belongs to, so the tabs and the list come
 * up on it with nothing to animate. The folder order and the server's copy of the folders aren't
 * touched.
 *
 * <p>Each account keeps its own pick on this phone, by the account's user id, so another account
 * signed in to the same slot never gets it. A pick is the folder's id and the name it had when
 * picked, since Telegram gives a deleted folder's id to the next one made. A folder that's gone,
 * renamed or locked by Telegram's folder limit is passed over and All chats opens as usual. Only
 * the main chat list takes it: the lists that pick a chat to share or forward to, and the archive,
 * open as Telegram has them. It happens once for each chat list Telegram makes. A list whose view
 * is built again, after a theme change say, gets new tabs and opens as Telegram has it then.
 */
public final class LaunchFolder {
    private LaunchFolder() {}

    /** What the diagnostic report counts each chat list opened on a picked folder as. */
    static final String OPENED = "chat list opened on the picked folder";

    private static final String PREFS = "hushtelegram_launch_folders";
    /** Added to a user id for the picked folder's name, beside its id under the user id itself. */
    private static final String NAME = ".name";

    /** The chat lists whose tabs were built once already. Weak, so a list Telegram drops goes too. Main thread only. */
    private static final Map<Object, Boolean> built = new WeakHashMap<>();

    static Function<Context, SharedPreferences> store = context -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

    /** Telegram's side, through the stubs below. A test answers it itself. */
    static Telegram telegram = new Stubs();

    /** A folder an account can open on: Telegram's id for it and its name. */
    public static final class Folder {
        public final int id;
        public final String name;

        Folder(int id, String name) {
            this.id = id;
            this.name = name;
        }

        /** Whether this is [pick]: the same id and the same name, so a new folder that took a deleted one's id isn't. */
        public boolean is(@Nullable Folder pick) {
            return pick != null && id == pick.id && name.equals(pick.name);
        }
    }

    /** The account Telegram shows now, as the settings page reads it once: who's signed in, their folders and their pick. */
    public static final class Shown {
        /** The user id, or 0 when no one is signed in. */
        public final long user;
        public final List<Folder> folders;
        @Nullable public final Folder pick;

        Shown(long user, List<Folder> folders, @Nullable Folder pick) {
            this.user = user;
            this.folders = folders;
            this.pick = pick;
        }

        /** Where the pick sits in a list of All Chats then [folders]: 0 for All Chats, and for a pick that's gone. */
        public int choice() {
            return choiceOf(pick);
        }

        /** Where [folder] sits in that list, 0 when it isn't there. */
        public int choiceOf(@Nullable Folder folder) {
            for (int i = 0; i < folders.size(); i++) {
                if (folders.get(i).is(folder)) return i + 1;
            }
            return 0;
        }
    }

    /**
     * Injected in DialogsActivity.updateFilterTabs where it asks whether the folder tabs are still
     * empty, before any is added.
     */
    public static void firstTabs(Object list) {
        HookStatus.invoked(FamilyNames.HIDE_FOLDER_COUNTERS);
        try {
            // Counted whatever the switch says, so turning it on later doesn't move a list already up.
            if (!telegram.tabsEmpty(list) || built.put(list, Boolean.TRUE) != null) return;
            if (!Utils.settingsReady() || !Settings.LAUNCH_FOLDER.get()) return;
            if (!mainList(telegram.arguments(list))) return;
            int account = telegram.account(list);
            int index = indexOf(account, picked(telegram.userId(account)));
            if (index >= 0 && telegram.select(list, index)) {
                HookStatus.counted(FamilyNames.HIDE_FOLDER_COUNTERS, OPENED);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, "folder at launch", failure);
        }
    }

    /**
     * Whether a chat list with [args] is the main one, the one the Chats tab shows. A list that picks
     * where to share or forward, and the archive, aren't.
     */
    static boolean mainList(@Nullable Bundle args) {
        return args != null && args.getBoolean("hasMainTabs", false) && !args.getBoolean("onlySelect", false)
                && args.getInt("dialogsType", 0) == 0 && args.getInt("folderId", 0) == 0;
    }

    /** Where [pick] sits in [account]'s folder list, or -1 when it's not there, renamed or locked. */
    private static int indexOf(int account, @Nullable Folder pick) {
        if (pick == null) return -1;
        int count = telegram.folderCount(account);
        for (int i = 0; i < count; i++) {
            if (telegram.folderDefault(account, i) || !folderAt(account, i).is(pick)) continue;
            return telegram.folderLocked(account, i) ? -1 : i;
        }
        return -1;
    }

    private static Folder folderAt(int account, int index) {
        String name = telegram.folderName(account, index);
        return new Folder(telegram.folderId(account, index), name == null ? "" : name);
    }

    /**
     * The account Telegram shows now, its folders and its pick, or null when Telegram's side can't
     * be read, which the settings page says rather than going down with it.
     */
    @Nullable
    public static Shown shown() {
        try {
            int account = telegram.selectedAccount();
            long user = telegram.userId(account);
            return new Shown(user, folders(account), picked(user));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, "folder list", failure);
            return null;
        }
    }

    /** The folders [account] can open on, in Telegram's order: every one but All chats and the locked ones. */
    static List<Folder> folders(int account) {
        List<Folder> folders = new ArrayList<>();
        int count = telegram.folderCount(account);
        for (int i = 0; i < count; i++) {
            if (telegram.folderDefault(account, i) || telegram.folderLocked(account, i)) continue;
            folders.add(folderAt(account, i));
        }
        return Collections.unmodifiableList(folders);
    }

    /** The folder picked for [user], or null for All chats and for a pick that can't be read. */
    @Nullable
    public static Folder picked(long user) {
        Context context = Utils.getContext();
        if (user == 0 || context == null) return null;
        try {
            SharedPreferences saved = store.apply(context);
            int id = saved.getInt(String.valueOf(user), 0);
            String name = saved.getString(user + NAME, null);
            return id > 0 && name != null ? new Folder(id, name) : null;
        } catch (RuntimeException unreadable) {
            HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, "folder pick", unreadable);
            return null;
        }
    }

    /**
     * Saves [folder] as [user]'s pick, null for All chats. It's written before this returns, so call
     * it off the main thread.
     *
     * @return whether it was written
     */
    public static boolean pick(long user, @Nullable Folder folder) {
        Context context = Utils.getContext();
        if (user == 0 || context == null) return false;
        SharedPreferences.Editor edit = store.apply(context).edit();
        if (folder != null && folder.id > 0) {
            edit.putInt(String.valueOf(user), folder.id).putString(user + NAME, folder.name);
        } else {
            edit.remove(String.valueOf(user)).remove(user + NAME);
        }
        return edit.commit();
    }

    /** Forgets which chat lists were built, as a restart does. */
    static void resetForTests() {
        built.clear();
    }

    /** What this needs from Telegram. */
    interface Telegram {
        boolean tabsEmpty(Object list);

        @Nullable
        Bundle arguments(Object list);

        int account(Object list);

        boolean select(Object list, int index);

        int selectedAccount();

        long userId(int account);

        int folderCount(int account);

        int folderId(int account, int index);

        @Nullable
        String folderName(int account, int index);

        boolean folderLocked(int account, int index);

        boolean folderDefault(int account, int index);
    }

    private static final class Stubs implements Telegram {
        @Override public boolean tabsEmpty(Object list) { return LaunchFolder.tabsEmpty(list); }
        @Override public Bundle arguments(Object list) { return LaunchFolder.arguments(list); }
        @Override public int account(Object list) { return LaunchFolder.account(list); }
        @Override public boolean select(Object list, int index) { return LaunchFolder.select(list, index); }
        @Override public int selectedAccount() { return LaunchFolder.selectedAccount(); }
        @Override public long userId(int account) { return LaunchFolder.userId(account); }
        @Override public int folderCount(int account) { return LaunchFolder.folderCount(account); }
        @Override public int folderId(int account, int index) { return LaunchFolder.folderId(account, index); }
        @Override public String folderName(int account, int index) { return LaunchFolder.folderName(account, index); }
        @Override public boolean folderLocked(int account, int index) { return LaunchFolder.folderLocked(account, index); }
        @Override public boolean folderDefault(int account, int index) { return LaunchFolder.folderDefault(account, index); }
    }

    // Stubs. The patch gives each one Telegram's code; until then they do nothing.

    /** {@code list.filterTabsView}'s tabs are empty, the tabs not built yet. */
    public static boolean tabsEmpty(Object list) { return false; }

    /** {@code list.getArguments()}. */
    @Nullable
    public static Bundle arguments(Object list) { return null; }

    /** {@code list.getCurrentAccount()}. */
    public static int account(Object list) { return 0; }

    /**
     * {@code list.viewPages[0].selectedType} and the tabs' selected id set to [index], so the tab
     * Telegram adds with that id is the selected one. False when the list has no tabs or pages.
     */
    public static boolean select(Object list, int index) { return false; }

    /** {@code UserConfig.selectedAccount}. */
    public static int selectedAccount() { return 0; }

    /** {@code UserConfig.getInstance(account).getClientUserId()}. */
    public static long userId(int account) { return 0; }

    /** {@code MessagesController.getInstance(account).getDialogFilters().size()}. */
    public static int folderCount(int account) { return 0; }

    /** {@code getDialogFilters().get(index).id}. */
    public static int folderId(int account, int index) { return 0; }

    /** {@code getDialogFilters().get(index).name}. */
    @Nullable
    public static String folderName(int account, int index) { return null; }

    /** {@code getDialogFilters().get(index).locked}. */
    public static boolean folderLocked(int account, int index) { return false; }

    /** {@code getDialogFilters().get(index).isDefault()}, All chats. */
    public static boolean folderDefault(int account, int index) { return false; }
}
