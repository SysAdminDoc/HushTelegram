/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
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
public class StickerSizeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Half of a 1080 pixel wide chat, Telegram's largest side for a sticker on that phone. */
    private static final float TELEGRAM = 540f;

    @Before public void reset() { restore(); }
    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.CHANGE_STICKER_SIZE);
        Settings.CHANGE_STICKER_SIZE.resetToDefault();
        Settings.STICKER_SIZE.resetToDefault();
        HookStatus.clear();
    }

    @Test public void offByDefaultEveryStickerKeepsTelegramsSize() {
        assertFalse(Settings.CHANGE_STICKER_SIZE.get());
        assertEquals(StickerSize.DEFAULT, (int) Settings.STICKER_SIZE.get());
        assertEquals(TELEGRAM, StickerSize.size(new Object(), TELEGRAM), 0f);
        assertEquals(TELEGRAM, StickerSize.size(null, TELEGRAM), 0f);
        assertTrue(String.join("\n", HookStatus.report()).contains(FamilyNames.CHANGE_STICKER_SIZE));
    }

    @Test public void onAStickerTakesThePickedShareOfTelegramsSize() {
        Settings.CHANGE_STICKER_SIZE.save(true);
        assertTrue(StickerSize.on());
        assertEquals("three quarters until another size is picked", 405f, StickerSize.size(new Object(), TELEGRAM), 0.001f);
        int[] picked = {50, 75, 125, 150};
        float[] sizes = {270f, 405f, 675f, 810f};
        for (int i = 0; i < picked.length; i++) {
            Settings.STICKER_SIZE.save(picked[i]);
            assertEquals(picked[i] + "%", sizes[i], StickerSize.size(new Object(), TELEGRAM), 0.001f);
        }
        assertEquals("a bubble without a message", TELEGRAM, StickerSize.size(null, TELEGRAM), 0f);
        // The unpatched stub answers that nothing is an animated emoji or a dice; the patched one
        // is checked against both builds in StickerSizeFixtureTest.
        assertFalse(StickerSize.emoji(new Object()));
    }

    @Test public void aSavedSizeThatIsntOneOfTheChoicesReadsAsTheDefault() {
        for (int choice : StickerSize.CHOICES) assertEquals(choice, StickerSize.percent(choice));
        for (int other : new int[]{0, 100, -75, 51, 149, Integer.MAX_VALUE}) {
            assertEquals(String.valueOf(other), StickerSize.DEFAULT, StickerSize.percent(other));
        }
        Settings.CHANGE_STICKER_SIZE.save(true);
        Settings.STICKER_SIZE.save(100);
        assertEquals(405f, StickerSize.size(new Object(), TELEGRAM), 0.001f);
        Settings.STICKER_SIZE.save(1000);
        assertEquals("kept within its range when saved", 150, (int) Settings.STICKER_SIZE.get());
    }

    @Test public void pausingAnEarlyStartOrAnUnreadableSwitchKeepsTelegramsSize() {
        Settings.CHANGE_STICKER_SIZE.save(true);
        Settings.STICKER_SIZE.save(50);
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertFalse(reason.name(), StickerSize.on());
            assertEquals(reason.name(), TELEGRAM, StickerSize.size(new Object(), TELEGRAM), 0f);
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse(StickerSize.on()));
        SettingReadsForTests.breakReads(Settings.CHANGE_STICKER_SIZE);
        assertFalse(StickerSize.on());
        assertEquals(TELEGRAM, StickerSize.size(new Object(), TELEGRAM), 0f);
        assertFalse(HookStatus.missing(FamilyNames.CHANGE_STICKER_SIZE).isEmpty());
    }
}
