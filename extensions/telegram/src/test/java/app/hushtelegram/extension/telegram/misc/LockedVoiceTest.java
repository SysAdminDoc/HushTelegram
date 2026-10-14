/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.KeyguardManager;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
public class LockedVoiceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A voice message, a round video and a song, as the stand-in check tells them apart. */
    private static final Object VOICE = new Object();
    private static final Object ROUND = new Object();
    private static final Object SONG = new Object();

    private final BooleanSupplier wasLocked = LockedVoice.locked;
    private final Predicate<Object> wasVoice = LockedVoice.voice;
    private final Runnable wasFeedback = LockedVoice.feedback;
    private final AtomicInteger told = new AtomicInteger();
    private boolean phoneLocked;

    @Before public void setUp() {
        Settings.LOCKED_VOICE_GUARD.resetToDefault();
        HookStatus.clear();
        phoneLocked = true;
        LockedVoice.locked = () -> phoneLocked;
        LockedVoice.voice = message -> message == VOICE || message == ROUND;
        LockedVoice.feedback = told::incrementAndGet;
        LockedVoice.forgetToast();
    }

    @After public void tearDown() {
        LockedVoice.locked = wasLocked;
        LockedVoice.voice = wasVoice;
        LockedVoice.feedback = wasFeedback;
        LockedVoice.forgetToast();
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.LOCKED_VOICE_GUARD);
        Settings.LOCKED_VOICE_GUARD.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultSoALockedPhonePlaysAsTelegramDoes() {
        assertFalse(Settings.LOCKED_VOICE_GUARD.get());
        assertFalse(LockedVoice.refuse(VOICE, null, false));
        assertFalse(LockedVoice.refuse(ROUND, VOICE, true));
        assertEquals(0, told.get());
        assertEquals(Collections.singletonList(FamilyNames.VOICE_ONE_AT_A_TIME + ": invoked 2, 0 found, 0 missing"),
                HookStatus.report());
    }

    @Test public void lockedNoVoiceOrRoundVideoStartsAndEachRefusalIsCountedAndSaid() {
        Settings.LOCKED_VOICE_GUARD.save(true);
        assertTrue(LockedVoice.refuse(VOICE, null, false));
        assertTrue(LockedVoice.refuse(ROUND, SONG, false));
        assertEquals(2, told.get());
        assertEquals(Collections.singletonList(FamilyNames.VOICE_ONE_AT_A_TIME + ": invoked 2, 0 found, 0 missing. Counted: "
                + "voice kept quiet while locked 2"), HookStatus.report());
    }

    @Test public void unlockingLetsTheNextPlayThrough() {
        Settings.LOCKED_VOICE_GUARD.save(true);
        assertTrue(LockedVoice.refuse(VOICE, null, false));
        phoneLocked = false;
        assertFalse(LockedVoice.refuse(VOICE, null, false));
        assertFalse(LockedVoice.refuse(ROUND, VOICE, false));
        // Nothing is remembered from the refusal: locking again refuses again, and unlocking frees it again.
        phoneLocked = true;
        assertTrue(LockedVoice.refuse(VOICE, null, false));
        phoneLocked = false;
        assertFalse(LockedVoice.refuse(VOICE, null, false));
    }

    @Test public void musicAndOrdinaryVideosPlayWhileLocked() {
        Settings.LOCKED_VOICE_GUARD.save(true);
        assertFalse(LockedVoice.refuse(SONG, null, false));
        assertFalse(LockedVoice.refuse(SONG, VOICE, true));
        assertEquals(0, told.get());
    }

    @Test public void whatsAlreadyPlayingCarriesOnButAPausedOneWaits() {
        Settings.LOCKED_VOICE_GUARD.save(true);
        // Telegram asks again for the message it's playing, such as once its file has downloaded.
        assertFalse(LockedVoice.refuse(VOICE, VOICE, false));
        assertFalse(LockedVoice.refuse(ROUND, ROUND, false));
        // Play on a paused one is a resume, and a different one is a new start.
        assertTrue(LockedVoice.refuse(VOICE, VOICE, true));
        assertTrue(LockedVoice.refuse(VOICE, ROUND, false));
    }

    @Test public void noMessageIsTelegramsOwnAnswerAndIsntCounted() {
        Settings.LOCKED_VOICE_GUARD.save(true);
        assertFalse(LockedVoice.refuse(null, null, false));
        assertFalse(LockedVoice.refuse(null, VOICE, true));
        assertEquals(Collections.emptyList(), HookStatus.report());
    }

    @Test public void everyPauseReasonPlaysAsTelegramDoes() {
        Settings.LOCKED_VOICE_GUARD.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), LockedVoice.refuse(VOICE, null, false));
            PauseForTests.resume();
        }
        assertEquals(0, told.get());
        assertTrue(LockedVoice.refuse(VOICE, null, false));
    }

    @Test public void unavailableOrUnreadableSettingsPlayAsTelegramDoesAndSaySo() {
        Settings.LOCKED_VOICE_GUARD.save(true);
        SettingsContextRule.withoutContext(() -> assertFalse(LockedVoice.refuse(VOICE, null, false)));
        SettingReadsForTests.breakReads(Settings.LOCKED_VOICE_GUARD);
        assertFalse(LockedVoice.refuse(VOICE, null, false));
        assertEquals(Collections.singletonList("a working 'locked phone switch read' hook (it threw java.lang.NullPointerException)"),
                HookStatus.missing(FamilyNames.VOICE_ONE_AT_A_TIME));
    }

    @Test public void aCheckThatThrowsPlaysAsTelegramDoesAndSaysSo() {
        Settings.LOCKED_VOICE_GUARD.save(true);
        LockedVoice.locked = () -> { throw new IllegalStateException("no keyguard"); };
        assertFalse(LockedVoice.refuse(VOICE, null, false));
        assertEquals(0, told.get());
        assertFalse(HookStatus.missing(FamilyNames.VOICE_ONE_AT_A_TIME).isEmpty());
    }

    @Test public void aToastThatThrowsStillRefuses() {
        Settings.LOCKED_VOICE_GUARD.save(true);
        LockedVoice.feedback = () -> { throw new IllegalStateException("no toast"); };
        assertTrue(LockedVoice.refuse(VOICE, null, false));
        assertFalse(HookStatus.missing(FamilyNames.VOICE_ONE_AT_A_TIME).isEmpty());
    }

    @Test public void theLockScreenIsAndroidsOwn() {
        KeyguardManager keyguard = (KeyguardManager) RuntimeEnvironment.getApplication().getSystemService(Context.KEYGUARD_SERVICE);
        shadowOf(keyguard).setKeyguardLocked(false);
        assertFalse(LockedVoice.keyguardLocked());
        shadowOf(keyguard).setKeyguardLocked(true);
        assertTrue(LockedVoice.keyguardLocked());
    }

    @Test public void aBurstOfRefusalsShowsOneToastThatSaysNothingOfTheMessage() {
        Settings.LOCKED_VOICE_GUARD.save(true);
        LockedVoice.feedback = wasFeedback;
        ShadowToast.reset();
        assertTrue(LockedVoice.refuse(VOICE, null, false));
        assertTrue(LockedVoice.refuse(ROUND, null, false));
        ShadowLooper.idleMainLooper();
        assertEquals(1, ShadowToast.shownToastCount());
        assertEquals("Unlock your phone to play voice and video messages.", ShadowToast.getTextOfLatestToast());

        ShadowSystemClock.advanceBy(Duration.ofMillis(LockedVoice.QUIET_MILLIS + 1));
        assertTrue(LockedVoice.refuse(VOICE, null, false));
        ShadowLooper.idleMainLooper();
        assertEquals(2, ShadowToast.shownToastCount());
    }
}
