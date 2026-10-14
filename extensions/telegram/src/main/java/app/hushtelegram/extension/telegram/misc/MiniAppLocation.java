/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.location.Location;
import android.webkit.GeolocationPermissions;

import androidx.annotation.Nullable;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Keeps the phone's location from Mini Apps and the web pages Telegram opens inside itself.
 *
 * <p>A Mini App asks through Telegram's bot location manager, which checks the phone has GPS before
 * it asks Android for a location, and turns each location into the answer the Mini App gets. With
 * the switch on, the GPS check answers no, so Telegram tells the Mini App location isn't available
 * without asking Android or showing its dialog, the way it does on a phone without GPS. A location
 * still on its way from a request made before the switch went on goes out as unavailable too.
 *
 * <p>A web page asks through the WebView's geolocation prompt: it's refused without Telegram's
 * dialog, and a dialog already open answers no whatever is tapped. Telegram never lets the WebView
 * keep an answer past the page, so a page allowed before the switch went on keeps it only until
 * it's closed or reloaded.
 *
 * <p>Sharing a location in a chat, maps and live locations go neither way and stay Telegram's.
 */
public final class MiniAppLocation {
    private MiniAppLocation() {}

    /**
     * Injected at the start of the bot location manager's GPS check.
     *
     * @return true to answer that the phone has no GPS, so no location is asked for
     */
    public static boolean refuse() {
        return held();
    }

    /**
     * Injected at the start of the location manager's answer for a Mini App, with the location.
     *
     * @return null while the switch is on, so the answer says location isn't available
     */
    @Nullable
    public static Location location(@Nullable Location location) {
        return held() ? null : location;
    }

    /**
     * Injected at the start of the web view's geolocation prompt. On a yes the page is refused here
     * and Telegram's dialog isn't shown.
     *
     * @return true when the page was refused
     */
    public static boolean refusePrompt(@Nullable String origin, @Nullable Object callback) {
        if (!held()) return false;
        try {
            ((GeolocationPermissions.Callback) callback).invoke(origin, false, false);
        } catch (Throwable failure) {
            // The page gets no answer and no location; Telegram's dialog still isn't shown.
            HookStatus.threw(FamilyNames.OPEN_EXTERNAL_LINKS, "web page location refusal", failure);
        }
        return true;
    }

    /**
     * Injected at the start of the answer to Telegram's geolocation dialog, with what was tapped or
     * what Android said.
     *
     * @return no while the switch is on, otherwise the answer as it was
     */
    @Nullable
    public static Object answer(@Nullable Object granted) {
        return held() ? Boolean.FALSE : granted;
    }

    private static boolean held() {
        HookStatus.invoked(FamilyNames.OPEN_EXTERNAL_LINKS);
        try {
            if (!Utils.settingsReady() || !Settings.MINI_APP_LOCATION_OFF.get()) return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.OPEN_EXTERNAL_LINKS, "Mini App location switch", failure);
            return false;
        }
        HookStatus.counted(FamilyNames.OPEN_EXTERNAL_LINKS, "location kept from a Mini App or web page");
        return true;
    }
}
