/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AutoForwardingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long A = 11;
    private static final long B = 12;
    private static final long C = 13;

    private AutoForwardingForTests.Fake telegram;

    @Before public void setUp() {
        telegram = AutoForwardingForTests.use(new AutoForwardingForTests.Fake());
        telegram.users.put(0, 1000L);
        telegram.users.put(1, 2000L);
        Settings.AUTO_FORWARDING.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        AutoForwardingForTests.restore();
        PauseForTests.resume();
        Settings.AUTO_FORWARDING.resetToDefault();
        HookStatus.clear();
    }

    private static AutoForwardingForTests.Message message(long dialog, int id) {
        return new AutoForwardingForTests.Message(0, dialog, id);
    }

    private static void arrive(Object... messages) {
        AutoForwarding.onNewMessages(new ArrayList<>(Arrays.asList(messages)), 0);
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    @Test public void aRuleForwardsANewMessageOnce() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        AutoForwardingForTests.Message first = message(A, 7);
        arrive(first, message(C, 8));
        assertEquals(Collections.singletonList("0: [11/7] to 12"), telegram.sent);
        assertTrue(report(), report().contains("messages forwarded 1"));
        // The same message handed over again, as Telegram does on a reconnect, isn't sent twice.
        arrive(first);
        assertEquals(1, telegram.sent.size());
    }

    @Test public void offOrPausedNothingGoes() {
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        assertFalse("off by default", Settings.AUTO_FORWARDING.get());
        arrive(message(A, 1));
        assertEquals(Collections.emptyList(), telegram.sent);
        assertFalse(report(), report().contains("messages checked for forwarding"));

        Settings.AUTO_FORWARDING.save(true);
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        arrive(message(A, 2));
        assertEquals(Collections.emptyList(), telegram.sent);
        PauseForTests.resume();
        arrive(message(A, 3));
        assertEquals(Collections.singletonList("0: [11/3] to 12"), telegram.sent);
    }

    @Test public void twoRulesPointingAtEachOtherDontLoop() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, B, A, false)));
        arrive(message(A, 1));
        assertEquals(Collections.singletonList("0: [11/1] to 12"), telegram.sent);
        // The forward lands in B as your own forwarded message, and stays there.
        AutoForwardingForTests.Message landed = message(B, 2);
        landed.out = true;
        landed.forwarded = true;
        arrive(landed);
        assertEquals(1, telegram.sent.size());
        // Something you write in B yourself still goes to A.
        AutoForwardingForTests.Message yours = message(B, 3);
        yours.out = true;
        arrive(yours);
        assertEquals("0: [12/3] to 11", telegram.sent.get(1));
    }

    @Test public void aMediaRuleTakesOnlyMedia() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, true)));
        AutoForwardingForTests.Message photo = message(A, 2);
        photo.media = true;
        arrive(message(A, 1), photo);
        assertEquals(Collections.singletonList("0: [11/2] to 12"), telegram.sent);
    }

    @Test public void serviceMessagesPushesAndOtherAccountsStay() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        AutoForwardingForTests.Message joined = message(A, 1);
        joined.service = true;
        arrive(joined);
        // A batch from a push comes again once Telegram connects, and goes then.
        AutoForwarding.onNewMessages(new ArrayList<>(Collections.singletonList(message(A, 2))), 1);
        // Account 1 is someone else, whose chat A has no rule.
        arrive(new AutoForwardingForTests.Message(1, A, 3));
        assertEquals(Collections.emptyList(), telegram.sent);
        arrive(message(A, 2));
        assertEquals(Collections.singletonList("0: [11/2] to 12"), telegram.sent);
    }

    @Test public void atMostTwentyAMinute() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        List<Object> burst = new ArrayList<>();
        for (int id = 1; id <= AutoForwarding.PER_MINUTE + 5; id++) burst.add(message(A, id));
        arrive(burst.toArray());
        assertEquals("one forward with the first twenty", 1, telegram.sent.size());
        assertTrue(telegram.sent.get(0).contains("11/20]"));
        assertFalse(telegram.sent.get(0).contains("11/21"));
        assertTrue(report(), report().contains("forwards over the per-minute limit 5"));
        telegram.now += 60_000L;
        arrive(message(A, 100));
        assertEquals("0: [11/100] to 12", telegram.sent.get(1));
    }

    @Test public void aRefusedForwardIsCounted() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        telegram.answer = 3;
        arrive(message(A, 1));
        assertTrue(report(), report().contains("forwards Telegram refused 1"));
    }

    @Test public void rulesSaveOnePerPairAndReadBack() {
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        assertFalse("a pair already on the list", AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, true)));
        assertFalse("back into its own chat", AutoForwarding.add(new AutoForwarding.Rule(1000, A, A, false)));
        assertFalse("no one signed in", AutoForwarding.add(new AutoForwarding.Rule(0, A, B, false)));
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(2000, A, C, true)));
        assertEquals("1000 11 12 0\n2000 11 13 1", Settings.AUTO_FORWARDING_RULES.get());
        assertEquals(AutoForwarding.rules(), AutoForwarding.parse(AutoForwarding.format(AutoForwarding.rules())));
        // Lines that don't read are left out.
        assertEquals(Collections.singletonList(new AutoForwarding.Rule(1, 2, 3, false)),
                AutoForwarding.parse("1 2 3 0\nx 2 3 0\n1 2 2 0\n1 2 3 1\n1 2 3\n1 4 5 2"));
        assertTrue(AutoForwarding.remove(new AutoForwarding.Rule(1000, A, B, false)));
        assertEquals("2000 11 13 1", Settings.AUTO_FORWARDING_RULES.get());
    }

    @Test public void theRulesPageSeesTheShownAccountOnly() {
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(2000, A, C, false)));
        telegram.chats(0, 0, A, B, 1000L, 99L).chats(0, 1, C);
        telegram.names.put(A, "Alice");
        telegram.names.put(B, "Bob");
        telegram.secret.add(99L);
        AutoForwarding.Shown shown = AutoForwarding.shown();
        assertEquals(1000, shown.user);
        assertEquals(Collections.singletonList(new AutoForwarding.Rule(1000, A, B, false)), shown.rules);
        List<String> names = new ArrayList<>();
        for (AutoForwarding.Chat chat : shown.chats) names.add(chat.id + " " + chat.name);
        // Your own chat is Saved Messages, a secret chat is left out, and a chat with no name shows its id.
        assertEquals(Arrays.asList("11 Alice", "12 Bob", "1000 Saved Messages", "13 13"), names);
        assertEquals("Bob", shown.name(B));
        assertEquals("404", shown.name(404));
    }

    @Test public void aBrokenTelegramLeavesThePageEmpty() {
        AutoForwarding.telegram = new AutoForwardingForTests.Fake() {
            @Override public int selectedAccount() {
                throw new IllegalStateException("Telegram changed");
            }
        };
        assertNull(AutoForwarding.shown());
        assertTrue(report(), report().contains(FamilyNames.AUTO_FORWARDING));
    }
}
