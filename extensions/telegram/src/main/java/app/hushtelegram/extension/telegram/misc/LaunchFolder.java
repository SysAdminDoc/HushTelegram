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
 * signed in to the same slot never gets it. A folder that's gone, or locked by Telegram's folder
 * limit, is passed over and All chats opens as usual. Only the main chat list takes it: the lists
 * that pick a chat to share or forward to, and the archive, open as Telegram has them. It happens
 * once for each chat list Telegram makes, so a list whose view is built again, after a theme
 * change say, stays where you left it.
 */
public final class LaunchFolder {
    private LaunchFolder() {}

    /** What the diagnostic report counts each chat list opened on a picked folder as. */
    static final String OPENED = "chat list opened on the picked folder";

    private static final String PREFS = "hushtelegram_launch_folders";

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

    /** Where [folder] sits in [account]'s folder list, or -1 when it's not there or locked. */
    private static int indexOf(int account, int folder) {
        if (folder <= 0) return -1;
        int count = telegram.folderCount(account);
        for (int i = 0; i < count; i++) {
            if (telegram.folderDefault(account, i) || telegram.folderId(account, i) != folder) continue;
            return telegram.folderLocked(account, i) ? -1 : i;
        }
        return -1;
    }

    /** The account Telegram shows now. */
    public static int currentAccount() {
        return telegram.selectedAccount();
    }

    /** [account]'s user id, or 0 when no one is signed in there. */
    public static long user(int account) {
        return telegram.userId(account);
    }

    /** The folders [account] can open on, in Telegram's order: every one but All chats and the locked ones. */
    public static List<Folder> folders(int account) {
        List<Folder> folders = new ArrayList<>();
        int count = telegram.folderCount(account);
        for (int i = 0; i < count; i++) {
            if (telegram.folderDefault(account, i) || telegram.folderLocked(account, i)) continue;
            String name = telegram.folderName(account, i);
            folders.add(new Folder(telegram.folderId(account, i), name == null ? "" : name));
        }
        return Collections.unmodifiableList(folders);
    }

    /** The folder id picked for [user], or 0 for All chats. */
    public static int picked(long user) {
        Context context = Utils.getContext();
        if (user == 0 || context == null) return 0;
        return store.apply(context).getInt(String.valueOf(user), 0);
    }

    /**
     * Saves [folder] as [user]'s pick, 0 for All chats. It's written before this returns, so call
     * it off the main thread.
     *
     * @return whether it was written
     */
    public static boolean pick(long user, int folder) {
        Context context = Utils.getContext();
        if (user == 0 || context == null) return false;
        SharedPreferences.Editor edit = store.apply(context).edit();
        if (folder > 0) edit.putInt(String.valueOf(user), folder);
        else edit.remove(String.valueOf(user));
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
