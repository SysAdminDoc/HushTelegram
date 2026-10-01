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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * {@link Analytics}'s two hooks, read on their own: what each counts with the switch on, what it
 * leaves alone with the switch off, paused, or before the settings are ready, and what it does
 * when the switch itself cannot be read.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AnalyticsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void setUp() {
        HookStatus.clear();
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.DISABLE_ANALYTICS);
        Settings.DISABLE_ANALYTICS.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void theDeviceStatsReportIsSkippedAndCountedWhileTheSwitchIsOn() {
        Settings.DISABLE_ANALYTICS.save(true);
        assertTrue(Analytics.skipDeviceStats());
        assertEquals(Arrays.asList(
                        "Disable analytics: invoked 1, 0 found, 0 missing. Counted: device stats report skipped 1"),
                HookStatus.report());
    }

    @Test
    public void theDeviceStatsReportGoesOutWithTheSwitchOff() {
        Settings.DISABLE_ANALYTICS.save(false);
        assertFalse(Analytics.skipDeviceStats());
        assertEquals(Arrays.asList("Disable analytics: invoked 1, 0 found, 0 missing"), HookStatus.report());
    }

    @Test
    public void theDeviceStatsReportGoesOutWhilePaused() {
        Settings.DISABLE_ANALYTICS.save(true);
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        assertFalse(Analytics.skipDeviceStats());
        assertEquals(Arrays.asList("Disable analytics: invoked 1, 0 found, 0 missing"), HookStatus.report());
    }

    @Test
    public void theDeviceStatsReportGoesOutBeforeTheSettingsAreReady() {
        Settings.DISABLE_ANALYTICS.save(true);
        SettingsContextRule.withoutContext(() -> assertFalse(Analytics.skipDeviceStats()));
        assertEquals(Arrays.asList("Disable analytics: invoked 1, 0 found, 0 missing"), HookStatus.report());
    }

    @Test
    public void theReadMetricsBatchIsDroppedAndCountedWhileTheSwitchIsOn() {
        Settings.DISABLE_ANALYTICS.save(true);
        List<Object> pending = new ArrayList<>(Arrays.asList("post 1", "post 2"));
        assertTrue(Analytics.skipReadMetrics(pending));
        assertTrue("the batch is emptied as a send would, so it can't pile up", pending.isEmpty());
        assertEquals(Arrays.asList(
                        "Disable analytics: invoked 1, 0 found, 0 missing. Counted: read metrics report skipped 1"),
                HookStatus.report());
    }

    @Test
    public void theReadMetricsBatchGoesOutUntouchedWithTheSwitchOffWhilePausedOrBeforeTheSettingsAreReady() {
        List<Object> pending = new ArrayList<>(Arrays.asList("post 1"));
        Settings.DISABLE_ANALYTICS.save(false);
        assertFalse(Analytics.skipReadMetrics(pending));
        Settings.DISABLE_ANALYTICS.save(true);
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        assertFalse(Analytics.skipReadMetrics(pending));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(Analytics.skipReadMetrics(pending)));
        assertEquals(Arrays.asList("post 1"), pending);
        assertEquals(Arrays.asList("Disable analytics: invoked 3, 0 found, 0 missing"), HookStatus.report());
    }

    @Test
    public void aBatchThatCannotBeEmptiedIsStillNotSent() {
        Settings.DISABLE_ANALYTICS.save(true);
        assertTrue(Analytics.skipReadMetrics(Collections.unmodifiableList(new ArrayList<>(Arrays.asList("post 1")))));
        assertTrue(Analytics.skipReadMetrics(null));
        assertEquals(Arrays.asList(
                        "a working 'read metrics clear' hook (it threw java.lang.UnsupportedOperationException)"),
                HookStatus.missing(FamilyNames.DISABLE_ANALYTICS));
    }

    @Test
    public void aHookThatCannotReadTheSwitchSkipsNothingAndRecordsWhatThrew() {
        Settings.DISABLE_ANALYTICS.save(true);
        SettingReadsForTests.breakReads(Settings.DISABLE_ANALYTICS);
        assertFalse(Analytics.skipDeviceStats());
        assertEquals(Arrays.asList(
                        "a working 'switch read' hook (it threw java.lang.NullPointerException)"),
                HookStatus.missing(FamilyNames.DISABLE_ANALYTICS));
        assertFalse("a throw must not count as a skip", HookStatus.report().get(0).contains("Counted:"));
    }
}
