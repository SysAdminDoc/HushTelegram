/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;
import java.lang.ref.WeakReference;

/**
 * Telegram's video viewer tells its player whether to be muted each time a video starts, buffers,
 * shows its first frame or stops casting, and only mutes a video that's about to be sent without
 * sound or one playing on a cast device (#61). Its volume calls give a video full sound on a volume
 * key, and on a tap in the editor, and none when the editor starts one.
 *
 * <p>With the switch on, every video the viewer opens plays without sound until one of those full
 * volume calls, or the editor's own sound button, gives that player its sound. The next video gets
 * a new player and starts silent again. Round videos and the videos playing in a chat never reach
 * the viewer and stay Telegram's. All of it runs on the main thread.
 */
public final class VideoSound {
    private VideoSound() {}

    static final String STARTED_SILENT = "video kept without sound";
    static final String GIVEN_SOUND = "video given its sound";

    /** The player a volume key, a tap or the sound button gave its sound to. */
    private static WeakReference<Object> heard = new WeakReference<>(null);
    /** The last player Telegram itself muted, so its sound coming back counts as asked for. */
    private static WeakReference<Object> mutedByTelegram = new WeakReference<>(null);

    /**
     * Called in place of the viewer telling its player whether to be muted.
     *
     * @param player the viewer's video player
     * @param mute 1 when Telegram mutes it anyway: a video sent without sound, or one on a cast device
     */
    public static void setMute(Object player, int mute) {
        stockSetMute(player, muted(player, mute != 0));
    }

    /**
     * Called in place of the viewer setting its player's volume.
     *
     * @param player the viewer's video player
     * @param volume 1 for full sound, 0 for none
     */
    public static void setVolume(Object player, float volume) {
        if (volume > 0f) given(player);
        stockSetVolume(player, volume);
    }

    /** What the player is told: Telegram's own mute, or none until this player was given its sound. */
    static boolean muted(Object player, boolean telegramMutes) {
        if (telegramMutes) {
            mutedByTelegram = new WeakReference<>(player);
            return true;
        }
        // Telegram unmuting a player it had muted is the editor's sound button, or casting ending.
        if (mutedByTelegram.get() == player) {
            mutedByTelegram = new WeakReference<>(null);
            given(player);
        }
        return heard.get() != player && startsSilent();
    }

    private static void given(Object player) {
        if (heard.get() == player) return;
        heard = new WeakReference<>(player);
        HookStatus.counted(FamilyNames.KEEP_VIDEOS_MUTED, GIVEN_SOUND);
    }

    private static boolean startsSilent() {
        HookStatus.invoked(FamilyNames.KEEP_VIDEOS_MUTED);
        try {
            if (!Utils.settingsReady() || !Settings.VIDEO_STARTS_SILENT.get()) return false;
            HookStatus.counted(FamilyNames.KEEP_VIDEOS_MUTED, STARTED_SILENT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.KEEP_VIDEOS_MUTED, "video sound", failure);
            return false;
        }
    }

    static void reset() {
        heard = new WeakReference<>(null);
        mutedByTelegram = new WeakReference<>(null);
    }

    /** The player's own mute. Replaced with the player's method when patching. */
    public static void stockSetMute(Object player, boolean mute) {}

    /** The player's own volume. Replaced with the player's method when patching. */
    public static void stockSetVolume(Object player, float volume) {}
}
