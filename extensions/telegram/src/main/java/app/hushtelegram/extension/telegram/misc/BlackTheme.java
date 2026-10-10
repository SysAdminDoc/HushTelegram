/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.util.SparseIntArray;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.PatchFamily;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Telegram reads a theme's colors from a key=value file, the built-in ones from its assets, and
 * hands each set here before using it. With the switch on, a dark built-in theme (Night, or Dark,
 * which was Tinted Night) gets pure black for its screens and bars, and for a plain chat background. Bubbles, menus,
 * dialogs and text keep the theme's colors. A patterned chat background takes its colors from the theme's accent
 * rather than from this file, so it's drawn over black instead, with the accent's colors showing through the pattern.
 *
 * <p>A theme installed from a file keeps its own colors: the theme editor and a new chat background
 * can save the colors in use back to that file, and black written there would outlast the switch.
 *
 * <p>Telegram keeps the colors it loaded until it applies a theme again, so a change shows after a
 * restart. Its accent colors are worked out from these, and they leave black as it is.
 *
 * <p>The launch screen Android shows before Telegram runs follows too, on Android 13 and up: see
 * {@link #launchScreen}.
 */
public final class BlackTheme {
    private BlackTheme() {}

    static final int BLACK = 0xFF000000;

    /**
     * Android's own black theme, which as a launch screen is black with Telegram's icon on it. It
     * ships with Android, so the patch adds nothing to Telegram's resources.
     */
    static final int LAUNCH_SCREEN = android.R.style.Theme_Black_NoTitleBar;
    private static final String LAUNCH_PREFS = "hushtelegram_launch_screen";
    private static final String LAUNCH_THEME = "theme";

    /** Names the launch screen for Telegram's next start; a test swaps it to watch what's asked. */
    interface LaunchScreens {
        void use(Activity activity, int theme);
    }

    static volatile LaunchScreens launchScreens = new LaunchScreens() {
        @Override
        public void use(Activity activity, int theme) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.getSplashScreen().setSplashScreenTheme(theme);
        }
    };

    /** The surfaces that turn black, as theme files name them. The first decides whether a theme is dark. */
    static final String[] SURFACES = {
            "windowBackgroundWhite", "windowBackgroundGray", "graySection", "windowBackgroundUnchecked",
            "actionBarDefault", "actionBarDefaultArchived", "avatar_backgroundActionBarBlue",
            "chats_menuBackground", "chats_menuTopBackgroundCats", "chat_wallpaper",
            "chat_messagePanelBackground", "chat_emojiPanelBackground", "chat_topPanelBackground",
            "inappPlayerBackground", "glass_targetMainTabs", "glass_targetMainTopPanel",
    };

    /** Telegram's ids for {@link #SURFACES}, looked up once. Package-visible so a test can set them. */
    static volatile int[] ids;

    /**
     * Called with each color set Telegram reads from a theme file, before Telegram uses it.
     *
     * @param asset the built-in theme's asset, such as night.attheme, or null for a theme file
     * @param colors Telegram's color ids mapped to colors, changed in place
     */
    public static void loaded(String asset, SparseIntArray colors) {
        if (asset == null || colors == null) return;
        HookStatus.invoked(FamilyNames.AMOLED_BLACK);
        try {
            if (!Utils.settingsReady() || !Settings.AMOLED_BLACK.get()) return;
            if (blacken(colors, ids()) > 0) HookStatus.counted(FamilyNames.AMOLED_BLACK, "dark theme turned black");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "black theme", failure);
        }
    }

    /**
     * Called before Telegram draws the chat background, with the pattern's strength. Telegram draws a
     * built-in theme's pattern on the accent's colors, and a dark cloud wallpaper's pattern over black
     * with the colors showing through it, which it asks for with a negative strength. With the switch
     * on, the theme's own pattern on a theme this switch turned black is drawn the second way.
     *
     * @param colors the colors in use, with the accent applied
     * @param picked a wallpaper picked for the theme, which keeps Telegram's drawing, or null
     * @param intensity the pattern's strength, negative when Telegram already draws it over black
     * @return the strength to draw with
     */
    public static int patternIntensity(SparseIntArray colors, Object picked, int intensity) {
        HookStatus.invoked(FamilyNames.AMOLED_BLACK);
        if (picked != null || colors == null || intensity <= 0) return intensity;
        try {
            if (!Utils.settingsReady() || !Settings.AMOLED_BLACK.get() || !black(colors, ids())) return intensity;
            HookStatus.counted(FamilyNames.AMOLED_BLACK, "pattern drawn over black");
            return -intensity;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "black pattern", failure);
            return intensity;
        }
    }

    /**
     * Called as Telegram's main screen is created and whenever one of its screens goes to the
     * background. Android draws the launch screen from a theme before Telegram runs, and Telegram's
     * is dark blue while the phone is in dark mode. From Android 13 an app can name another theme
     * for its next start, so while this patch is in, its switch is on and the phone is in dark mode,
     * that's {@link #LAUNCH_SCREEN}, and otherwise Telegram's own again. Android keeps the choice
     * and rewrites a file each time it's asked, so it's asked only when the choice changes.
     */
    public static void launchScreen(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            if (!Utils.settingsReady()) return;
            boolean black = PatchFamily.AMOLED_BLACK.inBuild() && Settings.AMOLED_BLACK.get() && darkMode();
            int theme = black ? LAUNCH_SCREEN : 0;
            SharedPreferences saved = activity.getSharedPreferences(LAUNCH_PREFS, Context.MODE_PRIVATE);
            // With nothing saved it asks once anyway, so a black launch screen left from before
            // Telegram's data was cleared goes too.
            if (saved.contains(LAUNCH_THEME) && saved.getInt(LAUNCH_THEME, 0) == theme) return;
            launchScreens.use(activity, theme);
            saved.edit().putInt(LAUNCH_THEME, theme).apply();
            if (black) HookStatus.counted(FamilyNames.AMOLED_BLACK, "launch screen turned black");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "black launch screen", failure);
        }
    }

    /** Whether the phone is in dark mode, which is when Telegram's launch screen is the dark one. */
    private static boolean darkMode() {
        return (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Whether the set's screens are the black this switch puts there. */
    static boolean black(SparseIntArray colors, int[] surfaces) {
        int window = surfaces[0] < 0 ? -1 : colors.indexOfKey(surfaces[0]);
        return window >= 0 && colors.valueAt(window) == BLACK;
    }

    /** Turns the surfaces the set has black when it's dark, and answers how many it changed. */
    static int blacken(SparseIntArray colors, int[] surfaces) {
        int window = surfaces[0] < 0 ? -1 : colors.indexOfKey(surfaces[0]);
        if (window < 0 || !dark(colors.valueAt(window))) return 0;
        int changed = 0;
        for (int id : surfaces) {
            // A key the theme leaves out falls back to another one, which stays Telegram's call.
            if (id < 0 || colors.indexOfKey(id) < 0) continue;
            colors.put(id, BLACK);
            changed++;
        }
        return changed;
    }

    /** An opaque color under a quarter of full brightness. */
    static boolean dark(int color) {
        if ((color >>> 24) != 0xFF) return false;
        int red = (color >> 16) & 0xFF, green = (color >> 8) & 0xFF, blue = color & 0xFF;
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue < 64;
    }

    private static int[] ids() {
        int[] known = ids;
        if (known != null) return known;
        int[] found = new int[SURFACES.length];
        for (int i = 0; i < SURFACES.length; i++) {
            found[i] = keyId(SURFACES[i]);
            if (found[i] < 0) HookStatus.missingMember(FamilyNames.AMOLED_BLACK, "theme key", "Theme", SURFACES[i]);
        }
        ids = found;
        return found;
    }

    /** Telegram's id for a theme key, or -1. Replaced with Telegram's own lookup when patching. */
    public static int keyId(String key) { return -1; }
}
