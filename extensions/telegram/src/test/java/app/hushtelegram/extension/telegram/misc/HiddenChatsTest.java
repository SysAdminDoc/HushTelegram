/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertArrayEquals;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class HiddenChatsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private HiddenChatsForTests.Fake telegram;

    @Before public void setUp() {
        // The chat list, the archive (folder 1) and a folder of its own, with one tab's list.
        telegram = HiddenChatsForTests.use(new HiddenChatsForTests.Fake().folder(0, 11, 12, 13).folder(1, 14).folder(2, 12));
        HiddenChatsForTests.Filter tab = new HiddenChatsForTests.Filter();
        tab.dialogs.add(new HiddenChatsForTests.Dialog(11));
        tab.dialogs.add(new HiddenChatsForTests.Dialog(12));
        telegram.filters = new Object[]{tab, null};
        telegram.users.put(0, 1000L);
        telegram.users.put(1, 2000L);
        Settings.HIDDEN_CHATS.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        HiddenChatsForTests.restore();
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.HIDDEN_CHATS);
        Settings.HIDDEN_CHATS.resetToDefault();
        HookStatus.clear();
    }

    private String report() {
        return String.join("\n", HookStatus.report());
    }

    private List<String> tab() {
        return Collections.singletonList(String.valueOf(telegram.filterDialogs(telegram.filters[0])));
    }

    private static LinkedHashSet<Long> ids(Long... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }

    @Test public void offByDefaultEveryChatStays() {
        HiddenChats.hide(1000, ids(12L));
        assertFalse(Settings.HIDDEN_CHATS.get());
        HiddenChats.prune(new Object());
        assertEquals(Arrays.asList("0: [11, 12, 13]", "1: [14]", "2: [12]"), telegram.lists());
        assertTrue(report(), report().contains(FamilyNames.HIDE_FOLDER_COUNTERS + ": invoked 1"));
        assertFalse(report(), report().contains(HiddenChats.LEFT_OUT));
    }

    @Test public void onTheHiddenChatsLeaveEveryFolderAndTab() {
        Settings.HIDDEN_CHATS.save(true);
        HiddenChats.hide(1000, ids(12L, 14L));
        HiddenChats.prune(new Object());
        // The folder that held only a hidden chat is left empty, so Telegram drops it with the other empty ones.
        assertEquals(Arrays.asList("0: [11, 13]", "1: []", "2: []"), telegram.lists());
        assertEquals(Collections.singletonList("[11]"), tab());
        assertTrue(report(), report().contains(HiddenChats.LEFT_OUT));
        // A sort with nothing to leave out counts nothing.
        HookStatus.clear();
        HiddenChats.prune(new Object());
        assertFalse(report(), report().contains(HiddenChats.LEFT_OUT));
    }

    @Test public void eachAccountKeepsItsOwnHiddenChats() {
        Settings.HIDDEN_CHATS.save(true);
        HiddenChats.hide(1000, ids(11L));
        HiddenChats.hide(2000, ids(13L));
        assertEquals(ids(11L), HiddenChats.hidden(1000));
        assertEquals(ids(13L), HiddenChats.hidden(2000));
        // The sort reads the user its own controller belongs to, not the account shown.
        telegram.controllerUser = 2000;
        telegram.selected = 0;
        HiddenChats.prune(new Object());
        assertEquals("0: [11, 12]", telegram.lists().get(0));
        // No one signed in hides nothing and saves nothing.
        telegram.controllerUser = 0;
        assertFalse(HiddenChats.hide(0, ids(11L)));
        assertEquals(Collections.emptySet(), HiddenChats.hidden(0));
        // An empty pick clears the account's list.
        assertTrue(HiddenChats.hide(1000, Collections.emptySet()));
        assertEquals(Collections.emptySet(), HiddenChats.hidden(1000));
        assertEquals(ids(13L), HiddenChats.hidden(2000));
    }

    @Test public void pauseMissingContextAndBrokenReadsLeaveEveryChat() {
        Settings.HIDDEN_CHATS.save(true);
        HiddenChats.hide(1000, ids(12L));
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            HiddenChats.prune(new Object());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> HiddenChats.prune(new Object()));
        SettingReadsForTests.breakReads(Settings.HIDDEN_CHATS);
        HiddenChats.prune(new Object());
        SettingReadsForTests.mend(Settings.HIDDEN_CHATS);
        assertEquals(Arrays.asList("0: [11, 12, 13]", "1: [14]", "2: [12]"), telegram.lists());
        // Telegram's side changing under the hook leaves the lists as they were and says so.
        HiddenChatsForTests.use(new HiddenChatsForTests.Fake() {
            @Override public long dialogId(Object dialog) { throw new IllegalStateException("Telegram changed"); }
        }.folder(0, 12));
        HiddenChats.prune(new Object());
        assertTrue(report(), report().contains("hidden chats"));
        assertFalse(report(), report().contains(HiddenChats.LEFT_OUT));
    }

    @Test public void theSettingsPageSeesTheChatsArchivedOnesAndHiddenOnesTelegramDropped() {
        telegram.chats(0, 0, 11, 12).chats(0, 1, 14, 12);
        telegram.names.put(11L, "Alice");
        telegram.names.put(12L, "  ");
        telegram.names.put(14L, "Old news");
        HiddenChats.hide(1000, ids(14L, 99L));
        HiddenChats.Shown shown = HiddenChats.shown();
        assertEquals(1000L, shown.user);
        assertEquals(0, shown.account);
        List<String> names = new ArrayList<>();
        for (HiddenChats.Chat chat : shown.chats) names.add(chat.id + "=" + chat.name);
        // Each once, a blank name falls back to the id, and a hidden chat Telegram doesn't list now still shows to untick.
        assertEquals(Arrays.asList("11=Alice", "12=12", "14=Old news", "99=99"), names);
        assertArrayEquals(new boolean[]{false, false, true, true}, shown.ticked());
        // Another account shows its own.
        telegram.selected = 1;
        assertEquals(2000L, HiddenChats.shown().user);
        assertEquals(Collections.emptySet(), HiddenChats.shown().hidden);
        // Telegram's side can't be read: the page hears it, nothing throws.
        telegram.broken = true;
        assertNull(HiddenChats.shown());
        assertTrue(report(), report().contains("chat list for hiding"));
        // The report names the first hook that broke, so the refresh's own failure gets a clean slate.
        HookStatus.clear();
        HiddenChats.refreshShown();
        assertTrue(report(), report().contains("chat list refresh"));
    }

    @Test public void aRefreshSortsTheAccountAgain() {
        HiddenChats.refresh(1);
        telegram.selected = 0;
        HiddenChats.refreshShown();
        assertEquals(Arrays.asList(1, 0), telegram.resorted);
    }
}
