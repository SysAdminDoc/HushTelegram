/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;

import java.util.function.Function;

import app.hushtelegram.extension.shared.Logger;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Keeps messages coming over Telegram's own connection when its server refuses the push sign-up.
 * A registered API pair has no push key on Telegram's side, so account.registerDevice answers
 * APP_PUSH_APIKEY_MISSING and no push ever comes (issues #5 and #16). Telegram already falls back
 * to its own connection on phones without Google Play, through two switches under Notifications
 * and Sounds: Keep-Alive Service ({@code pushService}) and Background Connection
 * ({@code pushConnection}). Neither is on by default where Google Play is, so a refused sign-up
 * left Telegram to wake up whenever Android let it.
 *
 * <p>On a refusal this turns both on, but only when the user has never set either, and remembers
 * that it did. A sign-up Firebase later gets accepted hands them back to Telegram's defaults, if
 * they're still as this left them. Switching either one in Telegram's settings is the user's
 * call and this never touches it again. Repair Firebase push registration off or Pause leaves
 * everything alone.
 */
public final class OwnConnection {
    /** Telegram's global notification settings (MessagesController.getGlobalNotificationsSettings). */
    static final String TELEGRAM_PREFS = "Notifications";
    static final String KEEP_ALIVE = "pushService";
    static final String BACKGROUND = "pushConnection";
    /** Ours: set while both switches are on because of a refusal, so only we turn them back. */
    static final String PREFS = "hushtelegram_own_connection";
    static final String TURNED_ON = "turned_on";

    private OwnConnection() { }

    /** Where both settings live. Tests swap in stores whose writes can fail. */
    static Function<Context, SharedPreferences> telegramStore =
            context -> context.getSharedPreferences(TELEGRAM_PREFS, Context.MODE_PRIVATE);
    static Function<Context, SharedPreferences> store =
            context -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

    /** Puts the change into effect in the running process. Tests count calls instead. */
    static Runnable apply = OwnConnection::applyInTelegram;

    /**
     * Called with each answer to a Firebase account.registerDevice, after it's recorded, while the
     * account that asked is still the one signed in. UnifiedPush answers never come here.
     */
    static void answered(boolean accepted) {
        try {
            if (!Utils.settingsReady() || HushTelegramPause.isPaused() || !Settings.REPAIR_FIREBASE_PUSH.get()) return;
            Context context = Utils.getContext();
            if (context == null) return;
            if (accepted) handBack(context);
            else turnOn(context);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REPAIR_FIREBASE_PUSH, "Telegram's own connection", failure);
        }
    }

    private static void turnOn(Context context) {
        SharedPreferences telegram = telegramStore.apply(context);
        if (telegram.contains(KEEP_ALIVE) || telegram.contains(BACKGROUND)) {
            if (!store.apply(context).getBoolean(TURNED_ON, false)) {
                HookStatus.counted(FamilyNames.REPAIR_FIREBASE_PUSH, "own connection left as set in Telegram");
            }
            return;
        }
        // Ours first: if Telegram's write then fails, the next answer finds nothing to hand back.
        if (!store.apply(context).edit().putBoolean(TURNED_ON, true).commit()) {
            HookStatus.counted(FamilyNames.REPAIR_FIREBASE_PUSH, "own connection not saved");
            return;
        }
        if (!telegram.edit().putBoolean(KEEP_ALIVE, true).putBoolean(BACKGROUND, true).commit()) {
            store.apply(context).edit().remove(TURNED_ON).commit();
            HookStatus.counted(FamilyNames.REPAIR_FIREBASE_PUSH, "own connection not saved");
            return;
        }
        HookStatus.counted(FamilyNames.REPAIR_FIREBASE_PUSH, "own connection turned on for a refused sign-up");
        Logger.printInfo(() -> "Push sign-up refused: Keep-Alive Service and Background Connection turned on");
        apply.run();
    }

    private static void handBack(Context context) {
        // UnifiedPush's sign-up is accepted too, and that alone says nothing about Firebase.
        if (UnifiedPush.activeEndpoint() != null) return;
        SharedPreferences ours = store.apply(context);
        if (!ours.getBoolean(TURNED_ON, false)) return;
        SharedPreferences telegram = telegramStore.apply(context);
        if (telegram.getBoolean(KEEP_ALIVE, false) && telegram.getBoolean(BACKGROUND, false)) {
            if (!telegram.edit().remove(KEEP_ALIVE).remove(BACKGROUND).commit()) return;
            HookStatus.counted(FamilyNames.REPAIR_FIREBASE_PUSH, "own connection handed back to push");
            Logger.printInfo(() -> "Push sign-up accepted: Keep-Alive Service and Background Connection back to Telegram's defaults");
            apply.run();
        }
        ours.edit().remove(TURNED_ON).commit();
    }

    /** Both of Telegram's switches on, or null when they can't be read. */
    @Nullable
    static Boolean bothOn(@Nullable Context context) {
        if (context == null) return null;
        try {
            SharedPreferences telegram = telegramStore.apply(context);
            return telegram.getBoolean(KEEP_ALIVE, false) && telegram.getBoolean(BACKGROUND, false);
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /** Whether the switches are on because of a refusal rather than the user. */
    static boolean turnedOnHere(@Nullable Context context) {
        if (context == null) return false;
        try {
            return store.apply(context).getBoolean(TURNED_ON, false);
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /**
     * What Telegram's own Notifications page does when either switch is flipped, on the main thread
     * as there: restart or stop its keep-alive service and tell the current account's connection.
     * Anything missing here only waits for the next start, which reads both settings again.
     */
    private static void applyInTelegram() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Class.forName("org.telegram.messenger.ApplicationLoader").getMethod("startPushService").invoke(null);
                int account = Class.forName("org.telegram.messenger.UserConfig").getField("selectedAccount").getInt(null);
                Class<?> connections = Class.forName("org.telegram.tgnet.ConnectionsManager");
                Object manager = connections.getMethod("getInstance", int.class).invoke(null, account);
                boolean enabled = (Boolean) connections.getMethod("isPushConnectionEnabled").invoke(manager);
                connections.getMethod("setPushConnectionEnabled", boolean.class).invoke(manager, enabled);
            } catch (Throwable failure) {
                HookStatus.counted(FamilyNames.REPAIR_FIREBASE_PUSH, "own connection waits for the next start");
                Logger.printException(() -> "Couldn't apply Telegram's own connection now", failure);
            }
        });
    }
}
