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
public class HideReactionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void reset() { restore(); }
    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.HIDE_REACTION_ROWS);
        SettingReadsForTests.mend(Settings.HIDE_REACTION_PICKER);
        Settings.HIDE_REACTION_ROWS.resetToDefault();
        Settings.HIDE_REACTION_PICKER.resetToDefault();
        HookStatus.clear();
    }

    @Test public void bothSwitchesStartOffAndLeaveTelegramAlone() {
        assertFalse(Settings.HIDE_REACTION_ROWS.get());
        assertFalse(Settings.HIDE_REACTION_PICKER.get());
        Object message = new Object();
        assertSame(message, DoubleTapReactions.rows(message, false, false));
        assertTrue(DoubleTapReactions.picker(true));
        assertFalse(DoubleTapReactions.picker(false));
    }

    @Test public void rowsGoAwayForMessagesButNotForTagsOrNoMessage() {
        Settings.HIDE_REACTION_ROWS.save(true);
        Object message = new Object();
        assertNull(DoubleTapReactions.rows(message, false, false));
        assertNull(DoubleTapReactions.rows(message, true, false));
        assertSame("Saved Messages tags are another feature", message, DoubleTapReactions.rows(message, false, true));
        assertNull(DoubleTapReactions.rows(null, false, false));
        assertTrue("the picker is its own switch", DoubleTapReactions.picker(true));
    }

    @Test public void pickerSwitchOnlyTurnsAnAvailablePickerOff() {
        Settings.HIDE_REACTION_PICKER.save(true);
        assertFalse(DoubleTapReactions.picker(true));
        assertFalse(DoubleTapReactions.picker(false));
        Object message = new Object();
        assertSame("the rows are their own switch", message, DoubleTapReactions.rows(message, false, false));
    }

    @Test public void pausedAndBrokenReadsFallBackToStock() {
        Settings.HIDE_REACTION_ROWS.save(true);
        Settings.HIDE_REACTION_PICKER.save(true);
        Object message = new Object();
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        assertSame(message, DoubleTapReactions.rows(message, false, false));
        assertTrue(DoubleTapReactions.picker(true));
        PauseForTests.resume();
        SettingReadsForTests.breakReads(Settings.HIDE_REACTION_ROWS);
        SettingReadsForTests.breakReads(Settings.HIDE_REACTION_PICKER);
        assertSame(message, DoubleTapReactions.rows(message, false, false));
        assertTrue(DoubleTapReactions.picker(true));
        assertFalse(HookStatus.missing(FamilyNames.DISABLE_DOUBLE_TAP_REACTIONS).isEmpty());
    }
}
