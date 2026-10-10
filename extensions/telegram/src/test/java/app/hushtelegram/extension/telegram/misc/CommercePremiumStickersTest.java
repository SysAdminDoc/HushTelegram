/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;

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
    }
}
