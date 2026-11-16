/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
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
public class SessionDeviceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Settings.SESSION_DEVICE_NAME.resetToDefault();
        Settings.SESSION_SYSTEM_VERSION.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        Settings.SESSION_DEVICE_NAME.resetToDefault();
        Settings.SESSION_SYSTEM_VERSION.resetToDefault();
        HookStatus.clear();
    }

    @Test public void emptyBoxesSendWhatTelegramBuilt() {
        assertEquals("samsungSM-S901U", SessionDevice.model("samsungSM-S901U"));
        assertEquals("SDK 36", SessionDevice.system("SDK 36"));
        Settings.SESSION_DEVICE_NAME.save("   \n ");
        assertEquals("only spaces is empty", "samsungSM-S901U", SessionDevice.model("samsungSM-S901U"));
    }

    @Test public void savedValuesGoOutCleaned() {
        Settings.SESSION_DEVICE_NAME.save(" Office\r\n\tPC ");
        Settings.SESSION_SYSTEM_VERSION.save("Windows 11");
        assertEquals("Office PC", SessionDevice.model("samsungSM-S901U"));
        assertEquals("Windows 11", SessionDevice.system("SDK 36"));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("session device name changed"));
        assertTrue(report, report.contains("session system version changed"));
    }

    @Test public void withoutSettingsTelegramsOwnGoOut() {
        Settings.SESSION_DEVICE_NAME.save("Office PC");
        SettingsContextRule.withoutContext(() -> assertEquals("samsungSM-S901U", SessionDevice.model("samsungSM-S901U")));
    }

    @Test public void aReadThatComesBackEmptySendsTelegramsOwn() {
        Settings.SESSION_SYSTEM_VERSION.save("Windows 11");
        SettingReadsForTests.breakReads(Settings.SESSION_SYSTEM_VERSION);
        try {
            assertEquals("SDK 36", SessionDevice.system("SDK 36"));
        } finally {
            SettingReadsForTests.mend(Settings.SESSION_SYSTEM_VERSION);
        }
        String report = String.join("\n", HookStatus.report());
        assertFalse(report, report.contains("session system version changed"));
        assertEquals("the saved value comes back once the read does", "Windows 11", SessionDevice.system("SDK 36"));
    }

    @Test public void cleanKeepsOneLineAndCutsWholeCharacters() {
        assertEquals("", SessionDevice.clean(null));
        assertEquals("", SessionDevice.clean(" \t\n  "));
        assertEquals("a b", SessionDevice.clean("a\u0000  b"));
        // A family emoji is one character to the eye, made of joiners that stay.
        String family = "👨‍👩‍👧";
        assertEquals(family + " PC", SessionDevice.clean(family + "  PC"));

        StringBuilder full = new StringBuilder();
        for (int i = 0; i < SessionDevice.MAX_LENGTH - 1; i++) full.append('x');
        // One char short of the limit, and the next is a pair that doesn't fit, so it goes whole.
        assertEquals(full.toString(), SessionDevice.clean(full + "😀"));
        assertEquals(full + "y", SessionDevice.clean(full + "yz"));
        assertEquals("the space past the limit isn't kept", full.toString(), SessionDevice.clean(full + " z"));
    }

    @Test public void stockValuesFollowTheConstructor() {
        assertEquals("SDK 30", SessionDevice.stockSystem());
        assertTrue(SessionDevice.stockModel(), !SessionDevice.stockModel().trim().isEmpty());
    }
}
