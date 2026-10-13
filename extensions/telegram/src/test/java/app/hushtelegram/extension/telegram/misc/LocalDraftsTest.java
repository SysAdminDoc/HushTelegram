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
import java.util.function.Predicate;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LocalDraftsTest {
    /** A draft with something in it, and Telegram's clear, as the patched reader tells them apart. */
    private static final Object DRAFT = new Object();
    private static final Object CLEAR = new Object();

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Predicate<Object> reader;

    @Before public void setUp() {
        reader = LocalDrafts.content;
        LocalDrafts.content = request -> request == DRAFT;
        Settings.LOCAL_DRAFTS.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        LocalDrafts.content = reader;
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.LOCAL_DRAFTS);
        Settings.LOCAL_DRAFTS.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultSoDraftsStillSync() {
        assertFalse(Settings.LOCAL_DRAFTS.get());
        assertFalse(LocalDrafts.keepOnPhone(DRAFT));
        assertFalse(LocalDrafts.keepOnPhone(CLEAR));
        assertEquals(Collections.singletonList("Disable draft link previews: invoked 2, 0 found, 0 missing"),
                HookStatus.report());
    }

    @Test public void switchedOnADraftStaysAndAClearStillGoes() {
        Settings.LOCAL_DRAFTS.save(true);
        assertTrue(LocalDrafts.keepOnPhone(DRAFT));
        assertFalse(LocalDrafts.keepOnPhone(CLEAR));
        assertEquals(Collections.singletonList("Disable draft link previews: invoked 2, 0 found, 0 missing. Counted: "
                        + "draft kept on the phone 1, empty draft clear sent 1"),
                HookStatus.report());
    }

    // The preview switch belongs to the same patch and says nothing about drafts.
    @Test public void thePreviewSwitchAloneLeavesDraftsSyncing() {
        Settings.DISABLE_DRAFT_PREVIEWS.save(true);
        try {
            assertFalse(LocalDrafts.keepOnPhone(DRAFT));
        } finally {
            Settings.DISABLE_DRAFT_PREVIEWS.resetToDefault();
        }
    }

    @Test public void everyPauseReasonLetsDraftsSyncAndResumeKeepsThemAgain() {
        Settings.LOCAL_DRAFTS.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), LocalDrafts.keepOnPhone(DRAFT));
            PauseForTests.resume();
        }
        assertTrue(LocalDrafts.keepOnPhone(DRAFT));
    }

    @Test public void unavailableSettingsLetDraftsSync() {
        Settings.LOCAL_DRAFTS.save(true);
        SettingsContextRule.withoutContext(() -> assertFalse(LocalDrafts.keepOnPhone(DRAFT)));
    }

    @Test public void anUnreadableSwitchLetsDraftsSyncAndSaysSo() {
        Settings.LOCAL_DRAFTS.save(true);
        SettingReadsForTests.breakReads(Settings.LOCAL_DRAFTS);
        assertFalse(LocalDrafts.keepOnPhone(DRAFT));
        assertEquals(Collections.singletonList("a working 'local drafts switch read' hook (it threw java.lang.NullPointerException)"),
                HookStatus.missing(FamilyNames.DISABLE_DRAFT_PREVIEWS));
    }

    // With the switch on, a draft the reader can't make out isn't sent either.
    @Test public void aDraftThatCantBeReadStaysOnThePhone() {
        Settings.LOCAL_DRAFTS.save(true);
        LocalDrafts.content = request -> { throw new IllegalStateException("no fields"); };
        assertTrue(LocalDrafts.keepOnPhone(DRAFT));
        assertEquals(Collections.singletonList("a working 'draft read' hook (it threw java.lang.IllegalStateException)"),
                HookStatus.missing(FamilyNames.DISABLE_DRAFT_PREVIEWS));
    }
}
