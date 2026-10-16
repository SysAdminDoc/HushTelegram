/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.util.List;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Keeps Telegram's usage reports on the phone.
 *
 * <p>While you scroll a channel, Telegram times how long each post stays on screen and sends the
 * batch as {@code messages.reportReadMetrics}. That send asks this class first, and while the switch
 * is on the batch isn't sent. Messages, calls, view counts and
 * everything else Telegram needs go on as before. Four verified Premium screen interactions also
 * ask before their telemetry send. Their payload construction and billing cleanup stay intact. So
 * does the camera's dual-camera report, which names the phone's maker and model.
 */
public final class Analytics {
    private Analytics() {}

    /**
     * Injected where a channel's read metrics are about to go out, with the batch waiting to be
     * sent. True means return without sending; the batch is emptied first, as Telegram empties it
     * after a send, so it doesn't pile up while you stay in the channel. Never throws.
     */
    public static boolean skipReadMetrics(List<?> pending) {
        if (!skip("read metrics report skipped")) return false;
        try {
            if (pending != null) pending.clear();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.DISABLE_ANALYTICS, "read metrics clear", t);
        }
        return true;
    }

    /** Only verified Premium screen interactions are eligible; unknown and diagnostic types stay stock. */
    public static boolean skipPremiumAppLog(String type) {
        if (type == null) return false;
        switch (type) {
            case "premium.promo_screen_show": return skip("premium promo show report skipped");
            case "premium.promo_screen_tap": return skip("premium promo tap report skipped");
            case "premium.promo_screen_accept": return skip("premium promo accept report skipped");
            case "premium.promo_screen_fail": return skip("premium promo fail report skipped");
            default: return false;
        }
    }

    /**
     * Asked just before the camera's dual-camera report goes out, with its event type. That report
     * carries the phone's maker and model and goes out when the server asks for device statistics.
     * Only that verified type is eligible; anything else stays stock without reading the switch.
     */
    public static boolean skipDeviceAppLog(String type) {
        if (!"android_dual_camera".equals(type)) return false;
        return skip("dual camera report skipped");
    }

    private static boolean skip(String what) {
        HookStatus.invoked(FamilyNames.DISABLE_ANALYTICS);
        try {
            if (!Utils.settingsReady() || !Settings.DISABLE_ANALYTICS.get()) return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.DISABLE_ANALYTICS, "switch read", t);
            return false;
        }
        HookStatus.counted(FamilyNames.DISABLE_ANALYTICS, what);
        return true;
    }
}
