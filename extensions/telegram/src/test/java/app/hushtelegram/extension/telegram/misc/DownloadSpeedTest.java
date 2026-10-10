/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class DownloadSpeedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void reset() { restore(); }
    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.FASTER_DOWNLOADS);
        Settings.FASTER_DOWNLOADS.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultTheServersFlagDecides() {
        assertFalse(Settings.FASTER_DOWNLOADS.get());
        assertFalse(DownloadSpeed.fast(false));
        assertTrue(DownloadSpeed.fast(true));
        assertTrue(String.join("\n", HookStatus.report()).contains(FamilyNames.FASTER_DOWNLOADS));
    }

    @Test public void onEveryDownloadTakesTheBigPieces() {
        Settings.FASTER_DOWNLOADS.save(true);
        assertTrue(DownloadSpeed.fast(false));
        assertTrue(DownloadSpeed.fast(true));
    }

    @Test public void pausingAnEarlyStartOrAnUnreadableSwitchLeavesTheServersChoice() {
        Settings.FASTER_DOWNLOADS.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), DownloadSpeed.fast(false));
            // An account the server already flagged keeps its big pieces.
            assertTrue(reason.name(), DownloadSpeed.fast(true));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertFalse(DownloadSpeed.fast(false));
            assertTrue(DownloadSpeed.fast(true));
        });
        SettingReadsForTests.breakReads(Settings.FASTER_DOWNLOADS);
        assertFalse(DownloadSpeed.fast(false));
        assertFalse(HookStatus.missing(FamilyNames.FASTER_DOWNLOADS).isEmpty());
    }
}
