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
public class SendAsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void reset() { restore(); }
    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.HIDE_SEND_AS);
        Settings.HIDE_SEND_AS.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultTheButtonShowsWheneverTelegramShowsIt() {
        assertFalse(Settings.HIDE_SEND_AS.get());
        assertTrue(SendAs.show(new Object(), true));
        assertFalse(SendAs.show(new Object(), false));
        assertTrue(String.join("\n", HookStatus.report()).contains(FamilyNames.HIDE_SEND_AS));
    }

    @Test public void onOnlyYouPostingAsYourselfHidesIt() {
        Settings.HIDE_SEND_AS.save(true);
        assertTrue(SendAs.hides(true));
        assertTrue(String.join("\n", HookStatus.report()).contains("Send as button hidden"));
        // A channel you'd post as keeps the picture, so its name is always in sight.
        assertFalse(SendAs.hides(false));
        // The unpatched stub calls nothing a person, so the button stays as Telegram decided.
        assertTrue(SendAs.show(new Object(), true));
    }

    @Test public void aButtonTelegramHidesOrANullIdentityIsLeftAlone() {
        Settings.HIDE_SEND_AS.save(true);
        assertFalse(SendAs.show(new Object(), false));
        assertTrue(SendAs.show(null, true));
        assertFalse(SendAs.show(null, false));
    }

    @Test public void pausingAnEarlyStartOrAnUnreadableSwitchKeepsTheButton() {
        Settings.HIDE_SEND_AS.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), SendAs.on());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse(SendAs.on()));
        SettingReadsForTests.breakReads(Settings.HIDE_SEND_AS);
        assertFalse(SendAs.on());
        assertFalse(HookStatus.missing(FamilyNames.HIDE_SEND_AS).isEmpty());
    }
}
