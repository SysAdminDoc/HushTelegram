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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_chatlists;
import org.telegram.tgnet.tl.TL_stories;

import java.util.Arrays;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class NewChatsBlockTest {
    private static final String TOAST = "New chats are blocked in HushTelegram settings.";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Settings.BLOCK_NEW_CHATS.resetToDefault();
        HookStatus.clear();
        ShadowToast.reset();
    }

    @After public void tearDown() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.BLOCK_NEW_CHATS);
        Settings.BLOCK_NEW_CHATS.resetToDefault();
        HookStatus.clear();
    }

    private static String toast() {
        ShadowLooper.idleMainLooper();
        return ShadowToast.getTextOfLatestToast();
    }

    private static Object[] joins() {
        return new Object[]{new TLRPC.TL_channels_joinChannel(), new TLRPC.TL_messages_importChatInvite(),
                new TLRPC.TL_messages_checkChatInvite(), new TL_chatlists.TL_chatlists_joinChatlistInvite(),
                new TL_chatlists.TL_chatlists_checkChatlistInvite(), new TL_chatlists.TL_chatlists_joinChatlistUpdates()};
    }

    @Test public void offByDefaultSoChatsJoinAndOpenAsTelegramsDo() {
        assertFalse(Settings.BLOCK_NEW_CHATS.get());
        for (Object join : joins()) assertFalse(join.getClass().getName(), NewChatsBlock.drop(join));
        assertFalse(NewChatsBlock.refuseOpen());
        assertNull(toast());
        assertTrue(HookStatus.report().stream().noneMatch(row -> row.contains("Counted:")));
    }

    @Test public void everyJoinRequestIsKnownByItsTelegramName() {
        for (String join : Arrays.asList("org.telegram.tgnet.TLRPC$TL_channels_joinChannel",
                "org.telegram.tgnet.TLRPC$TL_messages_importChatInvite", "org.telegram.tgnet.TLRPC$TL_messages_checkChatInvite",
                "org.telegram.tgnet.tl.TL_chatlists$TL_chatlists_joinChatlistInvite",
                "org.telegram.tgnet.tl.TL_chatlists$TL_chatlists_checkChatlistInvite",
                "org.telegram.tgnet.tl.TL_chatlists$TL_chatlists_joinChatlistUpdates")) {
            assertTrue(join, NewChatsBlock.joins(join));
        }
        // Leaving, reading, sending, a shared folder's update check and lookups by name all go out as usual.
        for (String other : Arrays.asList("org.telegram.tgnet.TLRPC$TL_channels_leaveChannel",
                "org.telegram.tgnet.TLRPC$TL_messages_readHistory", "org.telegram.tgnet.TLRPC$TL_messages_sendMessage",
                "org.telegram.tgnet.TLRPC$TL_contacts_resolveUsername", "org.telegram.tgnet.TLRPC$TL_channels_getChannels",
                "org.telegram.tgnet.tl.TL_chatlists$TL_chatlists_getChatlistUpdates",
                "org.telegram.tgnet.tl.TL_chatlists$TL_chatlists_leaveChatlist", "TL_channels_joinChannel",
                "org.telegram.tgnet.TLRPC$TL_channels_joinChannelX", "")) {
            assertFalse(other, NewChatsBlock.joins(other));
        }
    }

    @Test public void onDropsEveryJoinCountsEachAndSaysWhy() {
        Settings.BLOCK_NEW_CHATS.save(true);
        for (Object join : joins()) assertTrue(join.getClass().getName(), NewChatsBlock.drop(join));
        assertEquals(TOAST, toast());
        assertEquals(Arrays.asList("Hide popular apps: invoked 6, 0 found, 0 missing. Counted: joins blocked 6"),
                HookStatus.report());
    }

    @Test public void onRefusesToOpenAChatYouAreNotInAndSaysWhy() {
        Settings.BLOCK_NEW_CHATS.save(true);
        assertTrue(NewChatsBlock.refuseOpen());
        assertEquals(TOAST, toast());
        assertEquals(Arrays.asList("Hide popular apps: invoked 1, 0 found, 0 missing. Counted: chats not opened 1"),
                HookStatus.report());
    }

    @Test public void anyOtherRequestGoesOutWithoutReadingASettingOrCountingAnything() {
        Settings.BLOCK_NEW_CHATS.save(true);
        assertFalse(NewChatsBlock.drop(new TLRPC.TL_channels_leaveChannel()));
        assertFalse(NewChatsBlock.drop(new TLRPC.TL_auth_sendCode()));
        assertFalse(NewChatsBlock.drop(new TL_chatlists.TL_chatlists_getChatlistUpdates()));
        assertFalse(NewChatsBlock.drop(new TL_stories.TL_stories_readStories()));
        assertFalse(NewChatsBlock.drop(null));
        SettingReadsForTests.breakReads(Settings.BLOCK_NEW_CHATS);
        assertFalse(NewChatsBlock.drop(new TLRPC.TL_auth_sendCode()));
        SettingsContextRule.withoutContext(() -> assertFalse(NewChatsBlock.drop(new TLRPC.TL_auth_sendCode())));
        assertNull(toast());
        assertTrue(HookStatus.report().isEmpty());
        assertTrue(HookStatus.missing(FamilyNames.HIDE_POPULAR_APPS).isEmpty());
    }

    @Test public void everyPauseReasonLetsChatsJoinAndOpenAndResumeBlocksThemAgain() {
        Settings.BLOCK_NEW_CHATS.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), NewChatsBlock.drop(new TLRPC.TL_channels_joinChannel()));
            assertFalse(reason.name(), NewChatsBlock.refuseOpen());
            PauseForTests.resume();
        }
        assertNull(toast());
        assertTrue(HookStatus.report().stream().noneMatch(row -> row.contains("Counted:")));
        assertTrue(NewChatsBlock.drop(new TLRPC.TL_channels_joinChannel()));
        assertTrue(NewChatsBlock.refuseOpen());
    }

    @Test public void unavailableSettingsLetChatsJoinAndOpen() {
        Settings.BLOCK_NEW_CHATS.save(true);
        SettingsContextRule.withoutContext(() -> {
            assertFalse(NewChatsBlock.drop(new TLRPC.TL_messages_importChatInvite()));
            assertFalse(NewChatsBlock.refuseOpen());
        });
        assertTrue(HookStatus.report().stream().noneMatch(row -> row.contains("Counted:")));
    }

    @Test public void anUnreadableSwitchLetsChatsJoinAndOpenAndSaysSo() {
        Settings.BLOCK_NEW_CHATS.save(true);
        SettingReadsForTests.breakReads(Settings.BLOCK_NEW_CHATS);
        assertFalse(NewChatsBlock.drop(new TLRPC.TL_messages_checkChatInvite()));
        assertFalse(NewChatsBlock.refuseOpen());
        assertEquals(Arrays.asList("a working 'new chats block switch read' hook (it threw java.lang.NullPointerException)"),
                HookStatus.missing(FamilyNames.HIDE_POPULAR_APPS));
        assertNull(toast());
        assertTrue(HookStatus.report().stream().noneMatch(row -> row.contains("Counted:")));
    }
}
