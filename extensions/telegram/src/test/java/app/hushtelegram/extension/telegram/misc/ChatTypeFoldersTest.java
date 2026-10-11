/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.PatchFamily;
import app.hushtelegram.extension.telegram.settings.PatchFamilyForTests;
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
public class ChatTypeFoldersTest {
    private static final String MADE = "Made Private, Groups, Channels and Bots folders.";
    private static final String REMOVED = "Removed the folders HushTelegram made. Changed ones stay.";
    private static final String NONE_TO_REMOVE = "There were no folders from HushTelegram to remove.";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ChatTypeFolders.Telegram real;
    private ChatTypeFoldersForTests.Recorder telegram;
    private Context context;

    @Before public void setUp() {
        context = Utils.getContext();
        real = ChatTypeFolders.telegram;
        telegram = new ChatTypeFoldersForTests.Recorder();
        ChatTypeFolders.telegram = telegram;
        PatchFamilyForTests.inBuild(PatchFamily.CHAT_TYPE_FOLDERS);
        ChatTypeFolders.prefs(context).edit().clear().commit();
        Settings.CHAT_TYPE_FOLDERS.resetToDefault();
    }

    @After public void restore() {
        ChatTypeFolders.telegram = real;
        PatchFamilyForTests.reset();
        PauseForTests.resume();
        ChatTypeFolders.prefs(context).edit().clear().commit();
        Settings.CHAT_TYPE_FOLDERS.resetToDefault();
        HookStatus.clear();
    }

    private String turn(boolean on) {
        Settings.CHAT_TYPE_FOLDERS.save(on);
        return ChatTypeFolders.apply(context);
    }

    private static List<Integer> sorted(List<Integer> ids) {
        List<Integer> copy = new ArrayList<>(ids);
        Collections.sort(copy);
        return copy;
    }

    @Test public void turningItOnMakesAFolderForEachTypeWithFreeIds() {
        telegram.with(0, 0, "All chats").with(2, 1 | 2 | 4, "Work");

        assertEquals(MADE, turn(true));

        assertEquals(Arrays.asList("0/3/Private/3", "0/4/Groups/4", "0/5/Channels/8", "0/6/Bots/16"), telegram.created);
        assertEquals(4, ChatTypeFolders.prefs(context).getAll().size());
        assertEquals("16:Bots", ChatTypeFolders.prefs(context).getString("1000:6", null));
        assertTrue(Settings.CHAT_TYPE_FOLDERS.savedValue());
        assertTrue(String.join("\n", HookStatus.report()).contains("folder made"));
        // Once, after all four, with the list as the tabs show it, so Telegram's server keeps that order.
        assertEquals(Collections.singletonList(Arrays.asList(0, 2, 3, 4, 5, 6)), telegram.orders);
    }

    @Test public void noOrderIsSentWhenNothingWasMade() {
        telegram.with(2, 1 | 2, "People").with(3, 4, "Groups").with(4, 8, "Channels").with(5, 16, "Bots");
        assertEquals("You already have a folder for each chat type.", turn(true));

        telegram.folders.clear();
        telegram.limit = 0;
        assertEquals("Folder limit reached. Remove a folder and try again.", turn(true));

        turn(false);
        assertTrue(telegram.created.isEmpty());
        assertTrue(telegram.orders.isEmpty());
    }

    @Test public void eachToastFitsTwoLines() {
        // Android shows two lines of a toast from an app that targets 12 or later; about 70 characters fit there.
        for (String said : Arrays.asList(MADE, REMOVED, NONE_TO_REMOVE, "You already have a folder for each chat type.",
                "Folder limit reached. Remove a folder and try again.",
                "Made some folders. The folder limit left no room for the rest.",
                "HushTelegram is paused, so your folders stay as they are.", "Sign in to Telegram first.",
                "Telegram is still loading your folders. Try again in a moment.",
                "Couldn't change your folders. Try again in a moment.")) {
            assertTrue(said, said.length() <= 64);
        }
    }

    @Test public void typesYouAlreadyHaveAFolderForAreSkipped() {
        // Groups with muted ones left out is still a groups folder. Channels and bots together is neither.
        telegram.with(2, 4 | 32, "Mine").with(3, 1 | 2, "People").with(5, 8 | 16, "Feeds");

        assertEquals(MADE, turn(true));
        assertEquals(Arrays.asList("0/4/Channels/8", "0/6/Bots/16"), telegram.created);

        telegram.created.clear();
        assertEquals("You already have a folder for each chat type.", turn(true));
        assertTrue(telegram.created.isEmpty());
        assertTrue(Settings.CHAT_TYPE_FOLDERS.savedValue());
    }

    @Test public void telegramsFolderLimitIsKept() {
        telegram.limit = 3;
        telegram.with(0, 0, "All chats").with(2, 7, "Work");

        assertEquals("Made some folders. The folder limit left no room for the rest.", turn(true));
        assertEquals(Arrays.asList("0/3/Private/3", "0/4/Groups/4"), telegram.created);
        assertEquals(1, telegram.orders.size());

        telegram.created.clear();
        assertEquals("Folder limit reached. Remove a folder and try again.", turn(true));
        assertEquals(1, telegram.orders.size());
        assertTrue(telegram.created.isEmpty());
        assertEquals(2, ChatTypeFolders.prefs(context).getAll().size());
        // Nothing was made, so the switch goes back off.
        assertFalse(Settings.CHAT_TYPE_FOLDERS.savedValue());
    }

    @Test public void turningItOffRemovesOnlyTheFoldersLeftAsTheyWereMade() {
        turn(true);
        telegram.edit(2, 1 | 2, "Friends", 0);
        telegram.edit(3, 4 | 8, "Groups", 0);
        // Chats added, left out or pinned, a color or an emoji: Telegram's list says it isn't plain.
        telegram.edit(4, 8, "Channels", ChatTypeFolders.CHATS_PINNED);

        assertEquals(REMOVED, turn(false));
        assertEquals(Collections.singletonList(5), telegram.deleted);
        assertTrue(ChatTypeFolders.prefs(context).getAll().isEmpty());
        assertTrue(String.join("\n", HookStatus.report()).contains("folder removed"));

        assertEquals(NONE_TO_REMOVE, turn(false));
        assertEquals(1, telegram.deleted.size());
    }

    @Test public void theColorTelegramsServerGivesANewFolderDoesntKeepIt() {
        turn(true);
        // What Telegram's list holds once its server sends the folders back: each with a tag color.
        for (ChatTypeFolders.Folder made : new ArrayList<>(telegram.folders)) {
            telegram.edit(made.id, made.flags, made.name, ChatTypeFolders.COLOR);
        }

        assertEquals(REMOVED, turn(false));
        assertEquals(Arrays.asList(2, 3, 4, 5), sorted(telegram.deleted));
    }

    @Test public void eachThingYouCanSetOnAFolderKeepsIt() {
        int[] each = {ChatTypeFolders.TITLE_EMOJI, ChatTypeFolders.CHATS_ADDED, ChatTypeFolders.CHATS_LEFT_OUT,
                ChatTypeFolders.CHATS_PINNED, ChatTypeFolders.COLOR | ChatTypeFolders.CHATS_ADDED};
        for (int change : each) {
            telegram = new ChatTypeFoldersForTests.Recorder();
            ChatTypeFolders.telegram = telegram;
            turn(true);
            telegram.edit(2, 1 | 2, "Private", change);

            assertEquals(String.valueOf(change), REMOVED, turn(false));
            assertEquals(String.valueOf(change), Arrays.asList(3, 4, 5), sorted(telegram.deleted));
        }
    }

    @Test public void aFolderYouDeletedIsForgottenAndNothingIsSentForItsId() {
        turn(true);
        // Deleted in Telegram, and its id given to a folder made on another device since.
        telegram.folders.removeIf(folder -> folder.id == 5);

        assertEquals(REMOVED, turn(false));
        assertEquals(Arrays.asList(2, 3, 4), sorted(telegram.deleted));
        assertTrue(ChatTypeFolders.prefs(context).getAll().isEmpty());
    }

    @Test public void eachTelegramUserKeepsTheirOwnFolders() {
        telegram.user = 1;
        turn(true);
        assertEquals(4, telegram.created.size());

        // Another user signed in on the same account slot: their folders with the same ids aren't touched.
        telegram.user = 2;
        assertEquals(NONE_TO_REMOVE, turn(false));
        assertTrue(telegram.deleted.isEmpty());
        assertEquals(4, ChatTypeFolders.prefs(context).getAll().size());

        // User 11's records start with "11:", and user 1 still finds only theirs.
        ChatTypeFolders.prefs(context).edit().putString("11:2", "16:Bots").commit();
        telegram.user = 1;
        assertEquals(REMOVED, turn(false));
        assertEquals(Arrays.asList(2, 3, 4, 5), sorted(telegram.deleted));
        assertEquals(Collections.singleton("11:2"), ChatTypeFolders.prefs(context).getAll().keySet());
    }

    @Test public void whenNothingCanBeDoneTheSwitchGoesBack() {
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertEquals(reason.name(), "HushTelegram is paused, so your folders stay as they are.", turn(true));
            assertFalse(reason.name(), Settings.CHAT_TYPE_FOLDERS.savedValue());
            PauseForTests.resume();
        }

        telegram.signedIn = false;
        assertEquals("Sign in to Telegram first.", turn(true));
        assertFalse(Settings.CHAT_TYPE_FOLDERS.savedValue());
        telegram.signedIn = true;

        telegram.loaded = false;
        assertEquals("Telegram is still loading your folders. Try again in a moment.", turn(false));
        assertTrue(Settings.CHAT_TYPE_FOLDERS.savedValue());
        telegram.loaded = true;

        Settings.CHAT_TYPE_FOLDERS.save(true);
        SettingsContextRule.withoutContext(() -> assertNull(ChatTypeFolders.apply(context)));
        assertNull(ChatTypeFolders.apply(null));
        PatchFamilyForTests.inBuild();
        assertNull(ChatTypeFolders.apply(context));

        assertTrue(telegram.created.isEmpty());
        assertTrue(telegram.deleted.isEmpty());
        assertTrue(ChatTypeFolders.prefs(context).getAll().isEmpty());
    }

    @Test public void aFailureIsToldAndReportedAndWhatWasSentIsKept() {
        ChatTypeFolders.telegram = new ChatTypeFoldersForTests.Recorder() {
            @Override public void create(int account, int id, String name, int flags) {
                if (id > 2) throw new IllegalStateException("no connection");
                super.create(account, id, name, flags);
            }
        };

        assertEquals("Couldn't change your folders. Try again in a moment.", turn(true));
        assertFalse(HookStatus.missing(FamilyNames.CHAT_TYPE_FOLDERS).isEmpty());
        assertFalse(Settings.CHAT_TYPE_FOLDERS.savedValue());
        // Private went out before the failure, so turning it off can still find it.
        assertEquals("3:Private", ChatTypeFolders.prefs(context).getString("1000:2", null));
    }

    @Test public void theRealTelegramReadsThroughTheStubs() {
        ChatTypeFolders.telegram = real;
        // Unpatched stubs say signed out, so nothing is sent.
        assertEquals("Sign in to Telegram first.", turn(true));
        assertNull(real.folders(0));
    }
}
