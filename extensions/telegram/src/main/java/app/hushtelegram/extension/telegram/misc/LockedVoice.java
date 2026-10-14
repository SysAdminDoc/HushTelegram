/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.SystemClock;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Keeps voice and round video messages quiet while the phone is locked. Every way Telegram plays
 * one goes through its player's one play method: a tap in a chat, the play and skip buttons in the
 * notification and on the lock screen, a headset or car button, and the next message of a queue.
 * With the switch on and the lock screen showing, that method plays no voice or round video message
 * it isn't already playing, so nothing starts, resumes or skips to another one until you unlock.
 *
 * <p>What's already playing when the phone locks keeps playing: Telegram asks the same method again
 * for the message it's playing, for instance once the rest of its file has downloaded, and that
 * goes through. It ends where it ends, and the next one in a queue waits for an unlock. Pausing
 * still works from anywhere. Music, ordinary videos and calls never reach the check.
 */
public final class LockedVoice {
    private LockedVoice() {}

    /** How long one refusal's toast covers the ones after it, so a queue or a held button shows one. */
    static final long QUIET_MILLIS = 3000L;

    /** Whether the lock screen is showing. Tests swap in their own. */
    static volatile BooleanSupplier locked = LockedVoice::keyguardLocked;

    /** Whether a message is a voice or round video message. Tests swap in their own, since only a patched app has the real one. */
    static volatile Predicate<Object> voice = LockedVoice::voiceOrRound;

    /** Says why nothing played, with nothing from the message in it. Tests swap in their own. */
    static volatile Runnable feedback = LockedVoice::tell;

    private static volatile long lastTold = Long.MIN_VALUE;

    /**
     * At the start of Telegram's play method, before it looks at anything.
     *
     * @param message the message Telegram is asked to play, or null
     * @param playing the message Telegram's player holds, or null
     * @param paused  whether Telegram's player is paused
     * @return true to refuse: the method answers false and plays nothing
     */
    public static boolean refuse(Object message, Object playing, boolean paused) {
        // Telegram answers false for no message itself.
        if (message == null || !on()) return false;
        // The message already playing: Telegram carries on with it, or restarts it once it has downloaded.
        if (message == playing && !paused) return false;
        try {
            if (!voice.test(message) || !locked.getAsBoolean()) return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VOICE_ONE_AT_A_TIME, "locked phone check", failure);
            return false;
        }
        HookStatus.counted(FamilyNames.VOICE_ONE_AT_A_TIME, "voice kept quiet while locked");
        try {
            feedback.run();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VOICE_ONE_AT_A_TIME, "locked phone toast", failure);
        }
        return true;
    }

    private static boolean on() {
        HookStatus.invoked(FamilyNames.VOICE_ONE_AT_A_TIME);
        try {
            return Utils.settingsReady() && Settings.LOCKED_VOICE_GUARD.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VOICE_ONE_AT_A_TIME, "locked phone switch read", failure);
            return false;
        }
    }

    /** Whether Android's lock screen is showing. Without a context to ask, it says no. */
    static boolean keyguardLocked() {
        Context context = Utils.getContext();
        if (context == null) return false;
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    /** One short toast for a burst of refusals. */
    private static void tell() {
        long now = SystemClock.elapsedRealtime();
        long last = lastTold;
        if (last != Long.MIN_VALUE && now - last < QUIET_MILLIS) return;
        lastTold = now;
        Utils.showToastShort(L10n.t("Unlock your phone to play voice and video messages."));
    }

    /** Lets a test start from no toast at all. */
    static void forgetToast() {
        lastTold = Long.MIN_VALUE;
    }

    /** Whether a message is a voice or round video message. The patch writes this from Telegram's own checks. */
    public static boolean voiceOrRound(Object message) {
        return false;
    }
}
