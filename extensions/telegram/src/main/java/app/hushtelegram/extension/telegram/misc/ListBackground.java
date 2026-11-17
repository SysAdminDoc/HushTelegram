/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * A picture from the gallery behind Telegram's list pages (#88): the chat list, Settings, profiles,
 * contacts, calls and the rest, dimmed with the theme's own page color so text stays readable in
 * day and night themes.
 *
 * <p>Telegram paints its page colors fully opaque whatever the theme says, so they can't simply go
 * see-through. Instead, while the picture shows, the three page colors Telegram's getColor hands
 * out come back one step off in the lowest bit of two channels, a different pair for each, which
 * no eye tells apart. The two holders the main screen slides its screens in each get a GPU effect
 * that finds exactly those colors in what the screens drew and lays the picture under them. Text,
 * icons, bars and anything else keep their pixels, and sheets, dialogs, menus and other windows,
 * which have no effect, draw the marked colors as the solid colors they look like. A chat paints
 * its own wallpaper, so it stays as it was. The effect needs Android 13's runtime shaders, so older
 * phones don't offer the switch.
 */
public final class ListBackground {
    private ListBackground() {}

    /** The page fills that show the picture, as theme files name them. */
    static final String[] PAGES = {"windowBackgroundWhite", "windowBackgroundGray", "graySection"};

    /**
     * What each of {@link #PAGES} is XORed with while the picture shows: one step in the lowest bit of
     * two channels, a different pair for each page, so each is found on its own.
     */
    static final int[] CODES = {0x010001, 0x000101, 0x010100};

    /**
     * How much of its own color each of {@link #PAGES} keeps over the picture, out of 255: rows and
     * cards stay a light tint, the gray between them less, and section headers more.
     */
    static final int[] KEEP = {0x66, 0x26, 0x80};

    /** The dims offered, in percent of the theme's page color laid over the picture. */
    public static final int[] DIM_STEPS = {20, 40, 60, 80};

    /** Whether [dim] is one of {@link #DIM_STEPS}, the only dims its row saves. */
    public static boolean dimOffered(int dim) {
        for (int step : DIM_STEPS) if (step == dim) return true;
        return false;
    }

    /**
     * Finds the marked page colors in what a holder drew and lays the picture under them. A pixel
     * counts only with at least two of its four neighbors the same color, so a stray pixel of that
     * color in a photo stays as it is. Under it goes the picture, then the plain page color at the
     * dim, then the page color itself at what it keeps. The page colors are plain uniforms, the
     * bytes the window draws them as, since not every shader compiler takes layout(color).
     */
    static final String KEYER = ""
            + "uniform shader content;\n"
            + "uniform shader picture;\n"
            + "uniform half4 page0;\n"
            + "uniform half4 page1;\n"
            + "uniform half4 page2;\n"
            + "uniform half dim;\n"
            + "uniform half3 keep;\n"
            + "bool near(half4 a, half4 b) {\n"
            + "    half4 d = abs(a - b);\n"
            + "    return max(max(d.r, d.g), max(d.b, d.a)) < 0.00196;\n"
            + "}\n"
            + "bool keyed(half4 c, half4 e, half4 w, half4 s, half4 n, half4 page) {\n"
            + "    if (!near(c, page)) { return false; }\n"
            + "    half count = (near(e, page) ? 1.0 : 0.0) + (near(w, page) ? 1.0 : 0.0)\n"
            + "            + (near(s, page) ? 1.0 : 0.0) + (near(n, page) ? 1.0 : 0.0);\n"
            + "    return count > 1.5;\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "    half4 c = content.eval(p);\n"
            + "    if (!near(c, page0) && !near(c, page1) && !near(c, page2)) { return c; }\n"
            + "    half4 e = content.eval(p + float2(1.0, 0.0));\n"
            + "    half4 w = content.eval(p - float2(1.0, 0.0));\n"
            + "    half4 s = content.eval(p + float2(0.0, 1.0));\n"
            + "    half4 n = content.eval(p - float2(0.0, 1.0));\n"
            + "    half k = -1.0;\n"
            + "    if (keyed(c, e, w, s, n, page0)) { k = keep.x; }\n"
            + "    else if (keyed(c, e, w, s, n, page1)) { k = keep.y; }\n"
            + "    else if (keyed(c, e, w, s, n, page2)) { k = keep.z; }\n"
            + "    if (k < 0.0) { return c; }\n"
            + "    return mix(mix(picture.eval(p), page0, dim), c, k);\n"
            + "}\n";

    /** Telegram's ids for {@link #PAGES}, looked up once. Package-visible so a test can set them. */
    static volatile int[] ids;

    /** {@link #PAGES}' ids while the picture shows, or null: all {@link #mark} reads. */
    private static volatile int[] marking;

    /** The last color Telegram answered for each of {@link #PAGES}, before its marker. */
    private static final int[] SEEN = new int[PAGES.length];

    /** Whether a refit is already on its way to the main thread. */
    private static volatile boolean refitPosted;

    /** The page layouts Telegram made, so a change reaches them. Main thread only. */
    private static final List<WeakReference<Object>> LAYOUTS = new ArrayList<>();

    /** The holders that have an effect from here, and the holders being watched for a new size. Main thread only. */
    private static final Set<View> DRESSED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> WATCHED = Collections.newSetFromMap(new WeakHashMap<>());

    /** The picture as the holders show it. Main thread only. */
    private static final SavedPicture.Shown SHOWN = new SavedPicture.Shown(SavedPicture.LIST_BACKGROUND,
            ListBackground::refitAll, ListBackground::changed);

    /** Whether this phone can show the picture: finding the page colors takes Android 13's runtime shaders. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * Called with every color Telegram's getColor answers from the colors in use, with its key.
     * While the picture shows, a page color comes back with its marker and everything else as it
     * was. It runs for every color on every draw, so it reads one field and nothing else.
     */
    public static int mark(int key, int color) {
        int[] pages = marking;
        if (pages == null) return color;
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] != key) continue;
            if (SEEN[i] != color) seen(i, color);
            return color ^ CODES[i];
        }
        return color;
    }

    /** A page color Telegram answers differently now, after a theme change: the effects take it on. */
    private static void seen(int page, int color) {
        SEEN[page] = color;
        if (refitPosted) return;
        refitPosted = true;
        try {
            HookStatus.counted(FamilyNames.AMOLED_BLACK, "page colors marked for the picture");
            Utils.runOnMainThread(ListBackground::refitAll);
        } catch (Throwable failure) {
            refitPosted = false;
        }
    }

    /** The color [page] of {@link #PAGES} carries while the picture shows, from the last one Telegram answered. */
    static int marked(int page) {
        return SEEN[page] ^ CODES[page];
    }

    /** Called as Telegram works out the colors in use again: the markers go on or off with the switch. */
    public static void refreshing() {
        HookStatus.invoked(FamilyNames.AMOLED_BLACK);
        try {
            update();
            Utils.runOnMainThreadNowOrLater(ListBackground::refitAll);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "list background colors", failure);
        }
    }

    /** Puts the markers on while the switch is on with a picture saved, and takes them off otherwise. */
    private static void update() {
        marking = active() ? ids() : null;
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
            if (marking == null) update();
            fit(layout);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "list background holders", failure);
        }
    }

    /**
     * Called on the main thread when the switch or the picture changed: the markers go on or off,
     * Telegram works its colors out again, and the screens are built again so they paint with them,
     * the way Telegram applies a new language.
     */
    public static void changed() {
        try {
            SHOWN.forget();
            update();
            recolor();
            prune();
            for (WeakReference<Object> held : new ArrayList<>(LAYOUTS)) {
                Object layout = held.get();
                if (layout != null) rebuild(layout);
            }
            refitAll();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "list background change", failure);
        }
    }

    /** Called when only the dim changed: the holders take it on. */
    public static void redraw() {
        refitAll();
    }

    private static void refitAll() {
        refitPosted = false;
        try {
            prune();
            for (WeakReference<Object> held : new ArrayList<>(LAYOUTS)) {
                Object layout = held.get();
                if (layout != null) fit(layout);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "list background redraw", failure);
        }
    }

    private static void prune() {
        for (Iterator<WeakReference<Object>> it = LAYOUTS.iterator(); it.hasNext(); ) if (it.next().get() == null) it.remove();
    }

    /** Gives [layout]'s two holders the picture, or takes it away. */
    static void fit(Object layout) {
        View[] holders = pages(layout);
        if (holders == null) return;
        for (View holder : holders) if (holder != null) dress(holder);
    }

    /**
     * Gives [holder] the keyer while the markers are on, it's in the main window and the picture is
     * decoded at its size, and takes away only a keyer it gave.
     */
    static void dress(View holder) {
        watch(holder);
        if (!supported()) return;
        RenderEffect effect = null;
        if (marking != null && onScreen(holder)) {
            Bitmap picture = SHOWN.bitmap(holder.getContext(), holder.getWidth(), holder.getHeight());
            if (picture != null) effect = effect(picture, holder.getWidth(), holder.getHeight());
        }
        if (effect != null) {
            holder.setRenderEffect(effect);
            if (DRESSED.add(holder)) HookStatus.counted(FamilyNames.AMOLED_BLACK, "list holders showing the picture");
        } else if (DRESSED.remove(holder)) {
            holder.setRenderEffect(null);
        }
    }

    /** Whether [holder] has the picture from here. */
    static boolean dressed(View holder) {
        return DRESSED.contains(holder);
    }

    /** Fits [holder] again whenever its size changes, since the picture is decoded at its size. */
    private static void watch(View holder) {
        if (!WATCHED.add(holder)) return;
        holder.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop) return;
            try {
                dress(view);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.AMOLED_BLACK, "list background size", failure);
            }
        });
    }

    /** Set once {@link #KEYER} is turned down, so later keyers go straight to {@link #older}. */
    private static volatile boolean olderCompiler;

    /**
     * {@link #KEYER} written for an older shader compiler, one that reads a child as
     * sample(child, p) rather than child.eval(p), like the one under Robolectric.
     */
    static String older(String source) {
        return source.replaceAll("(\\w+)\\.eval\\(", "sample($1, ");
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static RuntimeShader compiled() {
        if (!olderCompiler) {
            try {
                return new RuntimeShader(KEYER);
            } catch (IllegalArgumentException refused) {
                olderCompiler = true;
            }
        }
        return new RuntimeShader(older(KEYER));
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static RenderEffect effect(Bitmap picture, int width, int height) {
        return RenderEffect.createRuntimeShaderEffect(keyer(picture, width, height,
                Math.max(0, Math.min(90, Settings.LIST_BACKGROUND_DIM.get()))), "content");
    }

    /**
     * The keyer for a holder [width] by [height], with [picture] filling it the way a wallpaper does
     * and the theme's page color at [dim] percent over it. Its "content" is what the holder drew.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    static RuntimeShader keyer(Bitmap picture, int width, int height, int dim) {
        RuntimeShader shader = compiled();
        BitmapShader under = new BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (picture.getWidth() != width || picture.getHeight() != height) {
            // A decode at a new size is on its way: the last one, scaled to cover and centered.
            float scale = Math.max(width / (float) picture.getWidth(), height / (float) picture.getHeight());
            Matrix fill = new Matrix();
            fill.setScale(scale, scale);
            fill.postTranslate((width - picture.getWidth() * scale) / 2f, (height - picture.getHeight() * scale) / 2f);
            under.setLocalMatrix(fill);
        }
        shader.setInputShader("picture", under);
        // The array setters throughout: Robolectric drops the ones taking single floats.
        for (int page = 0; page < PAGES.length; page++) {
            int color = marked(page);
            shader.setFloatUniform("page" + page, new float[]{((color >> 16) & 0xff) / 255f,
                    ((color >> 8) & 0xff) / 255f, (color & 0xff) / 255f, (color >>> 24) / 255f});
        }
        shader.setFloatUniform("dim", new float[]{dim / 100f});
        shader.setFloatUniform("keep", new float[]{KEEP[0] / 255f, KEEP[1] / 255f, KEEP[2] / 255f});
        return shader;
    }

    /** Whether [view] is in its activity's own window rather than a sheet's. */
    static boolean onScreen(View view) {
        Activity activity = activityOf(view.getContext());
        Window window = activity == null ? null : activity.getWindow();
        return window != null && view.getRootView() == window.peekDecorView();
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

    /** Has Telegram work out the colors in use again, leaving the chat wallpaper loaded. Replaced when patching. */
    public static void recolor() { }

    /** The two holders [layout] slides its screens in, or null. Replaced when patching. */
    @Nullable
    public static View[] pages(Object layout) { return null; }

    /** Builds [layout]'s screens again, the way a new language does. Replaced when patching. */
    public static void rebuild(Object layout) { }
}
