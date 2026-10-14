/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
import static android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
import static android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static app.hushtelegram.extension.telegram.misc.DataSaverDownloadsForTests.android;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.ConnectivityManager;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class DataSaverDownloadsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void reset() { restore(); }
    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.DATA_SAVER_DOWNLOADS);
        Settings.DATA_SAVER_DOWNLOADS.resetToDefault();
        DataSaverDownloads.forget();
        HookStatus.clear();
    }

    private static DataSaverDownloads.Status now() {
        return DataSaverDownloads.status(RuntimeEnvironment.getApplication());
    }

    @Test public void offByDefaultNothingIsHeld() {
        android(RESTRICT_BACKGROUND_STATUS_ENABLED, TYPE_MOBILE);
        assertFalse(Settings.DATA_SAVER_DOWNLOADS.get());
        assertFalse(DataSaverDownloads.hold());
        assertEquals(5, DataSaverDownloads.mask(5));
        assertTrue(String.join("\n", HookStatus.report()).contains(FamilyNames.FASTER_DOWNLOADS));
    }

    @Test public void dataSaverOnAMeteredNetworkHoldsAutomaticDownloads() {
        Settings.DATA_SAVER_DOWNLOADS.save(true);
        android(RESTRICT_BACKGROUND_STATUS_ENABLED, TYPE_MOBILE);
        assertEquals(DataSaverDownloads.DataSaver.ON, now().dataSaver);
        assertEquals(Boolean.TRUE, now().metered);
        assertTrue(now().restricts());
        assertTrue(DataSaverDownloads.hold());
        // A chat cell's download mask comes back with no media types, and an empty one stays empty.
        assertEquals(0, DataSaverDownloads.mask(5));
        assertEquals(0, DataSaverDownloads.mask(0));
        assertTrue(String.join("\n", HookStatus.report()).contains("automatic download held for Data Saver"));
    }

    @Test public void dataSaverOffOrAllowingTelegramOrAnUnmeteredNetworkKeepsTelegramsRules() {
        Settings.DATA_SAVER_DOWNLOADS.save(true);
        android(RESTRICT_BACKGROUND_STATUS_DISABLED, TYPE_MOBILE);
        assertEquals(DataSaverDownloads.DataSaver.OFF, now().dataSaver);
        assertFalse(DataSaverDownloads.hold());
        assertEquals(5, DataSaverDownloads.mask(5));

        android(RESTRICT_BACKGROUND_STATUS_WHITELISTED, TYPE_MOBILE);
        assertEquals(DataSaverDownloads.DataSaver.ALLOWED, now().dataSaver);
        assertFalse(DataSaverDownloads.hold());
        assertEquals(5, DataSaverDownloads.mask(5));

        android(RESTRICT_BACKGROUND_STATUS_ENABLED, TYPE_WIFI);
        assertEquals(Boolean.FALSE, now().metered);
        assertFalse(DataSaverDownloads.hold());
        assertEquals(5, DataSaverDownloads.mask(5));
    }

    @Test public void androidIsAskedAgainOnceTheLastAnswerIsASecondOld() {
        Settings.DATA_SAVER_DOWNLOADS.save(true);
        android(RESTRICT_BACKGROUND_STATUS_ENABLED, TYPE_MOBILE);
        assertTrue(DataSaverDownloads.hold());
        // Data Saver goes off, but chat cells ask while they lay out, so one answer is used for a second.
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager.class))
                .setRestrictBackgroundStatus(RESTRICT_BACKGROUND_STATUS_DISABLED);
        assertTrue(DataSaverDownloads.hold());
        ShadowSystemClock.advanceBy(Duration.ofMillis(DataSaverDownloads.FRESH_MS));
        assertFalse(DataSaverDownloads.hold());
    }

    @Test public void pausingAnEarlyStartOrAnUnreadableSwitchHoldsNothing() {
        Settings.DATA_SAVER_DOWNLOADS.save(true);
        android(RESTRICT_BACKGROUND_STATUS_ENABLED, TYPE_MOBILE);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), DataSaverDownloads.hold());
            assertEquals(reason.name(), 5, DataSaverDownloads.mask(5));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertFalse(DataSaverDownloads.hold());
            assertEquals(5, DataSaverDownloads.mask(5));
        });
        SettingReadsForTests.breakReads(Settings.DATA_SAVER_DOWNLOADS);
        assertFalse(DataSaverDownloads.hold());
        assertFalse(HookStatus.missing(FamilyNames.FASTER_DOWNLOADS).isEmpty());
    }

    @Test public void whatAndroidWontSayHoldsNothing() {
        DataSaverDownloads.Status none = DataSaverDownloads.status(null);
        assertEquals(DataSaverDownloads.DataSaver.UNKNOWN, none.dataSaver);
        assertNull(none.metered);
        assertFalse(none.restricts());

        Context noService = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Object getSystemService(String name) {
                return null;
            }
        };
        assertEquals(DataSaverDownloads.DataSaver.UNKNOWN, DataSaverDownloads.status(noService).dataSaver);
        assertNull(DataSaverDownloads.status(noService).metered);

        Context broken = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Object getSystemService(String name) {
                throw new SecurityException("no network access");
            }
        };
        assertEquals(DataSaverDownloads.DataSaver.UNKNOWN, DataSaverDownloads.status(broken).dataSaver);
        assertFalse(DataSaverDownloads.status(broken).restricts());
    }
}
