/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.settings;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.preference.Preference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.EnumSet;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.misc.UnifiedPushForTests;

/**
 * The Push app row under the UnifiedPush switch. Seen on the S22: ntfy ended the sign-up while
 * Telegram was in the background, and coming back the row still said wake-ups came through ntfy
 * until the page was opened again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@SuppressWarnings("deprecation")
public class PushAppRowTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> controller;

    @Before public void signedUp() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.UNIFIED_PUSH);
        Settings.UNIFIED_PUSH.save(true);
        UnifiedPushForTests.signedUpWithNtfy();
    }

    @After public void restore() {
        if (controller != null) controller.close();
        UnifiedPushForTests.reset();
        PatchFamily.inBuildForTests = null;
        Settings.UNIFIED_PUSH.resetToDefault();
        HookStatus.clear();
    }

    @Test public void comingBackAfterTheAppEndedTheSignUpSaysSo() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        HushTelegramPreferenceFragment page = new HushTelegramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Preference row = page.findPreference(UnifiedPushPicker.KEY);
        assertEquals("Telegram's wake-ups come through io.heckel.ntfy.", String.valueOf(row.getSummary()));

        controller.pause().stop();
        UnifiedPushForTests.endedByNtfy();
        controller.start().resume();
        assertEquals("io.heckel.ntfy ended the sign-up. Turn the switch off and on to sign up again.",
                String.valueOf(page.findPreference(UnifiedPushPicker.KEY).getSummary()));
    }
}
