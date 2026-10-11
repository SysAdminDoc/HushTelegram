/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.annotation.Nullable;
import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Logger;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.ui.CustomDialog;

/**
 * Says why sign-in stopped when Telegram refuses the app's API ID. Telegram 13's phone screen
 * names a few sendCode errors itself and hands the rest to its general error handler, which has no
 * case for sendCode, so API_ID_PUBLISHED_FLOOD (the API ID built into the app refused) left the
 * screen sitting there with nothing said (issues #3 and #11). Only those two errors on sendCode
 * get the message; everything else goes on to Telegram's handler as before.
 *
 * <p>It works while HushTelegram is paused too. The credentials patches it points at keep their
 * values through Pause, and a message is all it adds.
 */
public final class SignInHelp {
    static final String GUIDE_URL = "https://github.com/SysAdminDoc/HushTelegram#your-telegram-account";
    /** Where to go by hand, without the section, so the toast stays inside two lines. */
    static final String GUIDE_ADDRESS = GUIDE_URL.substring(GUIDE_URL.indexOf("://") + 3, GUIDE_URL.indexOf('#'));
    static final String SEND_CODE = "org.telegram.tgnet.TLRPC$TL_auth_sendCode";
    static final String PUBLISHED_FLOOD = "API_ID_PUBLISHED_FLOOD";
    static final String INVALID = "API_ID_INVALID";

    private SignInHelp() {}

    /**
     * Hooked at the start of Telegram's error handler with its error, screen and request. True when
     * this showed the message, and the handler then returns without its own.
     */
    public static boolean explain(Object error, Object fragment, Object request) {
        try {
            String code = refusal(error, request);
            if (code == null) return false;
            Activity activity = (Activity) fragment.getClass().getMethod("getParentActivity").invoke(fragment);
            if (activity == null || activity.isFinishing()) return false;
            show(activity, code);
            Logger.printInfo(() -> "Explained a sign-in refusal: " + code);
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "Couldn't explain a sign-in refusal", failure);
            return false;
        }
    }

    /** The error name this explains, or null for any other error or request. */
    @Nullable
    static String refusal(Object error, Object request) throws ReflectiveOperationException {
        if (error == null || request == null || !SEND_CODE.equals(request.getClass().getName())) return null;
        Object text = error.getClass().getField("text").get(error);
        if (!(text instanceof String)) return null;
        if (((String) text).contains(PUBLISHED_FLOOD)) return PUBLISHED_FLOOD;
        if (((String) text).contains(INVALID)) return INVALID;
        return null;
    }

    static String message(Context context, String code) {
        String reason = PUBLISHED_FLOOD.equals(code)
                ? L10n.t(context, "Telegram turned down the API ID this app signs in with, so no login code was sent."
                        + " Patch HushTelegram again with Use registered Telegram API credentials and your own ID from"
                        + " my.telegram.org/apps. The guide walks you through it.")
                : L10n.t(context, "Telegram didn't accept the API ID and hash this app was patched with, so no login"
                        + " code was sent. Copy both again from my.telegram.org/apps and patch once more.");
        return reason + "\n\n(" + code + ")";
    }

    private static void show(Activity activity, String code) {
        CustomDialog.create(activity, L10n.t(activity, "Telegram refused this app's ID"), message(activity, code),
                null, L10n.t(activity, "Open the guide"), () -> openGuide(activity), () -> { },
                null, null, false).first.show();
    }

    static void openGuide(Context context) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GUIDE_URL))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException | SecurityException missing) {
            // No browser, or none switched on. Uncaught, Android's exception closed Telegram.
            Logger.printInfo(() -> "No app opened the sign-in guide");
            Utils.showToastLong(L10n.f(context, "Can't open the link. Go to %1$s.", GUIDE_ADDRESS));
        }
    }
}
