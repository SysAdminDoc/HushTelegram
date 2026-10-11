/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.PatchFamily;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Folders by chat type in one tap: Private, Groups, Channels and Bots, made as Telegram's own
 * folders, so they're tabs above the chat list like any folder you make and they reach your other
 * devices too.
 *
 * <p>Turning the switch on does what Telegram's own folder screen does for a new folder, for each
 * type you don't already have a folder for, as far as Telegram's folder limit goes: it sends
 * {@code messages.updateDialogFilter} with only chat types in it and puts the folder in Telegram's
 * list and database. Turning it off deletes the folders this made that are still as they were
 * made, the way Telegram's own Delete folder does. One you renamed, colored, gave an emoji or
 * added, removed or pinned chats in is yours. Nothing happens at a start, only when the switch is
 * flipped, and when nothing can be done (paused, signed out, folders still loading) the switch
 * goes back to where it was.
 *
 * <p>Which folders it made is kept in its own prefs file, by Telegram user and folder id. None of
 * it is a setting, and the switch stays out of settings backups: importing one would flip it
 * without making or removing anything.
 */
public final class ChatTypeFolders {
    static final String PREFS = "hushtelegram_chat_type_folders";
    /** Telegram gives folders you make ids from 2; 0 is All chats. */
    static final int FIRST_ID = 2;
    /** The chat type bits of a folder, MessagesController.DIALOG_FILTER_FLAG_CONTACTS through BOTS. */
    static final int ALL_TYPES = 1 | 2 | 4 | 8 | 16;

    private ChatTypeFolders() {}

    /** The folders, in the order they're made. Each name is short enough for Telegram's 12 characters. */
    enum Type {
        PRIVATE("Private", 1 | 2),
        GROUPS("Groups", 4),
        CHANNELS("Channels", 8),
        BOTS("Bots", 16);

        final String english;
        final int flags;

        Type(String english, int flags) {
            this.english = english;
            this.flags = flags;
        }

        String title() {
            switch (this) {
                case PRIVATE: return L10n.t("Private");
                case GROUPS: return L10n.t("Groups");
                case CHANNELS: return L10n.t("Channels");
                default: return L10n.t("Bots");
            }
        }
    }

    /** One of Telegram's folders. */
    static final class Folder {
        final int id;
        final int flags;
        final String name;
        /** No chats added, left out or pinned, no color and no emoji in its name. */
        final boolean plain;

        Folder(int id, int flags, String name, boolean plain) {
            this.id = id;
            this.flags = flags;
            this.name = name;
            this.plain = plain;
        }
    }

    /** Telegram's side, swapped out by tests. The real one calls the stubs the patch writes. */
    interface Telegram {
        int account();

        boolean signedIn(int account);

        /** The signed-in Telegram user, so records stay with them and not with the account slot. */
        long user(int account);

        /** Null until Telegram has loaded the account's folders. */
        List<Folder> folders(int account);

        /** How many folders you can make yourself, All chats aside. */
        int limit(int account);

        /** Sends the new folder and adds it to Telegram's list, as Telegram's folder screen does. */
        void create(int account, int id, String name, int flags);

        /** Sends the delete and takes the folder out of Telegram's list, as Telegram's Delete folder does. */
        void delete(int account, int id);
    }

    static Telegram telegram = new Telegram() {
        @Override
        public int account() {
            return selectedAccount();
        }

        @Override
        public boolean signedIn(int account) {
            return ChatTypeFolders.signedIn(account);
        }

        @Override
        public long user(int account) {
            return userId(account);
        }

        @Override
        public List<Folder> folders(int account) {
            if (!foldersLoaded(account)) return null;
            List<Folder> folders = new ArrayList<>();
            for (int i = 0, count = folderCount(account); i < count; i++) {
                folders.add(new Folder(folderId(account, i), folderFlags(account, i), folderName(account, i),
                        folderPlain(account, i)));
            }
            return folders;
        }

        @Override
        public int limit(int account) {
            return folderLimit(account);
        }

        @Override
        public void create(int account, int id, String name, int flags) {
            createFolder(account, id, name, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0,
                    (flags & 16) != 0);
            addFolder(account, id, name, flags);
        }

        @Override
        public void delete(int account, int id) {
            deleteFolder(account, id);
        }
    };

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * From the switch, on the main thread, once its new value is saved: makes the folders with
     * the switch on and removes the ones this made with it off. When it can't, it puts the switch
     * back, and the settings screen shows that.
     *
     * @return what to tell the person, or null when there's nothing to say
     */
    public static String apply(Context context) {
        if (context == null || !PatchFamily.CHAT_TYPE_FOLDERS.inBuild()) return null;
        boolean on = false;
        try {
            if (!Utils.settingsReady()) return null;
            on = Settings.CHAT_TYPE_FOLDERS.savedValue();
            if (HushTelegramPause.isPaused()) return undo(on, L10n.t("HushTelegram is paused, so your folders stay as they are."));
            int account = telegram.account();
            if (!telegram.signedIn(account)) return undo(on, L10n.t("Sign in to Telegram first."));
            List<Folder> folders = telegram.folders(account);
            if (folders == null) return undo(on, L10n.t("Telegram is still loading your folders. Try again in a moment."));
            long user = telegram.user(account);
            return on ? make(context, account, user, folders) : remove(context, account, user, folders);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_TYPE_FOLDERS, "switch", failure);
            return undo(on, L10n.t("Couldn't change your folders. Try again in a moment."));
        }
    }

    /** Puts the switch back to what it was before this flip, and says why. */
    private static String undo(boolean on, String said) {
        try {
            Settings.CHAT_TYPE_FOLDERS.save(!on);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_TYPE_FOLDERS, "switch back", failure);
        }
        return said;
    }

    private static String make(Context context, int account, long user, List<Folder> folders) {
        Set<Integer> taken = new HashSet<>();
        int yours = 0;
        for (Folder folder : folders) {
            taken.add(folder.id);
            if (folder.id != 0) yours++;
        }
        int room = telegram.limit(account) - yours;
        SharedPreferences made = prefs(context);
        int count = 0, left = 0, next = FIRST_ID;
        for (Type type : Type.values()) {
            if (has(folders, type.flags)) continue;
            if (room <= 0) {
                left++;
                continue;
            }
            while (taken.contains(next)) next++;
            String name = type.title();
            // Kept before it's sent, so a folder that went out is always one Off can find.
            made.edit().putString(key(user, next), type.flags + ":" + name).commit();
            telegram.create(account, next, name, type.flags);
            taken.add(next);
            room--;
            count++;
        }
        if (count > 0) HookStatus.counted(FamilyNames.CHAT_TYPE_FOLDERS, "folder made");
        if (count == 0 && left == 0) return L10n.t("You already have a folder for each chat type.");
        if (count == 0) return undo(true, L10n.t("Telegram's folder limit is reached, so no folders were made. Remove one and try again."));
        if (left > 0) return L10n.t("Made some of the folders. Telegram's folder limit left no room for the rest.");
        return L10n.t("Made folders for private chats, groups, channels and bots. They're at the top of your chat list.");
    }

    private static String remove(Context context, int account, long user, List<Folder> folders) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor forget = prefs.edit();
        String prefix = user + ":";
        int count = 0;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(prefix) || !(entry.getValue() instanceof String)) continue;
            forget.remove(entry.getKey());
            String[] made = ((String) entry.getValue()).split(":", 2);
            int id;
            int flags;
            try {
                id = Integer.parseInt(entry.getKey().substring(prefix.length()));
                flags = Integer.parseInt(made[0]);
            } catch (RuntimeException unreadable) {
                continue;
            }
            Folder now = null;
            for (Folder folder : folders) {
                if (folder.id == id) now = folder;
            }
            // Gone already means you deleted it; nothing is sent for an id that may be reused.
            if (now != null && now.plain && now.flags == flags && made.length == 2 && made[1].equals(now.name)) {
                telegram.delete(account, id);
                count++;
            }
        }
        forget.commit();
        if (count == 0) return L10n.t("There were no folders from HushTelegram to remove.");
        HookStatus.counted(FamilyNames.CHAT_TYPE_FOLDERS, "folder removed");
        return L10n.t("Removed the folders HushTelegram made. Any you renamed or changed stay.");
    }

    /** Whether a folder already shows exactly these chat types, whatever else it leaves out. */
    private static boolean has(List<Folder> folders, int flags) {
        for (Folder folder : folders) {
            if (folder.id != 0 && (folder.flags & ALL_TYPES) == flags) return true;
        }
        return false;
    }

    private static String key(long user, int id) {
        return user + ":" + id;
    }

    // Stubs. The patch gives each one Telegram's code; until then they do nothing.

    /** {@code UserConfig.selectedAccount}. */
    public static int selectedAccount() { return 0; }

    /** {@code UserConfig.getInstance(account).isClientActivated()}. */
    public static boolean signedIn(int account) { return false; }

    /** {@code UserConfig.getInstance(account).getClientUserId()}. */
    public static long userId(int account) { return 0; }

    /** {@code MessagesController.getInstance(account).dialogFiltersLoaded}. */
    public static boolean foldersLoaded(int account) { return false; }

    /** {@code MessagesController.getInstance(account).dialogFilters.size()}. */
    public static int folderCount(int account) { return 0; }

    /** {@code dialogFilters.get(index).id}. */
    public static int folderId(int account, int index) { return 0; }

    /** {@code dialogFilters.get(index).flags}. */
    public static int folderFlags(int account, int index) { return 0; }

    /** {@code dialogFilters.get(index).name}. */
    public static String folderName(int account, int index) { return null; }

    /** No color, no title entities, and empty alwaysShow, neverShow and pinnedDialogs. */
    public static boolean folderPlain(int account, int index) { return false; }

    /** {@code dialogFiltersLimitPremium - 1} with Premium, {@code dialogFiltersLimitDefault} without. */
    public static int folderLimit(int account) { return 0; }

    /** {@code messages.updateDialogFilter} for a new folder with these chat types, sent without a callback. */
    public static void createFolder(int account, int id, String name, boolean contacts, boolean nonContacts,
            boolean groups, boolean channels, boolean bots) {}

    /** A new local folder through {@code addFilter} and {@code saveDialogFilter}, then {@code dialogFiltersUpdated}. */
    public static void addFolder(int account, int id, String name, int flags) {}

    /** {@code messages.updateDialogFilter} with no filter, then {@code removeFilter} and {@code deleteDialogFilter}. */
    public static void deleteFolder(int account, int id) {}
}
