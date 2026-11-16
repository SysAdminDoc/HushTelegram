/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.PatchFamily;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Chat list avatars at a size the person picks. A chat row works out its avatar's square once per
 * layout, and the online dot, the story ring, the unread mark and the archive's pull animation all
 * read that square when they draw. With the switch on, the square shrinks or grows around its own
 * center, so all of them follow, and the avatar's corner radius is scaled with it so a round
 * picture stays round. The row's height and its text don't move.
 */
public final class ChatListAvatar {
    private ChatListAvatar() {}

    /** The sizes a person can pick, as a percent of Telegram's. */
    public static final int[] CHOICES = {70, 80, 90, 110, 120};
    /** The size picked until the person picks another. */
    public static final int DEFAULT = 80;

    /**
     * Each avatar's radius before we scaled it and the radius we set, so a second layout without
     * Telegram setting the radius again scales from Telegram's value instead of from ours.
     * Main thread only, like the chat rows.
     */
    private static final Map<Object, int[]> RADII = new WeakHashMap<>();

    /**
     * Every chat row that has laid itself out, so a change to the switch or the size can lay them
     * out again. Main thread only, like the chat rows.
     */
    private static final Map<Object, Boolean> CELLS = new WeakHashMap<>();

    /**
     * Injected in the chat row's layout right after it sets the avatar's square.
     *
     * @param rect the square, in the row's pixels, changed in place
     * @param cell the chat row
     */
    public static void resize(RectF rect, Object cell) {
        if (rect == null) return;
        if (cell != null) CELLS.put(cell, Boolean.TRUE);
        boolean on = on();
        if (!on && RADII.isEmpty()) return;
        try {
            int percent = on ? percent(Settings.CHAT_LIST_AVATAR_SIZE.get()) : 100;
            Object image = cell == null ? null : telegram.avatarOf(cell);
            if (image != null) rescaleRadius(image, percent);
            if (percent == 100) return;
            scale(rect, percent);
            HookStatus.counted(FamilyNames.HIDE_FOLDER_COUNTERS, "avatar resized");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, "chat list avatar", failure);
        }
    }

    /**
     * Lays every chat row out again and redraws it, so a change to the switch or the size shows
     * now. A row lays itself out again only when its own size changes, and the picture's size
     * isn't the row's, so without this the rows already on screen kept the old size until Telegram
     * restarted (#89). Main thread only.
     */
    public static void relayoutAll() {
        for (Object cell : new ArrayList<>(CELLS.keySet())) {
            try {
                telegram.relayout(cell);
                if (cell instanceof View) ((View) cell).invalidate();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, "chat list avatar relayout", failure);
            }
        }
    }

    /** Shrinks or grows [rect] to [percent] of its size around its center. */
    static void scale(RectF rect, int percent) {
        float halfWidth = rect.width() * percent / 200f;
        float halfHeight = rect.height() * percent / 200f;
        float x = rect.centerX();
        float y = rect.centerY();
        rect.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
    }

    private static void rescaleRadius(Object image, int percent) {
        int[] corners = telegram.radii(image);
        if (corners == null || corners.length != 4) return;
        int current = corners[0];
        for (int corner : corners) {
            if (corner != current) return;
        }
        int[] plan = radiusPlan(current, RADII.get(image), percent);
        if (plan[1] != current) telegram.setRadius(image, plan[1]);
        if (percent == 100) {
            RADII.remove(image);
        } else {
            RADII.put(image, plan);
        }
    }

    /**
     * Telegram's radius and the one to set, from the avatar's [current] radius and what we did
     * last time ([memo], null when nothing). A current radius that is still ours means Telegram
     * hasn't set its own since, so the old base stays; anything else is Telegram's new value.
     */
    static int[] radiusPlan(int current, int[] memo, int percent) {
        int base = memo != null && memo[1] == current ? memo[0] : current;
        int wanted = percent == 100 ? base : Math.round(base * percent / 100f);
        return new int[] {base, wanted};
    }

    /** A saved size if it is one of the choices, otherwise the default. */
    public static int percent(int saved) {
        for (int choice : CHOICES) {
            if (choice == saved) return saved;
        }
        return DEFAULT;
    }

    static boolean on() {
        HookStatus.invoked(FamilyNames.HIDE_FOLDER_COUNTERS);
        try {
            return Utils.settingsReady() && Settings.CHAT_LIST_AVATAR.get()
                    && PatchFamily.Capability.CHAT_LIST_AVATAR.installed();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_FOLDER_COUNTERS, "chat list avatar switch", failure);
            return false;
        }
    }

    /** What this needs from Telegram. */
    interface Telegram {
        Object avatarOf(Object cell);
        int[] radii(Object image);
        void setRadius(Object image, int radius);
        void relayout(Object cell);
    }

    static Telegram telegram = new Stubs();

    private static final class Stubs implements Telegram {
        @Override public Object avatarOf(Object cell) { return ChatListAvatar.avatarOf(cell); }
        @Override public int[] radii(Object image) { return ChatListAvatar.radii(image); }
        @Override public void setRadius(Object image, int radius) { ChatListAvatar.setRadius(image, radius); }
        @Override public void relayout(Object cell) { ChatListAvatar.relayout(cell); }
    }

    /** Forgets every remembered radius and row. For tests. */
    static void forget() {
        RADII.clear();
        CELLS.clear();
    }

    // Stubs. The patch gives each one Telegram's code; until then they do nothing.

    /** The chat row's avatar ImageReceiver. */
    public static Object avatarOf(Object cell) { return null; }

    /** The ImageReceiver's four corner radii, in pixels. */
    public static int[] radii(Object image) { return null; }

    /** Gives the ImageReceiver one corner radius for all four corners. */
    public static void setRadius(Object image, int radius) {}

    /** Runs the chat row's own layout, the one {@link #resize} is injected in. */
    public static void relayout(Object cell) {}
}
