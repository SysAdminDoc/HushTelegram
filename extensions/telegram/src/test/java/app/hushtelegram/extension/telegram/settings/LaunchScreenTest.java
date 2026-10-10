/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.telegram.misc.BlackThemeForTests;

/** The launch screen AMOLED black asks Android for, through the hooks that reach it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class LaunchScreenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int BLACK = BlackThemeForTests.BLACK_LAUNCH_SCREEN;
    private static final int TELEGRAMS = 0;

    private final List<Integer> asked = new ArrayList<>();
    private Activity activity;

    @Before public void watch() {
        BlackThemeForTests.watchLaunchScreens(asked);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.AMOLED_BLACK);
        RuntimeEnvironment.setQualifiers("+night");
        activity = Robolectric.buildActivity(Activity.class).get();
    }

    @After public void restore() {
        BlackThemeForTests.stopWatchingLaunchScreens();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        Settings.AMOLED_BLACK.resetToDefault();
        HookStatus.clear();
    }

    @Test public void aDarkPhoneWithTheSwitchOnStartsBlackNextTimeAndIsAskedOnce() {
        Settings.AMOLED_BLACK.save(true);
        SettingsEntry.onActivityCreate(activity);
        stopped();
        SettingsEntry.onActivityCreate(activity);
        assertEquals(Collections.singletonList(BLACK), asked);
        assertTrue(String.join("\n", HookStatus.report()).contains("launch screen turned black 1"));
    }

    @Test public void offPausedOrALightPhoneGetsTelegramsOwnBack() {
        Settings.AMOLED_BLACK.save(true);
        SettingsEntry.onActivityCreate(activity);
        Settings.AMOLED_BLACK.save(false);
        stopped();
        Settings.AMOLED_BLACK.save(true);
        stopped();
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        stopped();
        PauseForTests.resume();
        stopped();
        RuntimeEnvironment.setQualifiers("+notnight");
        stopped();
        assertEquals(Arrays.asList(BLACK, TELEGRAMS, BLACK, TELEGRAMS, BLACK, TELEGRAMS), asked);
    }

    @Test public void withNothingSavedTelegramsOwnIsAskedForOnce() {
        // A black launch screen kept by Android after Telegram's data was cleared goes on the first start.
        SettingsEntry.onActivityCreate(activity);
        stopped();
        assertEquals(Collections.singletonList(TELEGRAMS), asked);
    }

    @Test public void aBuildWithoutThePatchNeverStartsBlack() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        Settings.AMOLED_BLACK.save(true);
        SettingsEntry.onActivityCreate(activity);
        stopped();
        assertEquals(Collections.singletonList(TELEGRAMS), asked);
    }

    @Test @Config(sdk = 30) public void androidBefore13IsLeftAlone() {
        Settings.AMOLED_BLACK.save(true);
        SettingsEntry.onActivityCreate(activity);
        stopped();
        assertEquals(Collections.emptyList(), asked);
    }

    private void stopped() {
        new SettingsEntry.OpenWhenResumed().onActivityStopped(activity);
    }
}
