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

import java.util.Collections;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ChannelRestrictionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Settings.OPEN_RESTRICTED_CHATS.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.OPEN_RESTRICTED_CHATS);
        Settings.OPEN_RESTRICTED_CHATS.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultSoRestrictedChannelsStayClosed() {
        assertFalse(Settings.OPEN_RESTRICTED_CHATS.get());
        assertFalse(ChannelRestrictions.ignoreRestrictionReason());
    }

    @Test public void switchedOnEveryRestrictionReasonIsIgnoredAndCounted() {
        Settings.OPEN_RESTRICTED_CHATS.save(true);
        assertTrue(ChannelRestrictions.ignoreRestrictionReason());
        assertTrue(ChannelRestrictions.ignoreRestrictionReason());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("restriction reason ignored 2"));
    }

    @Test public void theForwardingSwitchAloneLeavesRestrictedChannelsClosed() {
        Settings.BYPASS_CHANNEL_RESTRICTIONS.save(true);
        try {
            assertFalse(ChannelRestrictions.ignoreRestrictionReason());
        } finally {
            Settings.BYPASS_CHANNEL_RESTRICTIONS.resetToDefault();
        }
    }

    @Test public void everyPauseReasonKeepsRestrictedChannelsClosed() {
        Settings.OPEN_RESTRICTED_CHATS.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), ChannelRestrictions.ignoreRestrictionReason());
            PauseForTests.resume();
        }
        assertTrue(ChannelRestrictions.ignoreRestrictionReason());
    }

    @Test public void unavailableSettingsKeepRestrictedChannelsClosed() {
        Settings.OPEN_RESTRICTED_CHATS.save(true);
        SettingsContextRule.withoutContext(() -> assertFalse(ChannelRestrictions.ignoreRestrictionReason()));
    }

    @Test public void unreadableSettingFailsClosedAndReportsItsStateFailure() {
        Settings.OPEN_RESTRICTED_CHATS.save(true);
        SettingReadsForTests.breakReads(Settings.OPEN_RESTRICTED_CHATS);
        assertFalse(ChannelRestrictions.ignoreRestrictionReason());
        assertEquals(Collections.singletonList("a working 'switch read' hook (it threw java.lang.NullPointerException)"),
                HookStatus.missing(FamilyNames.BYPASS_CHANNEL_RESTRICTIONS));
    }
}
