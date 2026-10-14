/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.robolectric.Shadows.shadowOf;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.util.function.Consumer;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetworkInfo;

/** Lets tests outside this package set what Android reports and ask the Data Saver hook. */
public final class DataSaverDownloadsForTests {
    private DataSaverDownloadsForTests() {}

    /** Android reports Data Saver [status] for Telegram, on a network of [type], from now on. */
    public static void android(int status, int type) {
        ShadowConnectivityManager shadow = shadowOf(
                RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager.class));
        shadow.setRestrictBackgroundStatus(status);
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0,
                true, NetworkInfo.State.CONNECTED));
        shadow.setDefaultNetworkActive(true);
        DataSaverDownloads.forget();
    }

    /** Whether an automatic download would be held with Data Saver on and a mobile network. */
    public static boolean holdsOnMeteredDataSaver() {
        android(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED, ConnectivityManager.TYPE_MOBILE);
        try {
            return DataSaverDownloads.hold();
        } finally {
            DataSaverDownloads.reset();
        }
    }

    /**
     * Knows [controller] as an account's DownloadController, and runs Telegram's check on it with
     * [checker], at once once posted. {@link #reset} puts the real check back.
     */
    public static void checkWith(Consumer<Object> checker, Object controller) {
        DataSaverDownloads.reset();
        DataSaverDownloads.later = Runnable::run;
        DataSaverDownloads.checker = checker;
        DataSaverDownloads.holdMask(controller);
    }

    /** Forgets the controllers and puts Telegram's real check back. */
    public static void reset() {
        DataSaverDownloads.reset();
    }

    /** Whether the network's download mask would be held with Data Saver on and a mobile network. */
    public static boolean holdsMaskOnMeteredDataSaver() {
        android(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED, ConnectivityManager.TYPE_MOBILE);
        try {
            return DataSaverDownloads.holdMask(null);
        } finally {
            DataSaverDownloads.reset();
        }
    }
}
