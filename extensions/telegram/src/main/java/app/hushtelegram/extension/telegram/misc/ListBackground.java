/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.SparseIntArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * A picture from the gallery behind Telegram's list pages (#88): the chat list, Settings, profiles,
 * contacts, calls and the rest. With the switch on and a picture saved, the colors Telegram fills
 * those pages with turn see-through, and each of the two page holders Telegram slides its screens
 * in gets the picture as its background, dimmed with the theme's own page color so text stays
 * readable in day and night themes. Bars, menus, dialogs, the keyboard panel and the chat screen
 * use other colors and stay as they are; a chat draws its own wallpaper over the picture.
 *
 * <p>Only a built-in theme's colors change, Classic included: a theme installed from a file can
 * be saved back to that file by the theme editor, and see-through pages written there would outlast
 * the switch. The colors are changed as Telegram works out the colors in use from the theme's own,
 * so sharing a built-in theme's accent, which reads the theme again, shares Telegram's colors.
 */
public final class ListBackground {
    private ListBackground() {}

    /** The page fills that go see-through, as theme files name them. */
    static final String[] PAGES = {"windowBackgroundWhite", "windowBackgroundGray", "graySection"};

    /**
     * How much of its own opacity each of {@link #PAGES} keeps, out of 255: rows and cards stay a
     * light tint over the picture, the gray between them less, and section headers more.
     */
    static final int[] KEEP = {0x66, 0x26, 0x80};

    /** The dims offered, in percent of the theme's page color laid over the picture. */
    public static final int[] DIM_STEPS = {20, 40, 60, 80};

    /** Whether [dim] is one of {@link #DIM_STEPS}, the only dims its row saves. */
    public static boolean dimOffered(int dim) {
        for (int step : DIM_STEPS) if (step == dim) return true;
        return false;
    }

    /** Telegram's ids for {@link #PAGES}, looked up once. Package-visible so a test can set them. */
    static volatile int[] ids;

    /** Where the theme's own color set comes from; a test swaps it for a set of its own. */
    interface Tables {
        @Nullable SparseIntArray current();
    }

    static volatile Tables tables = ListBackground::table;

    /** The color sets read from a built-in theme's asset, the only ones that change. */
    private static final Set<SparseIntArray> BUILT_IN = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * Each changed set's own page colors and what was written over them. SparseIntArray keeps
     * Object's equality, so the map goes by identity.
     */
    private static final Map<SparseIntArray, Stock> STOCK = Collections.synchronizedMap(new WeakHashMap<>());

    /** The page layouts Telegram made, so a change reaches them. Main thread only. */
    private static final List<WeakReference<Object>> LAYOUTS = new ArrayList<>();

    /** The picture as the pages draw it. Main thread only. */
    private static final SavedPicture.Shown SHOWN = new SavedPicture.Shown(SavedPicture.LIST_BACKGROUND,
            ListBackground::redraw, ListBackground::changed);

    /** One set's page colors from before they turned see-through. */
    static final class Stock {
        final int[] colors;
        final boolean[] had;
        /** What was written in each key's place, so a key Telegram changed since is left alone. */
        final int[] wrote;

        Stock(int size) {
            colors = new int[size];
            had = new boolean[size];
            wrote = new int[size];
        }
    }

    /** Called with each built-in theme's color set as Telegram reads it, from {@link BlackTheme#loaded}. */
    static void builtIn(SparseIntArray colors) {
        if (colors != null) BUILT_IN.add(colors);
    }

    /**
     * Called as Telegram works out the colors in use from the theme's own, before it copies them.
     * With the switch on and a picture saved, the built-in theme's page fills go see-through; with
     * either gone, a set this changed gets its own colors back.
     */
    public static void refreshing() {
        HookStatus.invoked(FamilyNames.AMOLED_BLACK);
        try {
            if (refresh(tables.current(), active(), ids())) {
                HookStatus.counted(FamilyNames.AMOLED_BLACK, "lists turned see-through over the picture");
            }
            Utils.runOnMainThreadNowOrLater(ListBackground::dressAll);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "list background colors", failure);
        }
    }

    /**
     * Applies or takes back the see-through pages on [table], the theme's own set. Answers whether
     * the pages are see-through now. A set read from a file is never changed.
     */
    static boolean refresh(@Nullable SparseIntArray table, boolean on, int[] pages) {
        if (table == null) return false;
        if (on && eligible(table)) {
            apply(table, pages);
            return true;
        }
        restore(table, pages);
        return false;
    }

    /**
     * Whether [table] is a built-in theme's: read from an asset, or Telegram's Classic theme, which has
     * no file and starts from an empty set that every key's default fills in. A set this changed stays one.
     */
    static boolean eligible(SparseIntArray table) {
        return BUILT_IN.contains(table) || STOCK.containsKey(table) || table.size() == 0;
    }

    /** Turns [table]'s page fills see-through, keeping its own colors to put back. */
    static void apply(SparseIntArray table, int[] pages) {
        Stock stock;
        synchronized (STOCK) {
            Stock known = STOCK.get(table);
            stock = new Stock(pages.length);
            for (int i = 0; i < pages.length; i++) {
                int id = pages[i];
                if (id < 0) continue;
                int index = table.indexOfKey(id);
                boolean has = index >= 0;
                int now = has ? table.valueAt(index) : 0;
                if (known != null && has && known.wrote[i] == now) {
                    // Still what this wrote: the theme's own color is the one kept before.
                    stock.colors[i] = known.colors[i];
                    stock.had[i] = known.had[i];
                } else {
                    // A key the theme leaves out takes Telegram's default, so it can go see-through too.
                    stock.colors[i] = has ? now : defaultColor(id);
                    stock.had[i] = has;
                }
                stock.wrote[i] = withAlpha(stock.colors[i], KEEP[i]);
                table.put(id, stock.wrote[i]);
            }
            STOCK.put(table, stock);
        }
    }

    /** Puts [table]'s own page colors back where they're still what {@link #apply} wrote. */
    static void restore(SparseIntArray table, int[] pages) {
        Stock stock = STOCK.remove(table);
        if (stock == null) return;
        for (int i = 0; i < pages.length; i++) {
            int id = pages[i];
            if (id < 0) continue;
            int index = table.indexOfKey(id);
            if (index < 0 || table.valueAt(index) != stock.wrote[i]) continue;
            if (stock.had[i]) table.put(id, stock.colors[i]);
            else table.removeAt(index);
        }
    }

    /** [color] with [keep] out of 255 of its own opacity. */
    static int withAlpha(int color, int keep) {
        int alpha = (color >>> 24) * keep / 255;
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /** The theme's own opaque color for page [index] of {@link #PAGES}, or 0 when it isn't known. */
    static int stockColor(@Nullable SparseIntArray table, int index) {
        if (table == null) return 0;
        Stock stock = STOCK.get(table);
        return stock == null ? 0 : stock.colors[index] | 0xFF000000;
    }

    /** Whether the switch is on and there's a picture. Off while paused, like every switch. */
    static boolean active() {
        return Utils.settingsReady() && Settings.LIST_BACKGROUND.get()
                && SavedPicture.present(Utils.getContext(), SavedPicture.LIST_BACKGROUND);
    }

    /** Called as Telegram makes the holders a page layout slides its screens in. */
    public static void laidOut(Object layout) {
        if (layout == null) return;
        HookStatus.invoked(FamilyNames.AMOLED_BLACK);
        try {
            prune();
            boolean known = false;
            for (WeakReference<Object> held : LAYOUTS) known |= held.get() == layout;
            if (!known) LAYOUTS.add(new WeakReference<>(layout));
            dress(layout, dressed());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "list background holders", failure);
        }
    }

    /**
     * Called on the main thread when the switch, the picture or the dim changed: the colors are
     * worked out again, and the screens are built again so they paint with them, the way Telegram
     * applies a new language.
     */
    public static void changed() {
        try {
            SHOWN.forget();
            recolor();
            prune();
            for (WeakReference<Object> held : new ArrayList<>(LAYOUTS)) {
                Object layout = held.get();
                if (layout != null) rebuild(layout);
            }
            dressAll();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "list background change", failure);
        }
    }

    /** Called when only the dim changed: the pages draw again with it. */
    public static void redraw() {
        try {
            prune();
            for (WeakReference<Object> held : LAYOUTS) {
                View[] pages = held.get() == null ? null : pages(held.get());
                if (pages == null) continue;
                for (View page : pages) if (page != null) page.invalidate();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "list background redraw", failure);
        }
    }

    /** Whether the holders get the picture: on, and the theme's pages turned see-through for it. */
    private static boolean dressed() {
        return active() && STOCK.containsKey(tables.current());
    }

    private static void dressAll() {
        prune();
        boolean on = dressed();
        for (WeakReference<Object> held : LAYOUTS) {
            Object layout = held.get();
            if (layout != null) dress(layout, on);
        }
    }

    private static void prune() {
        for (Iterator<WeakReference<Object>> it = LAYOUTS.iterator(); it.hasNext(); ) if (it.next().get() == null) it.remove();
    }

    /** Gives [layout]'s page holders the picture, or gives them back what they had. */
    static void dress(Object layout, boolean on) {
        View[] pages = pages(layout);
        if (pages == null) return;
        for (View page : pages) {
            if (page == null) continue;
            Drawable now = page.getBackground();
            boolean ours = now instanceof Backdrop;
            if (on && ours) {
                now.invalidateSelf();
            } else if (on) {
                page.setBackground(new Backdrop(page, now));
            } else if (ours) {
                page.setBackground(((Backdrop) now).before);
            }
        }
    }

    /** The activity a view's context belongs to, or null. */
    @Nullable
    static Activity activityOf(@Nullable Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /**
     * A page holder's background: the picture, dimmed with the theme's page color, under the
     * screens it holds. A holder in a sheet over the screen gets the page color alone, so the
     * sheet stays solid.
     */
    static final class Backdrop extends Drawable {
        @Nullable final Drawable before;
        private final WeakReference<View> host;
        private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint fill = new Paint();
        private final Rect area = new Rect();

        Backdrop(View host, @Nullable Drawable before) {
            this.host = new WeakReference<>(host);
            this.before = before;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            View view = host.get();
            if (view == null || !covered(view, area)) return;
            SparseIntArray table = tables.current();
            int page = stockColor(table, 0);
            if (page == 0) return;
            Bitmap bitmap = onScreen(view) ? SHOWN.bitmap(view.getContext(), getBounds().width(), getBounds().height()) : null;
            if (bitmap == null) {
                // A sheet, or the picture still on its way: the page as Telegram paints it.
                fill.setColor(page);
                canvas.drawRect(area, fill);
                return;
            }
            canvas.save();
            canvas.clipRect(area);
            SavedPicture.drawFilling(canvas, bitmap, getBounds(), picture);
            int dim = Math.max(0, Math.min(90, Settings.LIST_BACKGROUND_DIM.get()));
            fill.setColor(page & 0x00FFFFFF | (dim * 255 / 100) << 24);
            canvas.drawRect(area, fill);
            canvas.restore();
        }

        /**
         * The part of [view] its visible screens cover, in its own coordinates. An empty holder draws
         * nothing, and a screen shown as a preview card gets the picture only behind the card.
         */
        static boolean covered(View view, Rect out) {
            out.setEmpty();
            if (!(view instanceof ViewGroup)) return false;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                int left = child.getLeft() + Math.round(child.getTranslationX());
                int top = child.getTop() + Math.round(child.getTranslationY());
                out.union(left, top, left + child.getWidth(), top + child.getHeight());
            }
            return out.intersect(0, 0, view.getWidth(), view.getHeight());
        }

        /** Whether [view] is in its activity's own window rather than a sheet's. */
        static boolean onScreen(View view) {
            Activity activity = activityOf(view.getContext());
            Window window = activity == null ? null : activity.getWindow();
            return window != null && view.getRootView() == window.peekDecorView();
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(@Nullable ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static int[] ids() {
        int[] known = ids;
        if (known != null) return known;
        int[] found = new int[PAGES.length];
        for (int i = 0; i < PAGES.length; i++) {
            found[i] = BlackTheme.keyId(PAGES[i]);
            if (found[i] < 0) HookStatus.missingMember(FamilyNames.AMOLED_BLACK, "theme key", "Theme", PAGES[i]);
        }
        ids = found;
        return found;
    }

    /** The theme's own color set, the one the colors in use are copied from. Replaced when patching. */
    @Nullable
    public static SparseIntArray table() { return null; }

    /** Has Telegram work out the colors in use again, leaving the chat wallpaper loaded. Replaced when patching. */
    public static void recolor() { }

    /** Telegram's default for a color key a theme leaves out. Replaced when patching. */
    public static int defaultColor(int key) { return 0; }

    /** The two holders [layout] slides its screens in, or null. Replaced when patching. */
    @Nullable
    public static View[] pages(Object layout) { return null; }

    /** Builds [layout]'s screens again, the way a new language does. Replaced when patching. */
    public static void rebuild(Object layout) { }
}
