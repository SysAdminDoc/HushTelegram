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
public class TestServersTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void reset() { restore(); }
    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.TEST_SERVER_SIGN_IN);
        Settings.TEST_SERVER_SIGN_IN.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultTelegramDecides() {
        assertFalse(Settings.TEST_SERVER_SIGN_IN.get());
        assertFalse(TestServers.shown(0));
        assertTrue(TestServers.shown(1));
        assertTrue(String.join("\n", HookStatus.report()).contains(FamilyNames.TEST_SERVER_SIGN_IN));
    }

    @Test public void onTheBoxShowsForEveryAccount() {
        Settings.TEST_SERVER_SIGN_IN.save(true);
        assertTrue(TestServers.shown(0));
        assertTrue(TestServers.shown(1));
    }

    @Test public void aTestAccountKeepsItsBoxWhateverHappensToTheSwitch() {
        Settings.TEST_SERVER_SIGN_IN.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), TestServers.shown(0));
            assertTrue(reason.name(), TestServers.shown(1));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertFalse(TestServers.shown(0));
            assertTrue(TestServers.shown(1));
        });
        SettingReadsForTests.breakReads(Settings.TEST_SERVER_SIGN_IN);
        assertTrue(TestServers.shown(1));
        assertFalse(TestServers.shown(0));
        assertFalse(HookStatus.missing(FamilyNames.TEST_SERVER_SIGN_IN).isEmpty());
    }
}
