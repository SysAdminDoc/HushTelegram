/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.os.Build;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.StringSetting;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * The device in Active sessions (#79): every account tells Telegram a device name and a system
 * version when it connects, and Settings > Devices on your other phones and computers shows them.
 *
 * <p>Telegram builds both in ConnectionsManager's constructor (the phone's maker and model run
 * together, and "SDK" with the Android API level) and hands them to init, which passes them to the
 * native connection. The patch runs each through {@link #model} and {@link #system} at the top of
 * init, so a name saved here goes out instead and an empty one leaves Telegram's own. Each account
 * connects once per launch, so a change shows after a restart.
 */
public final class SessionDevice {
    /** Room for a long name, and far less than a line the sessions list can't show. */
    public static final int MAX_LENGTH = 64;

    private SessionDevice() {}

    /** Injected at the top of ConnectionsManager.init on the device name Telegram built. */
    public static String model(String stock) {
        return chosen(Settings.SESSION_DEVICE_NAME, stock, "session device name");
    }

    /** Injected at the top of ConnectionsManager.init on the system version Telegram built. */
    public static String system(String stock) {
        return chosen(Settings.SESSION_SYSTEM_VERSION, stock, "session system version");
    }

    private static String chosen(StringSetting setting, String stock, String what) {
        HookStatus.invoked(FamilyNames.DISABLE_ANALYTICS);
        try {
            if (!Utils.settingsReady()) return stock;
            String value = clean(setting.get());
            if (value.isEmpty()) return stock;
            HookStatus.counted(FamilyNames.DISABLE_ANALYTICS, what + " changed");
            return value;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DISABLE_ANALYTICS, what, failure);
            return stock;
        }
    }

    /**
     * What was typed as one line Telegram can send: line breaks, tabs and other control
     * characters become spaces, runs of spaces become one, the ends are trimmed, and anything past
     * {@link #MAX_LENGTH} characters is cut, never in the middle of a character.
     */
    public static String clean(String typed) {
        if (typed == null) return "";
        StringBuilder out = new StringBuilder(Math.min(typed.length(), MAX_LENGTH));
        boolean space = false;
        for (int at = 0; at < typed.length(); ) {
            int point = typed.codePointAt(at);
            at += Character.charCount(point);
            if (Character.isISOControl(point) || Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = out.length() > 0;
                continue;
            }
            int needed = Character.charCount(point) + (space ? 1 : 0);
            if (out.length() + needed > MAX_LENGTH) break;
            if (space) out.append(' ');
            space = false;
            out.appendCodePoint(point);
        }
        return out.toString();
    }

    /** The name Telegram sends on its own, the way its constructor builds it. */
    public static String stockModel() {
        String model = String.valueOf(Build.MANUFACTURER) + Build.MODEL;
        return model.trim().isEmpty() ? "Android unknown" : model;
    }

    /** The system version Telegram sends on its own. */
    public static String stockSystem() {
        return "SDK " + Build.VERSION.SDK_INT;
    }
}
