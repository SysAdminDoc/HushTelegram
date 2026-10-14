/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.location.Location;
import android.webkit.GeolocationPermissions;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MiniAppLocationTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final Location fix = new Location("gps");
    private final Recorder page = new Recorder();

    @Before public void setUp() {
        fix.setLatitude(40.7);
        fix.setLongitude(-74.0);
        Settings.MINI_APP_LOCATION_OFF.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.MINI_APP_LOCATION_OFF);
        Settings.MINI_APP_LOCATION_OFF.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultEverythingGoesAsTelegramDecides() {
        assertFalse(Settings.MINI_APP_LOCATION_OFF.get());
        assertStock();
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("Counted:"));
    }

    @Test public void onKeepsTheLocationFromMiniAppsAndPages() {
        Settings.MINI_APP_LOCATION_OFF.save(true);

        assertTrue("the GPS check answers no", MiniAppLocation.refuse());
        assertNull("a location already on its way goes out as unavailable", MiniAppLocation.location(fix));
        assertNull(MiniAppLocation.location(null));
        assertTrue("the prompt is answered here", MiniAppLocation.refusePrompt("https://example.com", page));
        assertEquals(List.of("https://example.com false false"), page.answers);
        assertSame("an open dialog answers no whatever was tapped", Boolean.FALSE, MiniAppLocation.answer(Boolean.TRUE));
        assertSame(Boolean.FALSE, MiniAppLocation.answer(Boolean.FALSE));
        assertTrue(HookStatus.report().toString(),
                HookStatus.report().toString().contains("location kept from a Mini App or web page 6"));
    }

    // A page that can't be answered still gets no location and no dialog, and the report says why.
    @Test public void aRefusalThatThrowsIsReportedAndTheDialogStillStaysShut() {
        Settings.MINI_APP_LOCATION_OFF.save(true);
        assertTrue(MiniAppLocation.refusePrompt("https://example.com", new Object()));
        assertTrue(MiniAppLocation.refusePrompt("https://example.com", null));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("web page location refusal"));
    }

    @Test public void pauseMissingContextAndBrokenReadsLeaveItStock() {
        Settings.MINI_APP_LOCATION_OFF.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertStock();
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(this::assertStock);
        SettingReadsForTests.breakReads(Settings.MINI_APP_LOCATION_OFF);
        assertStock();
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("Mini App location switch"));
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("Counted:"));
    }

    private void assertStock() {
        assertFalse(MiniAppLocation.refuse());
        assertSame(fix, MiniAppLocation.location(fix));
        assertFalse("Telegram's dialog decides", MiniAppLocation.refusePrompt("https://example.com", page));
        assertTrue(page.answers.toString(), page.answers.isEmpty());
        assertSame(Boolean.TRUE, MiniAppLocation.answer(Boolean.TRUE));
        assertNull(MiniAppLocation.answer(null));
    }

    private static final class Recorder implements GeolocationPermissions.Callback {
        final List<String> answers = new ArrayList<>();

        @Override public void invoke(String origin, boolean allow, boolean retain) {
            answers.add(origin + " " + allow + " " + retain);
        }
    }
}
