/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/** Four verified interaction types share the existing switch and fail-open Pause/readiness guard. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AnalyticsAppLogTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final String[] TYPES = {
            "premium.promo_screen_show", "premium.promo_screen_tap",
            "premium.promo_screen_accept", "premium.promo_screen_fail"
    };
    private static final String[] COUNTERS = {
            "premium promo show report skipped", "premium promo tap report skipped",
            "premium promo accept report skipped", "premium promo fail report skipped"
    };

    @Before
    public void setUp() {
        HookStatus.clear();
        Settings.DISABLE_ANALYTICS.resetToDefault();
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.DISABLE_ANALYTICS);
        Settings.DISABLE_ANALYTICS.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void everyVerifiedInteractionIsSuppressedByDefaultWithItsOwnHonestCounter() {
        for (int i = 0; i < TYPES.length; i++) {
            HookStatus.clear();
            assertTrue(TYPES[i], Analytics.skipPremiumAppLog(TYPES[i]));
            assertEquals(Collections.singletonList("Disable analytics: invoked 1, 0 found, 0 missing. Counted: "
                    + COUNTERS[i] + " 1"), HookStatus.report());
        }
    }

    @Test
    public void switchedOffInteractionsRemainStockWithoutSuppressionCounts() {
        Settings.DISABLE_ANALYTICS.save(false);
        assertAllStock();
        assertEquals(Collections.singletonList("Disable analytics: invoked 4, 0 found, 0 missing"), HookStatus.report());
    }

    @Test
    public void everyPauseReasonRestoresEveryInteractionSend() {
        Settings.DISABLE_ANALYTICS.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            HookStatus.clear();
            PauseForTests.pause(reason);
            assertAllStock();
            assertEquals(reason.toString(), Collections.singletonList("Disable analytics: invoked 4, 0 found, 0 missing"),
                    HookStatus.report());
            PauseForTests.resume();
        }
    }

    @Test
    public void missingSettingsOrAnUndecidedPauseRestoresEveryInteractionSend() {
        Settings.DISABLE_ANALYTICS.save(true);
        SettingsContextRule.withoutContext(this::assertAllStock);
        SettingsContextRule.beforeThePauseIsDecided(this::assertAllStock);
        assertEquals(Collections.singletonList("Disable analytics: invoked 8, 0 found, 0 missing"), HookStatus.report());
    }

    @Test
    public void brokenSettingReadsFailOpenAndNeverCountSkippedReports() {
        Settings.DISABLE_ANALYTICS.save(true);
        SettingReadsForTests.breakReads(Settings.DISABLE_ANALYTICS);
        assertAllStock();
        assertEquals(Collections.singletonList("a working 'switch read' hook (it threw java.lang.NullPointerException)"),
                HookStatus.missing(FamilyNames.DISABLE_ANALYTICS));
        assertFalse(HookStatus.report().get(0).contains("Counted:"));
    }

    @Test
    public void operationalDiagnosticAndUnknownTypesNeverReadTheSwitchOrCountAsInteractions() {
        Settings.DISABLE_ANALYTICS.save(true);
        SettingReadsForTests.breakReads(Settings.DISABLE_ANALYTICS);
        for (String type : Arrays.asList(null, "", "fcm_token_request", "fcm_token_response", "hcm_token_request",
                "hcm_token_response", "android_dual_camera", "android_sdcard_exists", "support.report", "crash",
                "premium.promo_screen_show_extra", "PREMIUM.PROMO_SCREEN_SHOW")) {
            assertFalse(String.valueOf(type), Analytics.skipPremiumAppLog(type));
        }
        assertTrue("unverified types do not invoke the analytics guard", HookStatus.report().isEmpty());
        assertTrue("unverified types cannot trip a broken settings read", HookStatus.missing(FamilyNames.DISABLE_ANALYTICS).isEmpty());
    }

    @Test
    public void theDualCameraReportIsSkippedByDefaultAndNoOtherTypeIs() {
        assertTrue(Analytics.skipDeviceAppLog("android_dual_camera"));
        assertEquals(Collections.singletonList("Disable analytics: invoked 1, 0 found, 0 missing. Counted: dual camera report skipped 1"),
                HookStatus.report());
        HookStatus.clear();
        SettingReadsForTests.breakReads(Settings.DISABLE_ANALYTICS);
        for (String type : Arrays.asList(null, "", "android_sdcard_exists", "premium.promo_screen_show", "fcm_token_request",
                "ANDROID_DUAL_CAMERA", "android_dual_camera_extra")) {
            assertFalse(String.valueOf(type), Analytics.skipDeviceAppLog(type));
        }
        assertTrue("other types never invoke the guard", HookStatus.report().isEmpty());
        assertTrue("other types cannot trip a broken settings read", HookStatus.missing(FamilyNames.DISABLE_ANALYTICS).isEmpty());
    }

    @Test
    public void theDualCameraReportGoesOutWhenOffPausedEarlyOrUnreadable() {
        Settings.DISABLE_ANALYTICS.save(false);
        assertFalse(Analytics.skipDeviceAppLog("android_dual_camera"));
        Settings.DISABLE_ANALYTICS.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.toString(), Analytics.skipDeviceAppLog("android_dual_camera"));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse(Analytics.skipDeviceAppLog("android_dual_camera")));
        SettingReadsForTests.breakReads(Settings.DISABLE_ANALYTICS);
        assertFalse(Analytics.skipDeviceAppLog("android_dual_camera"));
        assertFalse(HookStatus.report().get(0).contains("Counted:"));
        assertEquals(Collections.singletonList("a working 'switch read' hook (it threw java.lang.NullPointerException)"),
                HookStatus.missing(FamilyNames.DISABLE_ANALYTICS));
    }

    private void assertAllStock() {
        for (String type : TYPES) assertFalse(type, Analytics.skipPremiumAppLog(type));
    }
}
