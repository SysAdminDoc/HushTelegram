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
public class ChannelButtonsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    // Telegram 13.0.1's numbers: Search 0, Gift 1, Direct messages 2, Info 3.
    private static final int SIDES = 1 | 1 << 2 | 1 << 3;

    @Before public void reset() { restore(); }
    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.HIDE_CHANNEL_BUTTONS);
        Settings.HIDE_CHANNEL_BUTTONS.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultEveryButtonStays() {
        assertFalse(Settings.HIDE_CHANNEL_BUTTONS.get());
        for (int button = 0; button < 4; button++) assertFalse(String.valueOf(button), ChannelButtons.hidden(button, SIDES));
        assertTrue(String.join("\n", HookStatus.report()).contains(FamilyNames.HIDE_CHANNEL_BUTTONS));
    }

    @Test public void onOnlyTheSideButtonsGoAndGiftIsLeftToItsOwnSwitch() {
        Settings.HIDE_CHANNEL_BUTTONS.save(true);
        assertTrue(ChannelButtons.hidden(0, SIDES));
        assertFalse(ChannelButtons.hidden(1, SIDES));
        assertTrue(ChannelButtons.hidden(2, SIDES));
        assertTrue(ChannelButtons.hidden(3, SIDES));
        assertTrue(String.join("\n", HookStatus.report()).contains("channel bar button hidden"));
    }

    @Test public void aNumberOutsideTheBarOrAnUnpatchedBuildHidesNothing() {
        Settings.HIDE_CHANNEL_BUTTONS.save(true);
        assertFalse(ChannelButtons.hidden(-1, SIDES));
        assertFalse(ChannelButtons.hidden(31, -1));
        assertFalse(ChannelButtons.hidden(40, -1));
        // Unpatched, the extension knows no side buttons, so the bar is left as Telegram has it.
        assertEquals(0, ChannelButtons.sideButtons());
        assertFalse(ChannelButtons.hidden(0, ChannelButtons.sideButtons()));
        ChannelButtons.set(new Object(), 0, true, false);
    }

    @Test public void pausingAnEarlyStartOrAnUnreadableSwitchKeepsTheButtons() {
        Settings.HIDE_CHANNEL_BUTTONS.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), ChannelButtons.hidden(0, SIDES));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse(ChannelButtons.on()));
        SettingReadsForTests.breakReads(Settings.HIDE_CHANNEL_BUTTONS);
        assertFalse(ChannelButtons.hidden(0, SIDES));
        assertFalse(HookStatus.missing(FamilyNames.HIDE_CHANNEL_BUTTONS).isEmpty());
    }
}
