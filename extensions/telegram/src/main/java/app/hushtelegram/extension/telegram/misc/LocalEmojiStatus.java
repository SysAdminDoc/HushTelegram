/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.function.Function;
import java.util.function.LongSupplier;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Telegram's emoji status is a Premium feature: the picker opens from the star next to the chat
 * list title, and the server turns the change down for an account without Premium. With the switch
 * on, the title's star shows for every account, and the emoji picked there stays on this phone
 * instead of going to Telegram. The patch asks here before MessagesController sends the account's
 * new status, and the chat list title asks here for the emoji to draw next to the account's name.
 * Nobody else sees it. Channel statuses and collectible gifts still go to Telegram as usual.
 *
 * <p>Each signed-in account keeps its own pick, by its user id, until the duration picked with a
 * long press runs out or the selected emoji is tapped again to clear it.
 *
 * <p>The title only asks when Telegram redraws it, which the switch itself never causes. So when
 * the Hush settings close on a switch the title last heard the other way round, the title is told
 * to draw again ({@link #settingsClosed()}) and the star comes or goes without a restart.
 *
 * <p>Keep emoji statuses moving, a switch of its own, lets every animated status next to a name
 * go on playing instead of stopping after two plays ({@link #statusRepeatType}).
 */
public final class LocalEmojiStatus {
    private LocalEmojiStatus() {}

    /** What the diagnostic report counts each title drawn with the kept emoji as. */
    static final String SHOWN = "kept emoji status shown";

    /** What the report counts each status kept on this phone instead of sent as. */
    static final String KEPT = "emoji status kept on this phone";

    /** AnimatedEmojiDrawable's cache types for a status next to a name and one in a sheet. */
    private static final int STATUS_BY_NAME = 7;
    private static final int STATUS_IN_SHEET = 9;

    /** A cache type AnimatedEmojiDrawable sets no play count for. */
    private static final int NO_PLAY_COUNT = -1;

    static final String PREFS = "hushtelegram_local_emoji_status";
    private static final String UNTIL = ".until";

    static Function<Context, SharedPreferences> store = context -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

    /** Seconds since 1970, the clock a status's {@code until} counts in. */
    static LongSupplier clock = () -> System.currentTimeMillis() / 1000;

    /** Telegram's side, through the stubs below. A test answers it itself. */
    static Telegram telegram = new Stubs();

    /** The switch as the chat list title last heard it, or null until it has asked. */
    static volatile Boolean heard;

    /** The emoji the chat list title draws for [user]: the one kept on this phone while the switch is on, else Telegram's [stock]. */
    public static Long documentId(Long stock, Object user) {
        HookStatus.invoked(FamilyNames.HIDE_COMMERCE);
        if (user == null) return stock;
        try {
            if (!on()) return stock;
            Context context = Utils.getContext();
            if (context == null) return stock;
            long kept = kept(store.apply(context), telegram.userId(user));
            if (kept == 0) return stock;
            HookStatus.counted(FamilyNames.HIDE_COMMERCE, SHOWN);
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_COMMERCE, "kept emoji status", failure);
            return stock;
        }
    }

    /** Whether the chat list title counts its account as Premium: Telegram's answer (non-zero for yes), or yes while the switch is on. */
    public static boolean premium(int stock) {
        HookStatus.invoked(FamilyNames.HIDE_COMMERCE);
        try {
            boolean on = on();
            heard = on;
            return stock != 0 || on;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_COMMERCE, "local emoji status switch", failure);
            return stock != 0;
        }
    }

    /**
     * The Hush settings closed. A title that last heard the switch the other way round draws again,
     * so the star and the kept emoji come or go at once. A title that never asked is left alone.
     */
    public static void settingsClosed() {
        try {
            Boolean last = heard;
            if (last == null || last == on()) return;
            telegram.refreshSelected();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_COMMERCE, "emoji status after settings", failure);
        }
    }

    /**
     * Keeps the account's new [status] on this phone while the switch is on, and redraws the title.
     * An empty status clears what was kept. A collectible gift goes to Telegram as usual.
     *
     * @return whether Telegram skips sending it
     */
    public static boolean keep(Object controller, Object status) {
        HookStatus.invoked(FamilyNames.HIDE_COMMERCE);
        if (controller == null || status == null) return false;
        try {
            if (!on()) return false;
            long document = telegram.statusDocument(status);
            if (document < 0) return false;
            long user = telegram.controllerUser(controller);
            Context context = Utils.getContext();
            if (user == 0 || context == null) return false;
            SharedPreferences.Editor edit = store.apply(context).edit();
            if (document == 0) {
                edit.remove(String.valueOf(user)).remove(user + UNTIL);
            } else {
                edit.putLong(String.valueOf(user), document).putInt(user + UNTIL, telegram.statusUntil(status));
            }
            edit.apply();
            telegram.refresh(controller);
            HookStatus.counted(FamilyNames.HIDE_COMMERCE, KEPT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_COMMERCE, "keep emoji status", failure);
            return false;
        }
    }

    /**
     * The cache type AnimatedEmojiDrawable sets an image's play count by. A status next to a name
     * or in a sheet plays twice and stops; while Keep emoji statuses moving is on, its type comes
     * back as one Telegram sets no count for, so it keeps looping. Any other [type] is Telegram's.
     */
    public static int statusRepeatType(int type) {
        HookStatus.invoked(FamilyNames.HIDE_COMMERCE);
        if (type != STATUS_BY_NAME && type != STATUS_IN_SHEET) return type;
        try {
            return Utils.settingsReady() && Settings.MOVING_EMOJI_STATUS.get() ? NO_PLAY_COUNT : type;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_COMMERCE, "moving emoji status switch", failure);
            return type;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.LOCAL_EMOJI_STATUS.get();
    }

    /** [user]'s kept emoji, or 0 for none or one whose time ran out. */
    private static long kept(SharedPreferences saved, long user) {
        if (user == 0) return 0;
        long document = saved.getLong(String.valueOf(user), 0);
        int until = saved.getInt(user + UNTIL, 0);
        return until != 0 && until <= clock.getAsLong() ? 0 : document;
    }

    interface Telegram {
        long userId(Object user);

        long controllerUser(Object controller);

        long statusDocument(Object status);

        int statusUntil(Object status);

        void refresh(Object controller);

        void refreshSelected();
    }

    private static final class Stubs implements Telegram {
        @Override public long userId(Object user) { return LocalEmojiStatus.userId(user); }
        @Override public long controllerUser(Object controller) { return LocalEmojiStatus.controllerUser(controller); }
        @Override public long statusDocument(Object status) { return LocalEmojiStatus.statusDocument(status); }
        @Override public int statusUntil(Object status) { return LocalEmojiStatus.statusUntil(status); }
        @Override public void refresh(Object controller) { LocalEmojiStatus.refresh(controller); }
        @Override public void refreshSelected() { LocalEmojiStatus.refreshSelected(); }
    }

    // Stubs. The patch gives each one Telegram's code; until then they do nothing.

    /** {@code ((TLRPC.User) user).id}. */
    public static long userId(Object user) { return 0; }

    /** {@code controller.getUserConfig().getClientUserId()}, for a MessagesController. */
    public static long controllerUser(Object controller) { return 0; }

    /** A {@code TL_emojiStatus}'s {@code document_id}, 0 for {@code TL_emojiStatusEmpty}, -1 for anything else. */
    public static long statusDocument(Object status) { return -1; }

    /** {@code ((TLRPC.EmojiStatus) status).until}, 0 for no end. */
    public static int statusUntil(Object status) { return 0; }

    /** Posts {@code userEmojiStatusUpdated} with the controller's current user, which redraws the chat list title. */
    public static void refresh(Object controller) {}

    /** {@link #refresh} with {@code MessagesController.getInstance(UserConfig.selectedAccount)}. */
    public static void refreshSelected() {}
}
