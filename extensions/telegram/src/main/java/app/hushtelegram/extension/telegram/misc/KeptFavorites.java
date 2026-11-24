/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Favorite stickers past Telegram's limit (#110). Telegram keeps five favorites, ten with Premium,
 * and adding one more drops the oldest. With the switch on, a sticker Telegram drops that way is
 * kept on this phone instead, and the sticker panel's Favorites section, the sticker search's row
 * of favorites and the sticker menu's favorite check count it with Telegram's own, after them.
 * Removing a kept one from favorites removes it from this phone only, and one that's a Telegram
 * favorite again stops being kept, so nothing shows twice.
 *
 * <p>The patch asks here at the start of MediaDataController.addRecentSticker, where its oldest
 * favorite falls off the list, after getRecentStickers copies a list, at isStickerInFavorites'
 * "no" and when FileRefController asks Telegram again for an expired file reference. Telegram
 * refreshes a favorite's reference through the account's favorites list, which no longer holds a
 * kept sticker, so a kept one asks through its sticker pack instead.
 *
 * <p>Each signed-in account keeps its own, by its user id, newest first and at most {@link #MAX}.
 * Off or paused, Telegram's favorites are all that shows and the kept ones wait for the switch.
 */
public final class KeptFavorites {
    private KeptFavorites() {}

    static final String PREFS = "hushtelegram_kept_favorites";

    /** How many stickers an account keeps past Telegram's limit; the oldest goes first. */
    static final int MAX = 200;

    /** MediaDataController's TYPE_FAVE, the favorites among its recent sticker lists. */
    static final int FAVORITES = 2;

    /** The parent Telegram gives a favorite sticker, which refreshes it through the favorites list. */
    static final String FAVORITES_PARENT = "fav";

    /** What the diagnostic report counts each sticker kept past Telegram's limit as. */
    static final String KEPT = "favorite sticker kept on this phone";

    /** What it counts each list of favorites shown with kept stickers in it as. */
    static final String SHOWN = "favorites shown with kept stickers";

    /** What it counts each kept sticker's file reference asked for through its pack as. */
    static final String REFRESHED = "kept favorite refreshed through its pack";

    /** What the diagnostic report names a kept sticker that can't be read back as. */
    static final String UNREADABLE = "kept favorite sticker unreadable";

    private static final String WHAT = "kept favorite stickers";

    static Function<Context, SharedPreferences> store = context -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

    /** Telegram's side, through the stubs and fields below. A test answers it itself. */
    static Telegram telegram = new Stubs();

    /** Each account's kept stickers, newest first, read from the store the first time they're asked for. */
    private static final Map<Long, List<Kept>> accounts = new HashMap<>();

    /** The favorite addRecentSticker is adding on this thread, so the sticker it drops can be kept. */
    private static final ThreadLocal<Object> adding = new ThreadLocal<>();

    /**
     * At the start of {@code addRecentSticker(type, parent, document, date, remove)}. A favorite
     * that's added or removed stops being kept here: added, it's one of Telegram's again, and
     * removed, it's gone. An addition is remembered for {@link #dropped}.
     *
     * @param remove non-zero when the sticker is being removed from the list
     */
    public static void faving(Object controller, int type, Object parent, Object document, int date, int remove) {
        adding.remove();
        if (type != FAVORITES || controller == null || document == null) return;
        HookStatus.invoked(FamilyNames.CHANGE_STICKER_SIZE);
        try {
            if (!on()) return;
            if (remove == 0) adding.set(controller);
            Context context = Utils.getContext();
            long user = telegram.account(controller);
            if (context == null || user == 0) return;
            long id = telegram.documentId(document);
            synchronized (KeptFavorites.class) {
                List<Kept> kept = keptFor(context, user);
                if (drop(kept, id)) save(context, user, kept);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHANGE_STICKER_SIZE, WHAT, failure);
        }
    }

    /**
     * Where addRecentSticker takes the last sticker off a list that's over its limit. When that's
     * the favorites list, while a favorite is being added, the sticker is kept here.
     */
    public static void dropped(Object document) {
        Object controller = adding.get();
        adding.remove();
        if (controller == null || document == null) return;
        HookStatus.invoked(FamilyNames.CHANGE_STICKER_SIZE);
        try {
            if (!on()) return;
            Context context = Utils.getContext();
            long user = telegram.account(controller);
            if (context == null || user == 0) return;
            byte[] data = telegram.serialized(document);
            if (data == null) return;
            Kept added = new Kept(telegram.documentId(document), document, Base64.encodeToString(data, Base64.NO_WRAP));
            synchronized (KeptFavorites.class) {
                List<Kept> kept = keptFor(context, user);
                drop(kept, added.id);
                kept.add(0, added);
                while (kept.size() > MAX) kept.remove(kept.size() - 1);
                save(context, user, kept);
            }
            HookStatus.counted(FamilyNames.CHANGE_STICKER_SIZE, KEPT);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHANGE_STICKER_SIZE, WHAT, failure);
        }
    }

    /**
     * After getRecentStickers copies [source], one of the account's recent sticker lists, into
     * [copy]. When it's the favorites, the kept stickers go after Telegram's, and one that's among
     * Telegram's favorites again stops being kept.
     */
    @SuppressWarnings("unchecked")
    public static void listed(Object controller, Object source, Object copy) {
        if (controller == null || !(source instanceof List) || !(copy instanceof List)) return;
        try {
            if (!on() || source != telegram.favoritesOf(controller)) return;
            HookStatus.invoked(FamilyNames.CHANGE_STICKER_SIZE);
            Context context = Utils.getContext();
            long user = telegram.account(controller);
            if (context == null || user == 0) return;
            Set<Long> telegrams = new HashSet<>();
            for (Object document : (List<?>) source) if (document != null) telegrams.add(telegram.documentId(document));
            List<Object> shown = new ArrayList<>();
            synchronized (KeptFavorites.class) {
                List<Kept> kept = keptFor(context, user);
                boolean pruned = false;
                for (Iterator<Kept> each = kept.iterator(); each.hasNext(); ) {
                    Kept one = each.next();
                    if (telegrams.contains(one.id)) {
                        each.remove();
                        pruned = true;
                    } else {
                        shown.add(one.document);
                    }
                }
                if (pruned) save(context, user, kept);
            }
            if (shown.isEmpty()) return;
            ((List<Object>) copy).addAll(shown);
            HookStatus.counted(FamilyNames.CHANGE_STICKER_SIZE, SHOWN);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHANGE_STICKER_SIZE, WHAT, failure);
        }
    }

    /**
     * At isStickerInFavorites' "no": whether [document] is a favorite after all, kept on this
     * phone. The sticker menu then offers to remove it rather than add it again.
     *
     * @param stock Telegram's answer, non-zero for yes
     */
    public static boolean favorite(Object controller, Object document, int stock) {
        if (stock != 0) return true;
        if (controller == null || document == null) return false;
        HookStatus.invoked(FamilyNames.CHANGE_STICKER_SIZE);
        try {
            if (!on()) return false;
            Context context = Utils.getContext();
            long user = telegram.account(controller);
            if (context == null || user == 0) return false;
            long id = telegram.documentId(document);
            synchronized (KeptFavorites.class) {
                return find(keptFor(context, user), id) != null;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHANGE_STICKER_SIZE, WHAT, failure);
            return false;
        }
    }

    /**
     * At the start of FileRefController.requestReference: the parent Telegram refreshes an expired
     * file reference through. A kept sticker came from the favorites list, which no longer has it,
     * so it asks through its sticker pack instead. Anything else keeps [parent].
     *
     * @param args what Telegram asks again for, the request or file location first
     */
    public static Object referenceParent(Object controller, Object args, Object parent) {
        if (!FAVORITES_PARENT.equals(parent) || controller == null) return parent;
        HookStatus.invoked(FamilyNames.CHANGE_STICKER_SIZE);
        try {
            if (!on()) return parent;
            Context context = Utils.getContext();
            long user = telegram.account(controller);
            long id = telegram.requestedId(args);
            if (context == null || user == 0 || id == 0) return parent;
            Kept found;
            synchronized (KeptFavorites.class) {
                found = find(keptFor(context, user), id);
            }
            if (found == null) return parent;
            Object pack = telegram.stickerSet(found.document);
            if (pack == null) return parent;
            HookStatus.counted(FamilyNames.CHANGE_STICKER_SIZE, REFRESHED);
            return pack;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHANGE_STICKER_SIZE, WHAT, failure);
            return parent;
        }
    }

    /** Forgets what was read from the store, for the duress wipe, which clears the store itself. */
    public static void forget() {
        synchronized (KeptFavorites.class) {
            accounts.clear();
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.KEEP_MORE_FAVORITES.get();
    }

    /**
     * [user]'s kept stickers, read from the store the first time. An entry that can't be read is
     * left out and named in the diagnostic report. Android's Base64 skips characters it doesn't
     * know rather than refusing them, so garbage reads back as bytes that are no document, and
     * Telegram's reader answers those with null.
     */
    private static List<Kept> keptFor(Context context, long user) {
        List<Kept> kept = accounts.get(user);
        if (kept != null) return kept;
        kept = new ArrayList<>();
        String saved = store.apply(context).getString(String.valueOf(user), "");
        if (saved != null && !saved.isEmpty()) {
            for (String entry : saved.split(",")) {
                if (kept.size() >= MAX) break;
                try {
                    Object document = telegram.deserialized(Base64.decode(entry, Base64.NO_WRAP));
                    if (document == null) throw new IllegalArgumentException("not a sticker document");
                    long id = telegram.documentId(document);
                    if (find(kept, id) == null) kept.add(new Kept(id, document, entry));
                } catch (Throwable unreadable) {
                    HookStatus.threw(FamilyNames.CHANGE_STICKER_SIZE, UNREADABLE, unreadable);
                }
            }
        }
        accounts.put(user, kept);
        return kept;
    }

    private static void save(Context context, long user, List<Kept> kept) {
        SharedPreferences.Editor edit = store.apply(context).edit();
        if (kept.isEmpty()) {
            edit.remove(String.valueOf(user));
        } else {
            StringBuilder entries = new StringBuilder();
            for (Kept one : kept) {
                if (entries.length() > 0) entries.append(',');
                entries.append(one.entry);
            }
            edit.putString(String.valueOf(user), entries.toString());
        }
        edit.apply();
    }

    private static Kept find(List<Kept> kept, long id) {
        for (Kept one : kept) if (one.id == id) return one;
        return null;
    }

    /** Takes the sticker with [id] out of [kept]: whether it was there. */
    private static boolean drop(List<Kept> kept, long id) {
        for (Iterator<Kept> each = kept.iterator(); each.hasNext(); ) {
            if (each.next().id == id) {
                each.remove();
                return true;
            }
        }
        return false;
    }

    /** A kept sticker: its document id, the document, and the document as stored. */
    private static final class Kept {
        final long id;
        final Object document;
        final String entry;

        Kept(long id, Object document, String entry) {
            this.id = id;
            this.document = document;
            this.entry = entry;
        }
    }

    interface Telegram {
        long account(Object controller);

        Object favoritesOf(Object controller);

        long documentId(Object document);

        byte[] serialized(Object document);

        Object deserialized(byte[] data);

        Object stickerSet(Object document) throws ReflectiveOperationException;

        long requestedId(Object args) throws ReflectiveOperationException;
    }

    private static final class Stubs implements Telegram {
        @Override public long account(Object controller) { return KeptFavorites.account(controller); }
        @Override public Object favoritesOf(Object controller) { return KeptFavorites.favoritesOf(controller); }
        @Override public long documentId(Object document) { return KeptFavorites.documentId(document); }
        @Override public byte[] serialized(Object document) { return KeptFavorites.serialized(document); }
        @Override public Object deserialized(byte[] data) { return KeptFavorites.deserialized(data); }
        @Override public Object stickerSet(Object document) throws ReflectiveOperationException { return packOf(document); }
        @Override public long requestedId(Object args) throws ReflectiveOperationException { return askedFor(args); }
    }

    /**
     * The sticker pack [document] belongs to: the first of its attributes naming one, an
     * InputStickerSet, or null when it names none or the empty one.
     */
    static Object packOf(Object document) throws ReflectiveOperationException {
        Object attributes = valueOf(document, "attributes");
        if (!(attributes instanceof List)) return null;
        for (Object attribute : (List<?>) attributes) {
            if (attribute == null) continue;
            Object pack = valueOf(attribute, "stickerset");
            if (pack != null && !pack.getClass().getSimpleName().endsWith("inputStickerSetEmpty")) return pack;
        }
        return null;
    }

    /**
     * The document id of what a reference request asks about, or 0: a file location's id, or the
     * id of the document a request (sending, faving, saving as recent) names, directly or through
     * its media.
     */
    static long askedFor(Object args) throws ReflectiveOperationException {
        if (!(args instanceof Object[]) || ((Object[]) args).length == 0 || ((Object[]) args)[0] == null) return 0;
        Object asked = ((Object[]) args)[0];
        Object media = valueOf(asked, "media");
        if (media != null) asked = media;
        Object id = valueOf(asked, "id");
        if (id instanceof Long) return (Long) id;
        if (id == null) return 0;
        Object inner = valueOf(id, "id");
        return inner instanceof Long ? (Long) inner : 0;
    }

    /** [owner]'s public field [name], or null when it has none. */
    private static Object valueOf(Object owner, String name) throws IllegalAccessException {
        Field field;
        try {
            field = HostNames.field(owner.getClass(), name);
        } catch (NoSuchFieldException none) {
            return null;
        }
        return field.get(owner);
    }

    // Stubs. The patch gives each one Telegram's code; until then they do nothing.

    /** {@code controller.getUserConfig().getClientUserId()}, for any of the account's controllers. */
    public static long account(Object controller) { return 0; }

    /** {@code ((MediaDataController) controller).recentStickers[TYPE_FAVE]}. */
    public static Object favoritesOf(Object controller) { return null; }

    /** {@code ((TLRPC.Document) document).id}. */
    public static long documentId(Object document) { return 0; }

    /** The document written out the way Telegram stores it, through SerializedData. */
    public static byte[] serialized(Object document) { return null; }

    /** A TLRPC.Document read back from what {@link #serialized} wrote, or null. */
    public static Object deserialized(byte[] data) { return null; }
}
