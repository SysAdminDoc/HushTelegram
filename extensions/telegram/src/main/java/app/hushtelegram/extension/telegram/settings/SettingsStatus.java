/*
 * Forked from https://github.com/SysAdminDoc/HushThreads at b141524 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at c15d4f79 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.hushtelegram.extension.telegram.settings;

/**
 * Which patches were selected for this build.
 *
 * <p>Every method answers false here. A patch that adds a feature rewrites its method to answer
 * true, so the settings screen offers only the switches this APK backs and the diagnostic report
 * lists only the patches it carries.
 */
@SuppressWarnings({"unused", "SameReturnValue"})
public final class SettingsStatus {
    private SettingsStatus() {
    }

    public static boolean hideAds() {
        return false;
    }

    public static boolean disableAnalytics() {
        return false;
    }

    public static boolean disableUpdateChecks() {
        return false;
    }
}
