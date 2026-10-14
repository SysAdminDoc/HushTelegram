/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.settings;

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
import org.robolectric.shadows.ShadowToast;

import java.util.Locale;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.settings.FailingStore;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.telegram.settings.SettingsLock.State;
import app.hushtelegram.extension.telegram.settings.SettingsLock.Stored;

/** The settings lock's states, on a clock the test moves by hand. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SettingsLockTest {
    private static final long START = 1_800_000_000_000L;
    private static final long HOUR = 60 * 60 * 1000L;
    private static final String LOCKED_NOTE = "Settings are locked. Tap Start unlock, then wait 24 hours.";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final long[] now = {START};

    @Before public void handClock() {
        forget();
        SettingsLock.clock = () -> now[0];
        ShadowToast.reset();
    }

    @After public void realClock() {
        forget();
        SettingsLock.clock = System::currentTimeMillis;
        PauseForTests.resume();
    }

    private static void forget() {
        Stored.LOCKED.resetToDefault();
        Stored.UNLOCK_STARTED.resetToDefault();
    }

    @Test public void offUntilLockedAndOffChangesNothing() {
        assertEquals(State.OFF, SettingsLock.state());
        assertFalse(SettingsLock.locked());
        assertEquals(0L, SettingsLock.remaining());
        assertFalse(SettingsLock.refuses());
        assertNull("an unlocked change said something", ShadowToast.getTextOfLatestToast());
        assertEquals("Lock HushTelegram settings", SettingsLock.title(State.OFF));
    }

    @Test public void lockedStaysLockedHoweverLongPassesUntilAnUnlockStarts() {
        assertTrue(SettingsLock.lock());
        assertEquals(State.LOCKED, SettingsLock.state());
        assertTrue(Stored.LOCKED.savedValue());
        assertEquals(0L, (long) Stored.UNLOCK_STARTED.savedValue());
        now[0] += 400 * SettingsLock.WAIT_MILLIS;
        assertEquals(State.LOCKED, SettingsLock.state());
        assertEquals(0L, SettingsLock.remaining());
        assertTrue(SettingsLock.refuses());
        assertEquals(LOCKED_NOTE, ShadowToast.getTextOfLatestToast());
        // Locking again keeps it as it is.
        assertTrue(SettingsLock.lock());
        assertEquals(State.LOCKED, SettingsLock.state());
    }

    @Test public void anUnlockWaitsTwentyFourHoursByTheWallClock() {
        SettingsLock.lock();
        assertTrue(SettingsLock.startUnlock());
        assertEquals(START, (long) Stored.UNLOCK_STARTED.savedValue());
        assertEquals(State.UNLOCKING, SettingsLock.state());
        assertEquals(SettingsLock.WAIT_MILLIS, SettingsLock.remaining());
        assertEquals("Settings unlock in 24 h 0 min. Tap to cancel and keep them locked.", SettingsLock.summary(State.UNLOCKING));

        now[0] = START + 90_000L;
        assertEquals("Settings unlock in 23 h 59 min. Tap to cancel and keep them locked.", SettingsLock.summary(State.UNLOCKING));

        // A millisecond short, still locked, and the last of it reads as a minute.
        now[0] = START + SettingsLock.WAIT_MILLIS - 1;
        assertEquals(State.UNLOCKING, SettingsLock.state());
        assertEquals(1L, SettingsLock.remaining());
        assertTrue(SettingsLock.refuses());
        assertEquals("Settings are locked. They unlock in 0 h 1 min.", ShadowToast.getTextOfLatestToast());

        // On the hour, the lock is off and nothing of it is kept.
        now[0] = START + SettingsLock.WAIT_MILLIS;
        assertEquals(State.OFF, SettingsLock.state());
        assertFalse(Stored.LOCKED.savedValue());
        assertEquals(0L, (long) Stored.UNLOCK_STARTED.savedValue());
        ShadowToast.reset();
        assertFalse(SettingsLock.refuses());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void startingAgainKeepsTheFirstStart() {
        SettingsLock.lock();
        SettingsLock.startUnlock();
        now[0] += 5 * HOUR;
        assertTrue(SettingsLock.startUnlock());
        assertEquals(START, (long) Stored.UNLOCK_STARTED.savedValue());
        assertEquals(SettingsLock.WAIT_MILLIS - 5 * HOUR, SettingsLock.remaining());
    }

    @Test public void cancelingKeepsTheLockAndForgetsTheWait() {
        SettingsLock.lock();
        SettingsLock.startUnlock();
        now[0] += 23 * HOUR;
        assertTrue(SettingsLock.cancelUnlock());
        assertEquals(State.LOCKED, SettingsLock.state());
        assertEquals(0L, (long) Stored.UNLOCK_STARTED.savedValue());
        now[0] += 10 * SettingsLock.WAIT_MILLIS;
        assertEquals(State.LOCKED, SettingsLock.state());
        // A second cancel finds it locked already.
        assertTrue(SettingsLock.cancelUnlock());
    }

    @Test public void aClockSetBackBeforeTheStartBeginsTheWaitAgain() {
        SettingsLock.lock();
        SettingsLock.startUnlock();
        now[0] = START + 20 * HOUR;
        assertEquals(4 * HOUR, SettingsLock.remaining());

        // Set back an hour before the start: the whole wait again, from now.
        now[0] = START - HOUR;
        assertEquals(State.UNLOCKING, SettingsLock.state());
        assertEquals(START - HOUR, (long) Stored.UNLOCK_STARTED.savedValue());
        assertEquals(SettingsLock.WAIT_MILLIS, SettingsLock.remaining());

        // Set forward again to where it was, the new start counts.
        now[0] = START + 20 * HOUR;
        assertEquals(State.UNLOCKING, SettingsLock.state());
        assertEquals(3 * HOUR, SettingsLock.remaining());
        now[0] = START - HOUR + SettingsLock.WAIT_MILLIS;
        assertEquals(State.OFF, SettingsLock.state());
    }

    @Test public void aClockAtNineteenSeventyStillStartsAWait() {
        now[0] = 0L;
        SettingsLock.lock();
        assertTrue(SettingsLock.startUnlock());
        assertEquals(1L, (long) Stored.UNLOCK_STARTED.savedValue());
        assertEquals(State.UNLOCKING, SettingsLock.state());
    }

    /** Locked, a switch goes through only to resume, or for Debug logging, which only changes a report. */
    @Test public void onlyResumeAndDebugLoggingGoThroughALock() {
        assertTrue(SettingsLock.exempts(app.hushtelegram.extension.shared.settings.BaseSettings.DEBUG.key, true));
        assertTrue(SettingsLock.exempts(app.hushtelegram.extension.shared.settings.BaseSettings.DEBUG.key, false));
        assertTrue(SettingsLock.exempts(app.hushtelegram.extension.shared.settings.BaseSettings.PAUSED.key, false));
        assertFalse(SettingsLock.exempts(app.hushtelegram.extension.shared.settings.BaseSettings.PAUSED.key, true));
        assertFalse(SettingsLock.exempts(Settings.HIDE_ADS.key, false));
        assertFalse(SettingsLock.exempts(Settings.SEARCH_ONLY_MY_CHATS.key, false));
        assertFalse(SettingsLock.exempts(null, false));
    }

    @Test public void unlockedThereIsNothingToStartOrCancel() {
        assertFalse(SettingsLock.startUnlock());
        assertFalse(SettingsLock.cancelUnlock());
        assertEquals(State.OFF, SettingsLock.state());
        assertFalse(Stored.LOCKED.savedValue());
    }

    @Test public void aStartLeftWithoutTheFlagIsClearedAndMeansNothing() {
        Stored.UNLOCK_STARTED.save(START);
        assertEquals(State.OFF, SettingsLock.state());
        assertEquals(0L, (long) Stored.UNLOCK_STARTED.savedValue());
    }

    @Test public void theRowStepsThroughLockStartAndCancelAndSaysWhich() {
        assertEquals("Settings locked. Unlocking takes 24 hours.", SettingsLock.advance());
        assertEquals(State.LOCKED, SettingsLock.state());
        assertEquals("Start unlock", SettingsLock.title(State.LOCKED));
        assertEquals("Your settings are locked. Tap to start the 24-hour wait. You can cancel it while it runs.",
                SettingsLock.summary(State.LOCKED));

        assertEquals("Unlock started. Settings open in 24 hours unless you cancel.", SettingsLock.advance());
        assertEquals(State.UNLOCKING, SettingsLock.state());
        assertEquals("Cancel unlock", SettingsLock.title(State.UNLOCKING));

        assertEquals("Unlock canceled. Settings stay locked.", SettingsLock.advance());
        assertEquals(State.LOCKED, SettingsLock.state());
    }

    /** A wait that ran out just before a tap on Cancel unlock locks again, which is what the tap was for. */
    @Test public void aCancelAfterTheWaitRanOutLocksAgain() {
        SettingsLock.lock();
        SettingsLock.startUnlock();
        now[0] += SettingsLock.WAIT_MILLIS;
        assertEquals("Settings locked. Unlocking takes 24 hours.", SettingsLock.advance());
        assertEquals(State.LOCKED, SettingsLock.state());
    }

    @Test public void aLockTheStoreCanNotKeepStaysOffAndSaysSo() {
        try (FailingStore ignored = FailingStore.install(FailingStore.Fault.COMMIT_THROWS, FailingStore.Fault.COMMIT_THROWS)) {
            assertEquals("Couldn't change the lock. Try again in a moment.", SettingsLock.advance());
        }
        assertEquals(State.OFF, SettingsLock.state());
        assertFalse(Stored.LOCKED.savedValue());
    }

    @Test public void pausingLeavesTheLockAlone() {
        SettingsLock.lock();
        SettingsLock.startUnlock();
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertTrue(reason.name(), Stored.LOCKED.isKeptWhenPaused());
            assertTrue(reason.name(), Stored.UNLOCK_STARTED.isKeptWhenPaused());
            assertEquals(reason.name(), State.UNLOCKING, SettingsLock.state());
            assertTrue(reason.name(), Stored.LOCKED.get());
            PauseForTests.resume();
        }
    }

    @Test public void theLockStaysOutOfSettingsFiles() throws Exception {
        SettingsLock.lock();
        SettingsLock.startUnlock();
        assertFalse(Stored.LOCKED.includeWithImportExport);
        assertFalse(Stored.UNLOCK_STARTED.includeWithImportExport);
        assertFalse(SettingsBackup.ALLOWLIST.contains(Stored.LOCKED));
        String file = SettingsBackup.create();
        assertFalse(file, file.contains(Stored.LOCKED.key));
        assertFalse(file, file.contains(Stored.UNLOCK_STARTED.key));
    }

    /** Nothing the lock says promises it can't be taken off: it always can, after the wait. */
    @Test public void noLinePromisesTheLockIsForever() {
        SettingsLock.lock();
        SettingsLock.startUnlock();
        StringBuilder all = new StringBuilder();
        for (State state : State.values()) {
            all.append(SettingsLock.title(state)).append('\n').append(SettingsLock.summary(state)).append('\n')
                    .append(SettingsLock.note(state)).append('\n');
        }
        // And what each tap on the row says, from off through a canceled unlock.
        forget();
        for (int tap = 0; tap < 3; tap++) all.append(SettingsLock.advance()).append('\n');
        String text = all.toString().toLowerCase(Locale.ROOT);
        for (String promise : new String[] {"undone", "undo", "forever", "permanent", "can't", "cannot", "never", "irreversible"}) {
            assertFalse(promise + " in:\n" + text, text.contains(promise));
        }
    }
}
