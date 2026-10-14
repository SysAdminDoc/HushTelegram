/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LaunchFolderTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final LaunchFolder.Folder WORK = new LaunchFolder.Folder(3, "Work");
    private static final LaunchFolder.Folder FRIENDS = new LaunchFolder.Folder(7, "Friends");
    private static final LaunchFolder.Folder OLD = new LaunchFolder.Folder(9, "Old");
    private static final LaunchFolder.Folder FAMILY = new LaunchFolder.Folder(5, "Family");

    private final LaunchFolder.Telegram real = LaunchFolder.telegram;
    private Fake telegram;

    /** Telegram as the stubs reach it: one chat list's tabs and arguments, and each account's folders. */
    private static final class Fake implements LaunchFolder.Telegram {
        Bundle args = main();
        final Set<Object> built = new HashSet<>();
        int listAccount = 0;
        int selected = 0;
        final Map<Integer, Long> users = new HashMap<>();
        /** Each account's folders as Telegram lists them: id, name, locked, default. */
        final Map<Integer, List<Object[]>> folders = new HashMap<>();
        /** Each pick, as "account/index". */
        final List<String> picks = new ArrayList<>();
        boolean pages = true;
        boolean broken;

        Fake folder(int account, int id, String name, boolean locked) {
            folders.computeIfAbsent(account, a -> new ArrayList<>()).add(new Object[]{id, name, locked, id == 0});
            return this;
        }

        private Object[] at(int account, int index) {
            if (broken) throw new IllegalStateException("Telegram changed");
            return folders.get(account).get(index);
        }

        @Override public boolean tabsEmpty(Object list) { return !built.contains(list); }
        @Override public Bundle arguments(Object list) { return args; }
        @Override public int account(Object list) { return listAccount; }

        @Override public boolean select(Object list, int index) {
            if (!pages) return false;
            picks.add(listAccount + "/" + index);
            return true;
        }

        @Override public int selectedAccount() { return selected; }
        @Override public long userId(int account) { return users.getOrDefault(account, 0L); }
        @Override public int folderCount(int account) { return folders.getOrDefault(account, new ArrayList<>()).size(); }
        @Override public int folderId(int account, int index) { return (Integer) at(account, index)[0]; }
        @Override public String folderName(int account, int index) { return (String) at(account, index)[1]; }
        @Override public boolean folderLocked(int account, int index) { return (Boolean) at(account, index)[2]; }
        @Override public boolean folderDefault(int account, int index) { return (Boolean) at(account, index)[3]; }
    }

    /** The arguments MainTabsActivity gives its chat list. */
    private static Bundle main() {
        Bundle args = new Bundle();
        args.putBoolean("hasMainTabs", true);
        return args;
    }

    private static Context context() {
        return Utils.getContext();
    }

    @Before public void setUp() {
        telegram = new Fake();
        telegram.users.put(0, 1000L);
        telegram.users.put(1, 2000L);
        telegram.folder(0, 0, "All Chats", false).folder(0, 3, "Work", false).folder(0, 7, "Friends", false)
                .folder(0, 9, "Old", true);
        telegram.folder(1, 0, "All Chats", false).folder(1, 5, "Family", false);
        LaunchFolder.telegram = telegram;
        LaunchFolder.resetForTests();
        LaunchFolder.store.apply(context()).edit().clear().commit();
        Settings.LAUNCH_FOLDER.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        LaunchFolder.telegram = real;
        LaunchFolder.resetForTests();
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.LAUNCH_FOLDER);
        Settings.LAUNCH_FOLDER.resetToDefault();
        LaunchFolder.store.apply(context()).edit().clear().commit();
        HookStatus.clear();
    }

    /** Telegram's first tab build for [list], which then has its tabs. */
    private void build(Object list) {
        LaunchFolder.firstTabs(list);
        telegram.built.add(list);
    }

    private String report() {
        return String.join("\n", HookStatus.report());
    }

    @Test public void offByDefaultTheListOpensOnAllChats() {
        LaunchFolder.pick(1000, FRIENDS);
        assertFalse(Settings.LAUNCH_FOLDER.get());
        build(new Object());
        assertEquals(new ArrayList<String>(), telegram.picks);
        assertTrue(report(), report().contains(FamilyNames.HIDE_FOLDER_COUNTERS + ": invoked 1"));
        assertFalse(report(), report().contains(LaunchFolder.OPENED));
    }

    @Test public void onTheListOpensOnThePickedFolderOnce() {
        Settings.LAUNCH_FOLDER.save(true);
        LaunchFolder.pick(1000, FRIENDS);
        Object list = new Object();
        build(list);
        // Its index in Telegram's list, All Chats included, which is what the tabs number by.
        assertEquals(Arrays.asList("0/2"), telegram.picks);
        // Asked again for the same list with its tabs empty, as when its view is built again, it isn't picked a second time.
        telegram.built.remove(list);
        build(list);
        assertEquals(Arrays.asList("0/2"), telegram.picks);
        // A new chat list, after a restart say, opens on it again.
        build(new Object());
        assertEquals(Arrays.asList("0/2", "0/2"), telegram.picks);
        assertTrue(report(), report().contains(LaunchFolder.OPENED + " 2"));
    }

    @Test public void aListAlreadyBuiltWhenTheSwitchWentOnStaysWhereItIs() {
        Object list = new Object();
        build(list);
        Settings.LAUNCH_FOLDER.save(true);
        LaunchFolder.pick(1000, FRIENDS);
        telegram.built.remove(list);
        build(list);
        assertEquals(new ArrayList<String>(), telegram.picks);
    }

    @Test public void onlyTheMainChatListTakesIt() {
        Settings.LAUNCH_FOLDER.save(true);
        LaunchFolder.pick(1000, WORK);
        Bundle share = main();
        share.putBoolean("onlySelect", true);
        Bundle type = main();
        type.putInt("dialogsType", 3);
        Bundle archive = main();
        archive.putInt("folderId", 1);
        Bundle tabless = new Bundle();
        for (Bundle args : Arrays.asList(share, type, archive, tabless, null)) {
            telegram.args = args;
            build(new Object());
        }
        assertEquals(new ArrayList<String>(), telegram.picks);
        telegram.args = main();
        build(new Object());
        assertEquals(Arrays.asList("0/1"), telegram.picks);
    }

    @Test public void eachAccountKeepsItsOwnPick() {
        Settings.LAUNCH_FOLDER.save(true);
        LaunchFolder.pick(1000, FRIENDS);
        telegram.listAccount = 1;
        build(new Object());
        assertEquals("the second account has no pick", new ArrayList<String>(), telegram.picks);
        LaunchFolder.pick(2000, FAMILY);
        build(new Object());
        telegram.listAccount = 0;
        build(new Object());
        assertEquals(Arrays.asList("1/1", "0/2"), telegram.picks);
        // Someone else signed in to the first slot doesn't get the last one's pick.
        telegram.users.put(0, 3000L);
        build(new Object());
        assertEquals(2, telegram.picks.size());
    }

    @Test public void aLockedOrMissingFolderOpensAllChats() {
        Settings.LAUNCH_FOLDER.save(true);
        LaunchFolder.pick(1000, OLD);
        build(new Object());
        LaunchFolder.pick(1000, new LaunchFolder.Folder(42, "Gone"));
        build(new Object());
        assertEquals(new ArrayList<String>(), telegram.picks);
        // A list with no pages takes nothing and isn't counted.
        LaunchFolder.pick(1000, WORK);
        telegram.pages = false;
        build(new Object());
        assertFalse(report(), report().contains(LaunchFolder.OPENED));
    }

    /** Telegram gives a deleted folder's id to the next one made, so a pick needs its name too. */
    @Test public void aNewFolderThatTookThePickedOnesIdOpensAllChats() {
        Settings.LAUNCH_FOLDER.save(true);
        LaunchFolder.pick(1000, FRIENDS);
        telegram.folders.get(0).set(2, new Object[]{7, "Gym", false, false});
        build(new Object());
        assertEquals(new ArrayList<String>(), telegram.picks);
        // The same folder under its picked name opens again.
        telegram.folders.get(0).set(2, new Object[]{7, "Friends", false, false});
        build(new Object());
        assertEquals(Arrays.asList("0/2"), telegram.picks);
    }

    @Test public void pauseMissingContextAndBrokenReadsOpenAllChats() {
        Settings.LAUNCH_FOLDER.save(true);
        LaunchFolder.pick(1000, FRIENDS);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            build(new Object());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> build(new Object()));
        SettingReadsForTests.breakReads(Settings.LAUNCH_FOLDER);
        build(new Object());
        SettingReadsForTests.mend(Settings.LAUNCH_FOLDER);
        telegram.broken = true;
        build(new Object());
        assertEquals(new ArrayList<String>(), telegram.picks);
        assertTrue(report(), report().contains("folder at launch"));
        assertFalse(report(), report().contains(LaunchFolder.OPENED));
    }

    @Test public void theFolderListLeavesOutAllChatsAndLockedOnes() {
        List<LaunchFolder.Folder> folders = LaunchFolder.folders(0);
        assertEquals(2, folders.size());
        assertEquals(3, folders.get(0).id);
        assertEquals("Work", folders.get(0).name);
        assertEquals(7, folders.get(1).id);
        telegram.folder(1, 6, null, false);
        assertEquals("", LaunchFolder.folders(1).get(1).name);
        assertEquals(0, LaunchFolder.folders(2).size());
        // The settings page reads the account shown now, its folders and its pick in one go.
        telegram.selected = 1;
        LaunchFolder.pick(2000, FAMILY);
        LaunchFolder.Shown shown = LaunchFolder.shown();
        assertEquals(2000L, shown.user);
        assertEquals(2, shown.folders.size());
        assertEquals("Family is first after All Chats", 1, shown.choice());
        assertEquals(2, shown.choiceOf(new LaunchFolder.Folder(6, "")));
        assertEquals("a renamed pick isn't there", 0, shown.choiceOf(new LaunchFolder.Folder(5, "Kids")));
        assertEquals(0, shown.choiceOf(null));
    }

    @Test public void aFolderListThatCantBeReadIsNoneAndSaysWhy() {
        telegram.broken = true;
        assertNull(LaunchFolder.shown());
        assertTrue(report(), report().contains("folder list"));
    }

    @Test public void picksAreSavedByUserAndAllChatsClearsOne() {
        assertNull(LaunchFolder.picked(1000));
        assertTrue(LaunchFolder.pick(1000, FRIENDS));
        assertTrue(LaunchFolder.pick(2000, FAMILY));
        assertTrue(FRIENDS.is(LaunchFolder.picked(1000)));
        assertTrue(FAMILY.is(LaunchFolder.picked(2000)));
        assertTrue(LaunchFolder.pick(1000, null));
        assertNull(LaunchFolder.picked(1000));
        assertFalse(LaunchFolder.store.apply(context()).contains("1000"));
        assertFalse(LaunchFolder.store.apply(context()).contains("1000.name"));
        // An id saved without its name, or a value of the wrong kind, is no pick.
        LaunchFolder.store.apply(context()).edit().putInt("3000", 7).putInt("4000.name", 1).putInt("4000", 7).commit();
        assertNull(LaunchFolder.picked(3000));
        assertNull(LaunchFolder.picked(4000));
        assertTrue(report(), report().contains("folder pick"));
        // No one signed in, or no context yet: nothing is saved or read.
        assertFalse(LaunchFolder.pick(0, FRIENDS));
        assertNull(LaunchFolder.picked(0));
        SettingsContextRule.withoutContext(() -> {
            assertFalse(LaunchFolder.pick(2000, WORK));
            assertNull(LaunchFolder.picked(2000));
        });
        assertTrue(FAMILY.is(LaunchFolder.picked(2000)));
    }
}
