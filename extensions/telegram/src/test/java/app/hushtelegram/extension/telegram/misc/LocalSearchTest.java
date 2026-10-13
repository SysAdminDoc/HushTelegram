/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
public class LocalSearchTest {
    private static final String STOCK = "There were no results for \"cat\".\nTry a new search.";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Settings.LOCAL_SEARCH.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.LOCAL_SEARCH);
        Settings.LOCAL_SEARCH.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultSoEveryLookupGoesAsTelegramSendsIt() {
        assertFalse(Settings.LOCAL_SEARCH.get());
        assertFalse(LocalSearch.keep());
        assertEquals("cat", LocalSearch.sent("cat"));
        assertEquals(STOCK, LocalSearch.emptyText(STOCK));
        assertEquals(Collections.singletonList("Disable draft link previews: invoked 3, 0 found, 0 missing"), HookStatus.report());
    }

    @Test public void switchedOnNoLookupLeavesAndEachIsCounted() {
        Settings.LOCAL_SEARCH.save(true);
        assertTrue(LocalSearch.keep());
        assertNull(LocalSearch.sent("cat"));
        assertNull(LocalSearch.sent("#cats"));
        assertEquals(Collections.singletonList("Disable draft link previews: invoked 3, 0 found, 0 missing. Counted: "
                + "search lookup kept on the phone 3"), HookStatus.report());
    }

    // An empty box is already null in Telegram, and there's nothing to keep back or count.
    @Test public void anEmptyBoxStaysNullAndIsntCounted() {
        Settings.LOCAL_SEARCH.save(true);
        assertNull(LocalSearch.sent(null));
        assertNull(LocalSearch.emptyText(null));
        assertEquals(Collections.emptyList(), HookStatus.report());
    }

    @Test public void theEmptyResultSaysWhereItLooked() {
        Settings.LOCAL_SEARCH.save(true);
        assertEquals(STOCK + "\nOnly chats and contacts on this phone were searched.", LocalSearch.emptyText(STOCK));
    }

    @Test public void everyPauseReasonSearchesAsTelegramDoes() {
        Settings.LOCAL_SEARCH.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), LocalSearch.keep());
            assertEquals(reason.name(), "cat", LocalSearch.sent("cat"));
            assertEquals(reason.name(), STOCK, LocalSearch.emptyText(STOCK));
            PauseForTests.resume();
        }
        assertTrue(LocalSearch.keep());
    }

    @Test public void unavailableOrUnreadableSettingsSearchAsTelegramDoesAndSaySo() {
        Settings.LOCAL_SEARCH.save(true);
        SettingsContextRule.withoutContext(() -> {
            assertFalse(LocalSearch.keep());
            assertEquals("cat", LocalSearch.sent("cat"));
            assertEquals(STOCK, LocalSearch.emptyText(STOCK));
        });
        SettingReadsForTests.breakReads(Settings.LOCAL_SEARCH);
        assertFalse(LocalSearch.keep());
        assertEquals("cat", LocalSearch.sent("cat"));
        assertEquals(STOCK, LocalSearch.emptyText(STOCK));
        assertEquals(Collections.singletonList("a working 'local search switch read' hook (it threw java.lang.NullPointerException)"),
                HookStatus.missing(FamilyNames.DISABLE_DRAFT_PREVIEWS));
    }
}
