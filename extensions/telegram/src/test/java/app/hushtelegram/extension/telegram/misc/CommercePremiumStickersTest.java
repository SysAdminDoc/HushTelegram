/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/** The patch-written reads are shadows; the answers Commerce gives from them run as shipped. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, shadows = CommercePremiumStickersTest.Reads.class,
        instrumentedPackages = "app.hushtelegram.extension.telegram.misc")
public class CommercePremiumStickersTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final Object CONTROLLER = new Object();
    private static final Object MESSAGE = new Object();
    private static final Object VIEW = new Object();

    @Before public void setUp() {
        Settings.HIDE_COMMERCE.resetToDefault();
        Reads.blocked = false;
        Reads.premium = false;
        Reads.premiumSticker = true;
        Reads.broken = false;
        HookStatus.clear();
    }

    @After public void tearDown() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.HIDE_COMMERCE);
        Settings.HIDE_COMMERCE.resetToDefault();
        HookStatus.clear();
    }

    @Test public void anAccountWithoutPremiumLosesPremiumStickersAndTheirEffects() {
        assertTrue(Settings.HIDE_COMMERCE.get());
        assertTrue(Commerce.premiumStickersBlocked(CONTROLLER));
        assertTrue(Commerce.skipPremiumEffect(MESSAGE));
        String report = HookStatus.report().get(0);
        assertTrue(report, report.contains("Premium stickers left out 1"));
        assertTrue(report, report.contains("Premium sticker effect skipped 1"));
    }

    @Test public void anAccountWithPremiumKeepsEverySticker() {
        Reads.premium = true;
        assertFalse(Commerce.premiumStickersBlocked(CONTROLLER));
        assertFalse(Commerce.skipPremiumEffect(MESSAGE));
        assertNoSuppression();
    }

    @Test public void anOrdinaryStickerKeepsItsEffect() {
        Reads.premiumSticker = false;
        assertFalse(Commerce.skipPremiumEffect(MESSAGE));
        assertNoSuppression();
    }

    @Test public void telegramsOwnBlockStandsWhateverTheSwitchSays() {
        Reads.blocked = true;
        Reads.premium = true;
        assertTrue(Commerce.premiumStickersBlocked(CONTROLLER));
        Settings.HIDE_COMMERCE.save(false);
        assertTrue(Commerce.premiumStickersBlocked(CONTROLLER));
        assertNoSuppression();
    }

    @Test public void theSwitchOffOrAPauseGivesTelegramsAnswer() {
        Settings.HIDE_COMMERCE.save(false);
        assertFalse(Commerce.premiumStickersBlocked(CONTROLLER));
        assertFalse(Commerce.skipPremiumEffect(MESSAGE));
        Settings.HIDE_COMMERCE.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), Commerce.premiumStickersBlocked(CONTROLLER));
            assertFalse(reason.name(), Commerce.skipPremiumEffect(MESSAGE));
            PauseForTests.resume();
        }
        assertNoSuppression();
    }

    @Test public void nothingToAskAboutOrAFailedReadKeepsTheStickers() {
        assertFalse(Commerce.premiumStickersBlocked(null));
        Reads.blocked = true;
        assertFalse("no controller is never handed on", Commerce.premiumStickersBlocked(null));
        Reads.blocked = false;
        assertFalse(Commerce.skipPremiumEffect(null));
        Reads.broken = true;
        assertFalse(Commerce.premiumStickersBlocked(CONTROLLER));
        assertFalse(Commerce.skipPremiumEffect(MESSAGE));
        assertNoSuppression();
        assertEquals(new HashSet<>(Arrays.asList(
                        "a working 'Premium account read' hook (it threw java.lang.IllegalStateException)",
                        "a working 'Premium sticker read' hook (it threw java.lang.IllegalStateException)")),
                new HashSet<>(HookStatus.missing(FamilyNames.HIDE_COMMERCE)));
        SettingReadsForTests.breakReads(Settings.HIDE_COMMERCE);
        Reads.broken = false;
        assertFalse(Commerce.premiumStickersBlocked(CONTROLLER));
        assertFalse(Commerce.skipPremiumEffect(MESSAGE));
        assertNoSuppression();
    }

    @Test public void anAccountWithoutPremiumSeesOnlyEmojiItCanSend() {
        List<Object> packs = new ArrayList<>(Arrays.asList("group", "free", "locked part", "featured", "locked featured"));
        Commerce.dropLockedEmojiPacks(VIEW, packs);
        assertEquals(Arrays.asList("group", "free", "featured"), packs);
        String report = HookStatus.report().get(0);
        assertTrue(report, report.contains("Premium emoji packs left out 1"));
    }

    @Test public void premiumOrAViewThatShowsEveryEmojiKeepsEveryPack() {
        Reads.premium = true;
        List<Object> packs = new ArrayList<>(Arrays.asList("free", "locked part"));
        Commerce.dropLockedEmojiPacks(VIEW, packs);
        assertEquals(Arrays.asList("free", "locked part"), packs);
        assertNoSuppression();
    }

    @Test public void onlyFreePacksLeaveTheListAlone() {
        List<Object> packs = new ArrayList<>(Arrays.asList("free", "featured"));
        Commerce.dropLockedEmojiPacks(VIEW, packs);
        assertEquals(Arrays.asList("free", "featured"), packs);
        assertNoSuppression();
    }

    @Test public void theSwitchOffOrAPauseKeepsEveryEmojiPack() {
        List<Object> packs = new ArrayList<>(Arrays.asList("free", "locked part"));
        Settings.HIDE_COMMERCE.save(false);
        Commerce.dropLockedEmojiPacks(VIEW, packs);
        assertEquals(2, packs.size());
        Settings.HIDE_COMMERCE.save(true);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            Commerce.dropLockedEmojiPacks(VIEW, packs);
            assertEquals(reason.name(), 2, packs.size());
            PauseForTests.resume();
        }
        assertNoSuppression();
    }

    @Test public void nothingToSortOrAFailedPackReadKeepsEveryEmojiPack() {
        List<Object> lone = new ArrayList<>(Arrays.asList("locked part"));
        Commerce.dropLockedEmojiPacks(null, lone);
        assertEquals(1, lone.size());
        Commerce.dropLockedEmojiPacks(VIEW, null);
        Commerce.dropLockedEmojiPacks(VIEW, new ArrayList<>());
        // The locked pack was already found when the next read fails, and still stays.
        List<Object> packs = new ArrayList<>(Arrays.asList("free", "locked part", "broken"));
        Commerce.dropLockedEmojiPacks(VIEW, packs);
        assertEquals(Arrays.asList("free", "locked part", "broken"), packs);
        assertNoSuppression();
        assertEquals(new HashSet<>(Arrays.asList(
                        "a working 'Premium emoji pack read' hook (it threw java.lang.IllegalStateException)")),
                new HashSet<>(HookStatus.missing(FamilyNames.HIDE_COMMERCE)));
    }

    private void assertNoSuppression() {
        assertFalse(HookStatus.report().stream().anyMatch(row -> row.contains("Counted:")));
    }

    @Implements(value = Commerce.class, isInAndroidSdk = false)
    public static class Reads {
        static boolean blocked;
        static boolean premium;
        static boolean premiumSticker;
        static boolean broken;

        @Implementation protected static boolean premiumBlocked(Object controller) {
            // The patch's stub calls into the controller, which a null can't answer.
            if (controller == null) throw new NullPointerException("controller");
            return blocked;
        }

        @Implementation protected static boolean premiumAccount(Object controller) {
            if (broken) throw new IllegalStateException("account");
            return premium;
        }

        @Implementation protected static boolean premiumSticker(Object message) {
            if (broken) throw new IllegalStateException("sticker");
            return premiumSticker;
        }

        @Implementation protected static boolean messageAccountPremium(Object message) {
            return premium;
        }

        @Implementation protected static boolean emojiViewPremium(Object view) {
            return premium;
        }

        @Implementation protected static boolean emojiPackFree(Object pack) {
            if ("broken".equals(pack)) throw new IllegalStateException("pack");
            return !String.valueOf(pack).startsWith("locked");
        }
    }
}
