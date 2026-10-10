/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.telegram.settings.PatchFamily;
import app.hushtelegram.extension.telegram.settings.PatchFamilyForTests;
import app.hushtelegram.extension.telegram.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class UnifiedPushTest {
    private static final String OTHER = "org.example.push";
    private static final String ENDPOINT = "https://ntfy.sh/upAbCdEf12?up=1";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final List<String> registered = new ArrayList<>();
    private final List<Runnable> wakes = new ArrayList<>();
    private UnifiedPush.Telegram real;
    private Context context;

    @Before public void setUp() {
        context = Utils.getContext();
        real = UnifiedPush.telegram;
        UnifiedPush.telegram = new UnifiedPush.Telegram() {
            @Override public void register(String endpoint) { registered.add(endpoint); }
            @Override public void wake(Runnable done) { wakes.add(done); }
        };
        PatchFamilyForTests.inBuild(PatchFamily.UNIFIED_PUSH);
        UnifiedPush.prefs(context).edit().clear().commit();
        Settings.UNIFIED_PUSH.resetToDefault();
    }

    @After public void restore() {
        UnifiedPush.telegram = real;
        PatchFamilyForTests.reset();
        PauseForTests.resume();
        UnifiedPush.prefs(context).edit().clear().commit();
        Settings.UNIFIED_PUSH.resetToDefault();
        HookStatus.clear();
    }

    private void install(String... packages) {
        for (String name : packages) {
            ResolveInfo info = new ResolveInfo();
            info.activityInfo = new ActivityInfo();
            info.activityInfo.packageName = name;
            info.activityInfo.name = name + ".Distributor";
            shadowOf(context.getPackageManager()).addResolveInfoForIntent(new Intent(UnifiedPush.REGISTER), info);
        }
    }

    private List<Intent> sent() {
        return shadowOf((Application) RuntimeEnvironment.getApplication()).getBroadcastIntents();
    }

    private Intent last(String action) {
        Intent found = null;
        for (Intent intent : sent()) if (action.equals(intent.getAction())) found = intent;
        return found;
    }

    private Intent from(String action, String token) {
        return new Intent(action).putExtra("token", token);
    }

    private String token() { return UnifiedPush.prefs(context).getString(UnifiedPush.TOKEN, null); }

    /** Signed up with ntfy, an address in hand, the switch on. */
    private String signedUp() {
        install(UnifiedPush.NTFY);
        Settings.UNIFIED_PUSH.save(true);
        UnifiedPush.sync(context, true);
        UnifiedPush.receive(context, from(UnifiedPush.NEW_ENDPOINT, token()).putExtra("endpoint", ENDPOINT), () -> {});
        registered.clear();
        return token();
    }

    @Test public void telegramKeepsItsOwnTokenUntilAnAddressIsInAndTheSwitchIsOn() {
        assertEquals("fcm-token", UnifiedPush.token(2, "fcm-token"));
        assertEquals(2, UnifiedPush.type(2));
        UnifiedPush.prefs(context).edit().putString(UnifiedPush.ENDPOINT, ENDPOINT).commit();
        assertEquals("the switch is still off", "fcm-token", UnifiedPush.token(2, "fcm-token"));
        assertEquals(2, UnifiedPush.type(2));

        Settings.UNIFIED_PUSH.save(true);
        assertEquals(ENDPOINT, UnifiedPush.token(2, "fcm-token"));
        assertEquals(UnifiedPush.SIMPLE_PUSH, UnifiedPush.type(2));
        // A Firebase sign-up that failed asks with no token, and the address goes in all the same.
        assertEquals(ENDPOINT, UnifiedPush.token(2, null));
        assertEquals(UnifiedPush.SIMPLE_PUSH, UnifiedPush.type(2));
        assertEquals(ENDPOINT, UnifiedPush.token(UnifiedPush.SIMPLE_PUSH, ENDPOINT));
        assertEquals(UnifiedPush.SIMPLE_PUSH, UnifiedPush.type(UnifiedPush.SIMPLE_PUSH));

        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertEquals(reason.name(), "fcm-token", UnifiedPush.token(2, "fcm-token"));
            assertEquals(reason.name(), 2, UnifiedPush.type(2));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertEquals("fcm-token", UnifiedPush.token(2, "fcm-token"));
            assertEquals(2, UnifiedPush.type(2));
        });
    }

    @Test public void signingUpPicksNtfyAndSendsWhatTheSpecAsks() {
        install(OTHER, UnifiedPush.NTFY);
        assertEquals(java.util.Arrays.asList(UnifiedPush.NTFY, OTHER), UnifiedPush.distributors(context));
        UnifiedPush.sync(context, true);
        Intent register = last(UnifiedPush.REGISTER);
        assertNotNull(register);
        assertEquals(UnifiedPush.NTFY, register.getPackage());
        assertEquals(token(), register.getStringExtra("token"));
        assertEquals(36, token().length());
        assertEquals(context.getPackageName(), register.getStringExtra("application"));
        assertTrue(register.getParcelableExtra("pi") instanceof PendingIntent);

        // Each start signs up again with the same token, which keeps the same address.
        String first = token();
        UnifiedPush.sync(context, true);
        assertEquals(first, token());
        assertEquals(first, last(UnifiedPush.REGISTER).getStringExtra("token"));

        // Another app: the old sign-up ends and the new one gets a token of its own.
        UnifiedPush.prefs(context).edit().putString(UnifiedPush.CHOSEN, OTHER).commit();
        UnifiedPush.sync(context, true);
        Intent ended = last(UnifiedPush.UNREGISTER);
        assertEquals(UnifiedPush.NTFY, ended.getPackage());
        assertEquals(first, ended.getStringExtra("token"));
        assertEquals(OTHER, last(UnifiedPush.REGISTER).getPackage());
        assertNotEquals(first, token());
        assertEquals(token(), last(UnifiedPush.REGISTER).getStringExtra("token"));
    }

    @Test public void anAddressCountsOnlyWithTheSignUpsTokenAndGoesToTelegram() {
        install(UnifiedPush.NTFY);
        Settings.UNIFIED_PUSH.save(true);
        UnifiedPush.sync(context, true);
        int[] done = {0};
        UnifiedPush.receive(context, from(UnifiedPush.NEW_ENDPOINT, "someone-else").putExtra("endpoint", ENDPOINT), () -> done[0]++);
        assertNull(UnifiedPush.prefs(context).getString(UnifiedPush.ENDPOINT, null));
        for (String bad : new String[]{"javascript:alert(1)", "https://", "ftp://host/x", ""}) {
            UnifiedPush.receive(context, from(UnifiedPush.NEW_ENDPOINT, token()).putExtra("endpoint", bad), () -> done[0]++);
        }
        assertNull(UnifiedPush.prefs(context).getString(UnifiedPush.ENDPOINT, null));
        assertTrue(registered.isEmpty());

        UnifiedPush.receive(context, from(UnifiedPush.NEW_ENDPOINT, token()).putExtra("endpoint", ENDPOINT), () -> done[0]++);
        assertEquals(ENDPOINT, UnifiedPush.prefs(context).getString(UnifiedPush.ENDPOINT, null));
        assertEquals(java.util.Collections.singletonList(ENDPOINT), registered);
        assertEquals("every broadcast is finished", 6, done[0]);

        // Paused, the address is kept for later and Telegram keeps the one it has.
        registered.clear();
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        UnifiedPush.receive(context, from(UnifiedPush.NEW_ENDPOINT, token()).putExtra("endpoint", ENDPOINT + "2"), () -> {});
        assertEquals(ENDPOINT + "2", UnifiedPush.prefs(context).getString(UnifiedPush.ENDPOINT, null));
        assertTrue(registered.isEmpty());
    }

    @Test public void aMessageWakesTelegramAndIsAcknowledged() {
        String token = signedUp();
        int[] done = {0};
        UnifiedPush.receive(context, from(UnifiedPush.MESSAGE, token).putExtra("bytesMessage", "version=1".getBytes())
                .putExtra("id", "m1"), () -> done[0]++);
        assertEquals(1, wakes.size());
        assertEquals("the broadcast stays open until Telegram has been told", 0, done[0]);
        wakes.get(0).run();
        wakes.get(0).run();
        assertEquals(1, done[0]);
        Intent ack = last(UnifiedPush.MESSAGE_ACK);
        assertEquals(UnifiedPush.NTFY, ack.getPackage());
        assertEquals(token, ack.getStringExtra("token"));
        assertEquals("m1", ack.getStringExtra("id"));

        // Somebody else's message, or one while paused, wakes nothing and still finishes.
        UnifiedPush.receive(context, from(UnifiedPush.MESSAGE, "someone-else"), () -> done[0]++);
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        UnifiedPush.receive(context, from(UnifiedPush.MESSAGE, token), () -> done[0]++);
        assertEquals(1, wakes.size());
        assertEquals(3, done[0]);
    }

    @Test public void anEndedSignUpStaysEndedUntilTheSwitchGoesOffAndOn() {
        String token = signedUp();
        assertEquals("Telegram's wake-ups come through io.heckel.ntfy.", UnifiedPush.status(context, true));
        UnifiedPush.receive(context, from(UnifiedPush.REGISTRATION_FAILED, token), () -> {});
        assertEquals("io.heckel.ntfy turned the sign-up down. Check that it allows UnifiedPush.", UnifiedPush.status(context, true));
        assertNull(UnifiedPush.activeEndpoint());

        UnifiedPush.receive(context, from(UnifiedPush.UNREGISTERED, token), () -> {});
        assertEquals("io.heckel.ntfy ended the sign-up. Turn the switch off and on to sign up again.", UnifiedPush.status(context, true));
        int registers = count(UnifiedPush.REGISTER);
        UnifiedPush.sync(context, true);
        assertEquals("a new start doesn't sign up again behind the app's back", registers, count(UnifiedPush.REGISTER));

        UnifiedPush.sync(context, false);
        assertEquals("io.heckel.ntfy. Turn the switch on to sign up with it.", UnifiedPush.status(context, false));
        UnifiedPush.sync(context, true);
        assertEquals(registers + 1, count(UnifiedPush.REGISTER));
        assertEquals("Signing up with io.heckel.ntfy…", UnifiedPush.status(context, true));
    }

    @Test public void turningItOffEndsTheSignUpAndForgetsTheAddress() {
        String token = signedUp();
        UnifiedPush.prefs(context).edit().putString(UnifiedPush.CHOSEN, UnifiedPush.NTFY).commit();
        Settings.UNIFIED_PUSH.save(false);
        UnifiedPush.sync(context, false);
        Intent ended = last(UnifiedPush.UNREGISTER);
        assertEquals(UnifiedPush.NTFY, ended.getPackage());
        assertEquals(token, ended.getStringExtra("token"));
        assertNull(token());
        assertNull(UnifiedPush.prefs(context).getString(UnifiedPush.ENDPOINT, null));
        assertEquals("the choice of app stays", UnifiedPush.NTFY, UnifiedPush.prefs(context).getString(UnifiedPush.CHOSEN, null));
        // Its last address is gone, so a broadcast carrying the old token is somebody else's now.
        UnifiedPush.receive(context, from(UnifiedPush.MESSAGE, token), () -> {});
        assertTrue(wakes.isEmpty());
        assertNull(UnifiedPush.raise(context));
    }

    @Test public void withNoAppInstalledTheRowSaysWhatToInstall() {
        assertNull(UnifiedPush.chosen(context));
        assertEquals("Install a UnifiedPush app like ntfy, then come back here.", UnifiedPush.status(context, true));
        UnifiedPush.sync(context, true);
        assertNull(last(UnifiedPush.REGISTER));
        assertNull(token());
    }

    @Test public void outsideThisBuildNothingAnswers() {
        String token = signedUp();
        assertNotNull(UnifiedPush.raise(context));
        PatchFamilyForTests.inBuild();
        int registers = count(UnifiedPush.REGISTER);
        UnifiedPush.onTelegramStart(context);
        UnifiedPush.switched(context, true);
        UnifiedPush.receive(context, from(UnifiedPush.MESSAGE, token), () -> {});
        assertTrue(wakes.isEmpty());
        assertNull(UnifiedPush.raise(context));
        assertEquals(registers, count(UnifiedPush.REGISTER));
    }

    private int count(String action) {
        int n = 0;
        for (Intent intent : sent()) if (action.equals(intent.getAction())) n++;
        return n;
    }
}
