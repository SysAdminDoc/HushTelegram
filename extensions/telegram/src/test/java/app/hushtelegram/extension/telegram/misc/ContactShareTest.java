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
public class ContactShareTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        ContactShare.resetForTests();
        Settings.SHARE_NUMBER_UNTICKED.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.SHARE_NUMBER_UNTICKED);
        Settings.SHARE_NUMBER_UNTICKED.resetToDefault();
        ContactShare.resetForTests();
        HookStatus.clear();
    }

    @Test public void offByDefaultSoTheBoxIsTickedAsTelegramTicksIt() {
        assertFalse(Settings.SHARE_NUMBER_UNTICKED.get());
        Object screen = new Object();
        assertTrue(ContactShare.startsTicked(screen, true, false));
        // A view built again goes Telegram's way too, ticking the box again.
        assertTrue(ContactShare.startsTicked(screen, true, false));
        assertEquals(Collections.singletonList("Hide phone number: invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    @Test public void switchedOnANewScreenStartsClear() {
        Settings.SHARE_NUMBER_UNTICKED.save(true);
        assertFalse(ContactShare.startsTicked(new Object(), true, false));
        assertEquals(Collections.singletonList("Hide phone number: invoked 1, 0 found, 0 missing. Counted: "
                + "number sharing started unticked 1"), HookStatus.report());
    }

    // The person ticks the box, the view is built again (a theme change, say), and the tick stays.
    @Test public void aViewBuiltAgainKeepsWhatThePersonChose() {
        Settings.SHARE_NUMBER_UNTICKED.save(true);
        Object screen = new Object();
        assertFalse(ContactShare.startsTicked(screen, true, false));
        assertTrue(ContactShare.startsTicked(screen, true, true));
        assertFalse(ContactShare.startsTicked(screen, true, false));
        // Another screen is new, so it starts clear.
        assertFalse(ContactShare.startsTicked(new Object(), true, true));
    }

    @Test public void everyPauseReasonTicksTheBoxAsTelegramDoes() {
        Settings.SHARE_NUMBER_UNTICKED.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertTrue(reason.name(), ContactShare.startsTicked(new Object(), true, false));
            PauseForTests.resume();
        }
        assertFalse(ContactShare.startsTicked(new Object(), true, false));
    }

    @Test public void unavailableOrUnreadableSettingsTickTheBoxAndSaySo() {
        Settings.SHARE_NUMBER_UNTICKED.save(true);
        SettingsContextRule.withoutContext(() -> assertTrue(ContactShare.startsTicked(new Object(), true, false)));
        SettingReadsForTests.breakReads(Settings.SHARE_NUMBER_UNTICKED);
        assertTrue(ContactShare.startsTicked(new Object(), true, false));
        assertEquals(Collections.singletonList("a working 'share box switch read' hook (it threw java.lang.NullPointerException)"),
                HookStatus.missing(FamilyNames.HIDE_PHONE_NUMBER));
    }
}
