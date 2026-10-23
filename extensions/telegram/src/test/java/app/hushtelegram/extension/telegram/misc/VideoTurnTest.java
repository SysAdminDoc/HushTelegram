/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class VideoTurnTest {
    private static final int LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    private static final int REVERSE = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
    private static final int EITHER = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void reset() { restore(); }
    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.VIDEO_EITHER_LANDSCAPE);
        Settings.VIDEO_EITHER_LANDSCAPE.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultFullScreenKeepsTelegramsSide() {
        assertFalse(Settings.VIDEO_EITHER_LANDSCAPE.get());
        assertEquals(LANDSCAPE, VideoTurn.orientation(LANDSCAPE));
        assertEquals(REVERSE, VideoTurn.orientation(REVERSE));
        assertTrue(String.join("\n", HookStatus.report()).contains(FamilyNames.KEEP_VIDEOS_MUTED));
    }

    @Test public void onTheSensorPicksEitherSideWhicheverSideTelegramPicked() {
        Settings.VIDEO_EITHER_LANDSCAPE.save(true);
        assertEquals(EITHER, VideoTurn.orientation(LANDSCAPE));
        assertEquals(EITHER, VideoTurn.orientation(REVERSE));
        assertTrue(String.join("\n", HookStatus.report()).contains("full screen video free to turn 2"));
    }

    @Test public void theActivityIsAskedForTheChosenOrientation() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            VideoTurn.fullScreen(activity, LANDSCAPE);
            assertEquals("off", LANDSCAPE, activity.getRequestedOrientation());
            Settings.VIDEO_EITHER_LANDSCAPE.save(true);
            VideoTurn.fullScreen(activity, LANDSCAPE);
            assertEquals("on", EITHER, activity.getRequestedOrientation());
        }
    }

    @Test public void pausingOrAnEarlyStartKeepsTelegramsSide() {
        Settings.VIDEO_EITHER_LANDSCAPE.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertEquals(reason.name(), LANDSCAPE, VideoTurn.orientation(LANDSCAPE));
            assertTrue(Settings.VIDEO_EITHER_LANDSCAPE.savedValue());
            PauseForTests.resume();
            assertEquals(reason.name(), EITHER, VideoTurn.orientation(LANDSCAPE));
        }
        SettingsContextRule.withoutContext(() -> assertEquals(LANDSCAPE, VideoTurn.orientation(LANDSCAPE)));
    }

    @Test public void anUnreadableSwitchKeepsTelegramsSideAndReportsIt() {
        Settings.VIDEO_EITHER_LANDSCAPE.save(true);
        SettingReadsForTests.breakReads(Settings.VIDEO_EITHER_LANDSCAPE);
        assertEquals(LANDSCAPE, VideoTurn.orientation(LANDSCAPE));
        assertFalse(HookStatus.missing(FamilyNames.KEEP_VIDEOS_MUTED).isEmpty());
    }
}
