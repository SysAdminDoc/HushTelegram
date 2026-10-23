/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.pm.ActivityInfo;

/** Asks {@link VideoTurn} about a full screen video from tests outside this package. */
public final class VideoTurnForTests {
    private VideoTurnForTests() {}

    /** Whether a video put in full screen now could turn to either landscape side. */
    public static boolean turnsEitherWay() {
        return VideoTurn.orientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
    }
}
