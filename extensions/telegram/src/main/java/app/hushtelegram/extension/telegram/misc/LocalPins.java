/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;
import android.util.SparseArray;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.PatchFamily;
import app.hushtelegram.extension.telegram.settings.Settings;
import app.hushtelegram.extension.telegram.settings.SettingsLock;

/**
 * Pin chats on this phone (#79): the chat list's long press menu offers Pin on this phone, and the
 * chats picked that way sit right under Telegram's own pinned chats in All Chats, newest message
 * first among themselves.
 *
 * <p>Nothing goes to Telegram. The server's pins, its pin limit and the other devices stay as they
 * are, and the archive and the folder tabs keep Telegram's order. The picks are dialog ids, the same
 * peer on every account, so one list in the settings covers them all and the settings file carries
 * it without any account's own id. The order runs inside the hidden chats' sort hook, after the
 * date order, and moves only chats that aren't pinned or the archive's row.
 *
 * <p>Those chats can also be dragged into an order of your own (#108), the way Telegram lets you
 * drag its pinned chats once one is selected. The drag goes through Telegram's own move, which is
 * offered here for a chat pinned on this phone in All Chats and lets it land only on another one,
 * so Telegram's pins and the ones here never trade places. Once it lands, the order is saved in
 * {@link Settings#LOCAL_PINS_ORDER} and Telegram isn't told: its own pin order didn't change.
 */
public final class LocalPins {
    private LocalPins() {}

    /** What the diagnostic report counts each sort that put the picked chats up top as. */
    static final String SORTED = "chats pinned on this phone";

    /** What it counts each drop of a chat pinned on this phone as. */
    static final String DRAGGED = "chat pinned on this phone dragged";

    /**
     * Whether the chat Telegram last offered to drag is one pinned on this phone. Telegram asks for
     * the chat a drag starts on before it moves anything, so each drop reads what this drag started
     * on. Main thread only, like the chat list.
     */
    private static boolean draggingLocal;

    /** The most chats one phone keeps pinned, so the setting stays short. */
    public static final int MAX_PINS = 20;

    /** The long press menu's Pin on this phone and Unpin on this phone items, after Record calls here's id. */
    static final int PIN_ID = 0x48544805;
    static final int UNPIN_ID = 0x48544806;

    /** The chat ids a long press picked on a chat list, through the stub below. A test answers it itself. */
    static Function<Object, List<?>> selection = LocalPins::selectedOf;

    /** Each chat list's two items, so they can follow the switch and the selection. Main thread only. */
    private static final Map<Object, WeakReference<View>> PIN_ITEMS = new WeakHashMap<>();
    private static final Map<Object, WeakReference<View>> UNPIN_ITEMS = new WeakHashMap<>();

    /**
     * The picked chats in [text], comma-separated dialog ids as {@link #join} writes them, or null
     * for anything else: a zero, a repeat, a sign or padding a write never puts there, or more than
     * {@link #MAX_PINS}.
     */
    @Nullable
    static Set<Long> parse(@Nullable String text) {
        if (text == null) return null;
        Set<Long> ids = new LinkedHashSet<>();
        if (text.isEmpty()) return ids;
        for (String part : text.split(",", -1)) {
            long id;
            try {
                id = Long.parseLong(part);
            } catch (NumberFormatException notAnId) {
                return null;
            }
            if (id == 0 || !String.valueOf(id).equals(part) || !ids.add(id)) return null;
        }
        return ids.size() > MAX_PINS ? null : ids;
    }

    /** Whether [text] is one this would save, for a settings file's value. */
    public static boolean allowed(@Nullable String text) {
        return parse(text) != null;
    }

    /** How many chats [text] pins, none for a value this never wrote. */
    public static int count(@Nullable String text) {
        Set<Long> ids = parse(text);
        return ids == null ? 0 : ids.size();
    }

    static String join(Set<Long> ids) {
        StringBuilder text = new StringBuilder();
        for (long id : ids) {
            if (text.length() > 0) text.append(',');
            text.append(id);
        }
        return text.toString();
    }

    /** The picked chats, none when the setting can't be read or holds something this never wrote. */
    static Set<Long> pins() {
        Set<Long> ids = parse(Settings.LOCAL_PINNED_CHATS.get());
        return ids == null ? Collections.emptySet() : ids;
    }

    /**
     * The order the picked chats were dragged into, first on top, keeping only chats still picked.
     * Empty when nothing was dragged, or the setting holds something this never wrote.
     */
    static Set<Long> order() {
        Set<Long> ids = parse(Settings.LOCAL_PINS_ORDER.get());
        if (ids == null) return Collections.emptySet();
        ids.retainAll(pins());
        return ids;
    }

    /** Whether the switch is on and this build carries the menu's selection read, which pause turns off. */
    private static boolean ready() {
        return Utils.settingsReady() && Settings.LOCAL_PINS.get() && PatchFamily.Capability.LOCAL_PINS.installed();
    }

    /**
     * Called from {@link HiddenChats#prune} after the date order: moves the picked chats in All
     * Chats to the first places after the pinned chats, in the order they were dragged into, or
     * newest first, or does nothing while the switch is off or nothing is picked.
     */
    @SuppressWarnings("unchecked")
    static void sort(Object controller) {
        try {
            if (!Utils.settingsReady() || !Settings.LOCAL_PINS.get()) return;
            Set<Long> pins = pins();
            if (pins.isEmpty() || !PatchFamily.Capability.LOCAL_PINS.installed()) return;
            HiddenChats.Telegram chats = HiddenChats.telegram;
            SparseArray<?> folders = chats.folders(controller);
            // Folder 0 is All Chats; the archive and the folder tabs keep their own order.
            Object all = folders == null ? null : folders.get(0);
            if (!(all instanceof List)) return;
            ChatSort.Telegram dates = ChatSort.telegram;
            Comparator<Object> newestFirst = Comparator.<Object>comparingInt(dates::lastDate).reversed();
            leadInOrder((List<Object>) all, dialog -> chats.pinned(dialog) || dates.isFolder(dialog),
                    dialog -> pins.contains(chats.dialogId(dialog)), dragged(order(), chats::dialogId, newestFirst));
            HookStatus.counted(FamilyNames.HIDE_FOLDER_COUNTERS, SORTED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, SORTED, failure);
        }
    }

    /**
     * The picked chats' order: the ones in [order] as they sit there, and above them the ones
     * picked since the last drag, by [unordered]. With nothing dragged, just [unordered].
     */
    static <T> Comparator<T> dragged(Set<Long> order, ToLongFunction<T> id, Comparator<T> unordered) {
        if (order.isEmpty()) return unordered;
        Map<Long, Integer> rank = new HashMap<>();
        for (long each : order) rank.put(each, rank.size());
        return (first, second) -> {
            Integer a = rank.get(id.applyAsLong(first));
            Integer b = rank.get(id.applyAsLong(second));
            if (a == null || b == null) return a != null ? 1 : b != null ? -1 : unordered.compare(first, second);
            return Integer.compare(a, b);
        };
    }

    /**
     * Puts the [pinned] chats that aren't [fixed] first, newest first, into the places the chats
     * that aren't [fixed] took, with the others after them in the order they had. A [fixed] chat
     * or a null never moves.
     */
    static <T> void lead(List<T> list, Predicate<T> fixed, Predicate<T> pinned, ToIntFunction<T> date) {
        leadInOrder(list, fixed, pinned, Comparator.comparingInt(date).reversed());
    }

    /** {@link #lead}, with the [pinned] chats put in [order] instead of newest first. */
    static <T> void leadInOrder(List<T> list, Predicate<T> fixed, Predicate<T> pinned, Comparator<T> order) {
        List<Integer> slots = new ArrayList<>();
        List<T> up = new ArrayList<>();
        List<T> rest = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            T each = list.get(i);
            if (each == null || fixed.test(each)) continue;
            slots.add(i);
            (pinned.test(each) ? up : rest).add(each);
        }
        if (up.isEmpty()) return;
        up.sort(order);
        up.addAll(rest);
        for (int i = 0; i < slots.size(); i++) list.set(slots.get(i), up.get(i));
    }

    /** Called by {@link HiddenChats#menuItem} after Record calls here: adds Pin on this phone and Unpin on this phone. */
    static void menuItem(Object list, Object more) {
        try {
            Context context = Utils.getContext();
            View pin = item(context, more, PIN_ID, "msg_pin", L10n.t("Pin on this phone"));
            View unpin = item(context, more, UNPIN_ID, "msg_unpin", L10n.t("Unpin on this phone"));
            if (pin != null) PIN_ITEMS.put(list, new WeakReference<>(pin));
            if (unpin != null) UNPIN_ITEMS.put(list, new WeakReference<>(unpin));
            menuShown(list);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, "pin on this phone item", failure);
        }
    }

    @Nullable
    private static View item(@Nullable Context context, Object more, int id, String icon, String text) {
        int drawable = context == null ? 0 : context.getResources().getIdentifier(icon, "drawable", context.getPackageName());
        Object item = HiddenChats.telegram.addMenuItem(more, id, drawable, text);
        return item instanceof View ? (View) item : null;
    }

    /**
     * Called by {@link HiddenChats#menuShown} on each change of selection: with the switch on,
     * Unpin on this phone shows when every selected chat is pinned here and Pin on this phone shows
     * otherwise. With it off, or paused, neither shows.
     */
    static void menuShown(Object list) {
        View pin = held(PIN_ITEMS, list);
        View unpin = held(UNPIN_ITEMS, list);
        if (pin == null && unpin == null) return;
        boolean offered = false;
        boolean unpinning = false;
        try {
            if (ready()) {
                Set<Long> picked = picked(selection.apply(list));
                unpinning = !picked.isEmpty() && pins().containsAll(picked);
                offered = true;
            }
        } catch (Throwable failure) {
            offered = false;
            HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, "pin on this phone item", failure);
        }
        if (pin != null) pin.setVisibility(offered && !unpinning ? View.VISIBLE : View.GONE);
        if (unpin != null) unpin.setVisibility(offered && unpinning ? View.VISIBLE : View.GONE);
    }

    @Nullable
    private static View held(Map<Object, WeakReference<View>> items, Object list) {
        WeakReference<View> held = items.get(list);
        return held == null ? null : held.get();
    }

    private static Set<Long> picked(@Nullable List<?> selected) {
        Set<Long> picked = new LinkedHashSet<>();
        for (int i = 0; selected != null && i < selected.size(); i++) {
            Object chat = selected.get(i);
            if (chat instanceof Long && (Long) chat != 0) picked.add((Long) chat);
        }
        return picked;
    }

    /**
     * Called by {@link HiddenChats#menuChoice} for a tap on either item: the selected chats join
     * the ones pinned here, or leave them, every signed-in account's list sorts again, and Telegram
     * gets its back arrow, which closes the selection.
     */
    static int menuChoice(Object listener, int id) {
        try {
            if (!ready() || SettingsLock.refuses()) return id;
            Set<Long> picked = picked(HiddenChats.telegram.selectedChats(listener));
            if (picked.isEmpty()) return HiddenChats.CLOSE_SELECTION;
            boolean pinning = id == PIN_ID;
            // Read and written in one queued task, so two quick taps don't drop each other's chats.
            boolean queued = Utils.runInOrderOnBackgroundThread(() -> {
                Set<Long> pins = new LinkedHashSet<>(pins());
                if (pinning) pins.addAll(picked);
                else pins.removeAll(picked);
                boolean full = pins.size() > MAX_PINS;
                boolean saved = !full && Settings.LOCAL_PINNED_CHATS.saveCommitted(join(pins));
                // A chat unpinned here leaves the dragged order too, so pinning it again puts it on top.
                if (saved && !pinning) forget(picked);
                Utils.runOnMainThread(() -> {
                    if (full) {
                        Utils.showToastLong(L10n.f("You can pin up to %1$d chats on this phone.", MAX_PINS));
                    } else if (!saved) {
                        Utils.showToastLong(L10n.t("Couldn't save the chats. Try again in a moment."));
                    } else {
                        HiddenChats.refreshAll();
                        Utils.showToastShort(pinning ? L10n.t("Pinned in All Chats on this phone.") : L10n.t("Unpinned on this phone."));
                    }
                });
            });
            if (!queued) Utils.showToastLong(L10n.t("Couldn't save the chats. Try again in a moment."));
            return HiddenChats.CLOSE_SELECTION;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, "pin on this phone", failure);
            return id;
        }
    }

    /** Takes [chats] out of the dragged order, keeping the others where they were. */
    private static void forget(Set<Long> chats) {
        Set<Long> order = parse(Settings.LOCAL_PINS_ORDER.get());
        if (order == null || !order.removeAll(chats)) return;
        Settings.LOCAL_PINS_ORDER.save(join(order));
    }

    /**
     * Whether a chat pinned on this phone can be dragged or dropped on: the switch is on and this
     * build has the drag hooks, it sits in All Chats on the chat list's first page (not a folder tab
     * or the archive), and Telegram itself doesn't have it pinned.
     */
    static boolean movable(Object activity, @Nullable Object dialog) {
        if (dialog == null || !ready() || !PatchFamily.Capability.LOCAL_PIN_ORDER.installed()) return false;
        HiddenChats.Telegram chats = HiddenChats.telegram;
        if (chats.pinned(dialog) || !pins().contains(chats.dialogId(dialog))) return false;
        if (drag.page(activity) != 0) return false;
        List<?> all = chats.dialogs(chats.selectedAccount(), 0);
        return all != null && all.contains(dialog);
    }

    /**
     * Replaces the chat list's question of whether [dialog], under a finger while chats are
     * selected, can be dragged: Telegram's answer for its own pins, and yes for a chat pinned on
     * this phone. Notes which kind this drag is, for {@link #dropOn}.
     */
    public static boolean dragFrom(Object activity, Object dialog) {
        HookStatus.invoked(FamilyNames.HIDE_FOLDER_COUNTERS);
        draggingLocal = false;
        if (drag.pinned(activity, dialog)) return true;
        try {
            draggingLocal = movable(activity, dialog);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, DRAGGED, failure);
        }
        return draggingLocal;
    }

    /**
     * Replaces the chat list's question of whether the dragged chat may take [dialog]'s place:
     * Telegram's answer when one of its own pins is dragged, and for a chat pinned on this phone
     * only another one, so the two kinds never mix.
     */
    public static boolean dropOn(Object activity, Object dialog) {
        if (!draggingLocal) return drag.pinned(activity, dialog);
        try {
            return movable(activity, dialog);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, DRAGGED, failure);
            return false;
        }
    }

    /**
     * Replaces the chat list's note that its pins moved, which sends Telegram their new order when
     * the selection closes. A chat pinned on this phone saves the new order here instead, and
     * Telegram isn't told, since none of its own pins moved.
     */
    public static void moved(Object activity) {
        if (!draggingLocal) {
            drag.changed(activity);
            return;
        }
        try {
            saveOrder();
            HookStatus.counted(FamilyNames.HIDE_FOLDER_COUNTERS, DRAGGED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, DRAGGED, failure);
        }
    }

    /**
     * Saves the picked chats' order as All Chats shows it now, first on top. Picked chats it
     * doesn't show (another account's, hidden or archived ones) keep their places after them.
     */
    static void saveOrder() {
        HiddenChats.Telegram chats = HiddenChats.telegram;
        List<?> all = chats.dialogs(chats.selectedAccount(), 0);
        Set<Long> pins = pins();
        Set<Long> order = new LinkedHashSet<>();
        for (int i = 0; all != null && i < all.size(); i++) {
            Object dialog = all.get(i);
            if (dialog == null || chats.pinned(dialog)) continue;
            long id = chats.dialogId(dialog);
            if (pins.contains(id)) order.add(id);
        }
        if (order.isEmpty()) return;
        order.addAll(order());
        Settings.LOCAL_PINS_ORDER.save(join(order));
    }

    /** Telegram's side of a drag, through the stubs below. A test answers it itself. */
    interface Drag {
        /** Telegram's own answer: whether [dialog] is one of its pins on the page shown. */
        boolean pinned(Object activity, Object dialog);

        /** The type of the chat list's page shown, 0 for All Chats and the archive. */
        int page(Object activity);

        /** Notes that Telegram's pins moved, so it sends their order when the selection closes. */
        void changed(Object activity);
    }

    static Drag drag = new Drag() {
        @Override public boolean pinned(Object activity, Object dialog) { return stockPinned(activity, dialog); }
        @Override public int page(Object activity) { return shownType(activity); }
        @Override public void changed(Object activity) { markMoved(activity); }
    };

    // Stubs. The patch gives them Telegram's code; until then they read nothing.

    /** {@code DialogsActivity.selectedDialogs}, the chat ids a long press picked on [list]. */
    @Nullable
    public static ArrayList<?> selectedOf(Object list) { return null; }

    /** {@code DialogsActivity.isDialogPinned(dialog)}, Telegram's own answer for a drag. */
    public static boolean stockPinned(Object activity, Object dialog) { return false; }

    /** {@code DialogsActivity.viewPages[0].dialogsType}, 0 for All Chats and the archive. */
    public static int shownType(Object activity) { return -1; }

    /** {@code DialogsActivity.movingWas = true}, so the selection's end sends Telegram its new pin order. */
    public static void markMoved(Object activity) {}
}
