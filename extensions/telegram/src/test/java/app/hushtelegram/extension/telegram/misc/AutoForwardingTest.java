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
        // Your own messages stay: Telegram hands them over only when a scheduled one goes out.
        AutoForwardingForTests.Message scheduled = message(B, 3);
        scheduled.out = true;
        arrive(scheduled);
        assertEquals(1, telegram.sent.size());
        // Someone else's message in B still goes to A.
        arrive(message(B, 4));
        assertEquals("0: [12/4] to 11", telegram.sent.get(1));
    }

    @Test public void rulesOnTwoAccountsPointingAtEachOtherDontLoop() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(2000, B, A, false)));
        arrive(message(A, 1));
        assertEquals(Collections.singletonList("0: [11/1] to 12"), telegram.sent);
        // Account 1 sees account 0's forward come into B from someone else, and leaves it.
        AutoForwardingForTests.Message landed = new AutoForwardingForTests.Message(1, B, 2);
        landed.forwarded = true;
        landed.sender = 1000;
        arrive(landed);
        assertEquals(1, telegram.sent.size());
        // A forward someone else sent into B still goes.
        AutoForwardingForTests.Message theirs = new AutoForwardingForTests.Message(1, B, 3);
        theirs.forwarded = true;
        theirs.sender = 5000;
        arrive(theirs);
        assertEquals("1: [12/3] to 11", telegram.sent.get(1));
        // And something account 0 writes in B, not a forward, goes too.
        AutoForwardingForTests.Message written = new AutoForwardingForTests.Message(1, B, 4);
        written.sender = 1000;
        arrive(written);
        assertEquals("1: [12/4] to 11", telegram.sent.get(2));
    }

    @Test public void aForwardFromTheDestinationStays() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        // A channel's post its discussion group got, with a rule from the group back to the channel.
        AutoForwardingForTests.Message post = message(A, 1);
        post.forwarded = true;
        post.sender = B;
        post.origin = B;
        arrive(post);
        assertEquals(Collections.emptyList(), telegram.sent);
        AutoForwardingForTests.Message elsewhere = message(A, 2);
        elsewhere.forwarded = true;
        elsewhere.sender = 5000;
        elsewhere.origin = C;
        arrive(elsewhere);
        assertEquals(Collections.singletonList("0: [11/2] to 12"), telegram.sent);
    }

    @Test public void channelsOnTwoAccountsDontLoop() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(2000, B, A, false)));
        // A post in channel A that was itself forwarded from channel C at second 500.
        AutoForwardingForTests.Message post = message(A, 1);
        post.forwarded = true;
        post.sender = A;
        post.origin = C;
        post.originDate = 500;
        arrive(post);
        assertEquals(Collections.singletonList("0: [11/1] to 12"), telegram.sent);
        // Account 1 sees the copy in channel B, posted as B and still naming C, and leaves it.
        AutoForwardingForTests.Message copy = new AutoForwardingForTests.Message(1, B, 2);
        copy.forwarded = true;
        copy.sender = B;
        copy.origin = C;
        copy.originDate = 500;
        arrive(copy);
        assertEquals(1, telegram.sent.size());
        // Had it gone round, the next copy in A wouldn't go to B a second time either.
        AutoForwardingForTests.Message again = message(A, 3);
        again.forwarded = true;
        again.sender = A;
        again.origin = C;
        again.originDate = 500;
        arrive(again);
        assertEquals(1, telegram.sent.size());
        // Another post forwarded from C, sent at another time, still goes.
        AutoForwardingForTests.Message other = message(A, 4);
        other.forwarded = true;
        other.sender = A;
        other.origin = C;
        other.originDate = 501;
        arrive(other);
        assertEquals("0: [11/4] to 12", telegram.sent.get(1));
    }

    @Test public void aDiscussionCopyOfAForwardStays() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        // Someone writes in group A at second 40, and the rule sends it to channel B.
        AutoForwardingForTests.Message written = message(A, 1);
        written.sender = 7000;
        written.date = 40;
        arrive(written);
        assertEquals(Collections.singletonList("0: [11/1] to 12"), telegram.sent);
        // B's discussion group is A, and the post comes back there naming its first author.
        AutoForwardingForTests.Message discussion = message(A, 2);
        discussion.forwarded = true;
        discussion.sender = B;
        discussion.origin = 7000;
        discussion.originDate = 40;
        arrive(discussion);
        assertEquals(1, telegram.sent.size());
    }

    @Test public void anAlbumsPartsGoTogether() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        // Three photos forwarded from one person's album share an author and a date.
        List<Object> parts = new ArrayList<>();
        for (int id = 1; id <= 3; id++) {
            AutoForwardingForTests.Message part = message(A, id);
            part.forwarded = true;
            part.sender = 7000;
            part.origin = 8000;
            part.originDate = 90;
            part.media = true;
            parts.add(part);
        }
        arrive(parts.toArray());
        assertEquals(Collections.singletonList("0: [11/1, 11/2, 11/3] to 12"), telegram.sent);
    }

    @Test public void anAlbumThatArrivesInTwoPartsAllGoes() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        List<Object> first = new ArrayList<>();
        List<Object> rest = new ArrayList<>();
        for (int id = 1; id <= 3; id++) {
            AutoForwardingForTests.Message part = message(A, id);
            part.forwarded = true;
            part.sender = 7000;
            part.origin = 8000;
            part.originDate = 90;
            part.media = true;
            part.mediaId = 500 + id;
            (id < 3 ? first : rest).add(part);
        }
        arrive(first.toArray());
        arrive(rest.toArray());
        assertEquals(Arrays.asList("0: [11/1, 11/2] to 12", "0: [11/3] to 12"), telegram.sent);
    }

    @Test public void aCopyFromAHiddenAuthorStays() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        AutoForwardingForTests.Message written = message(A, 1);
        written.sender = 7000;
        written.date = 40;
        written.text = "see you at 6";
        arrive(written);
        assertEquals(1, telegram.sent.size());
        // The author hides who they are, so the copy that comes back has only their name.
        AutoForwardingForTests.Message copy = message(A, 2);
        copy.forwarded = true;
        copy.sender = B;
        copy.originName = "Pat";
        copy.originDate = 40;
        copy.text = "see you at 6";
        arrive(copy);
        assertEquals(1, telegram.sent.size());
    }

    @Test public void aHiddenAuthorsOtherTextFromTheSameSecondGoes() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        AutoForwardingForTests.Message written = message(A, 1);
        written.sender = 7000;
        written.date = 1000;
        written.text = "lunch?";
        arrive(written);
        // Somebody else's text, first sent by a hidden author in that same second, isn't a copy of it.
        AutoForwardingForTests.Message other = message(A, 2);
        other.forwarded = true;
        other.sender = 7001;
        other.originName = "Pat";
        other.originDate = 1000;
        other.text = "the train is late";
        arrive(other);
        assertEquals(Arrays.asList("0: [11/1] to 12", "0: [11/2] to 12"), telegram.sent);
    }

    @Test public void twoHiddenAuthorsInOneSecondBothGo() {
        Settings.AUTO_FORWARDING.save(true);
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        AutoForwardingForTests.Message one = message(A, 1);
        one.forwarded = true;
        one.sender = 7000;
        one.originName = "Pat";
        one.originDate = 50;
        arrive(one);
        AutoForwardingForTests.Message other = message(A, 2);
        other.forwarded = true;
        other.sender = 7000;
        other.originName = "Sam";
        other.originDate = 50;
        arrive(other);
        assertEquals(Arrays.asList("0: [11/1] to 12", "0: [11/2] to 12"), telegram.sent);
        // The first one forwarded again is still a copy.
        AutoForwardingForTests.Message again = message(A, 3);
        again.forwarded = true;
        again.sender = 7001;
        again.originName = "Pat";
        again.originDate = 50;
        arrive(again);
        assertEquals(2, telegram.sent.size());
    }

    @Test public void aRuleAddedWhilePausedKeepsTheOthers() {
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, B, false)));
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        assertEquals(Collections.singletonList(new AutoForwarding.Rule(1000, A, B, false)), AutoForwarding.rules());
        assertTrue(AutoForwarding.add(new AutoForwarding.Rule(1000, A, C, false)));
        assertEquals("1000 11 12 0\n1000 11 13 0", Settings.AUTO_FORWARDING_RULES.savedValue());
        assertTrue(AutoForwarding.remove(new AutoForwarding.Rule(1000, A, B, false)));
        assertEquals("1000 11 13 0", Settings.AUTO_FORWARDING_RULES.savedValue());
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
        telegram.chats(0, 0, 77L, A, B, 1000L, 99L).chats(0, 1, C);
        telegram.names.put(A, "Alice");
        telegram.names.put(B, "Bob");
        telegram.secret.add(99L);
        telegram.folders.add(77L);
        telegram.readOnly.add(B);
        AutoForwarding.Shown shown = AutoForwarding.shown();
        assertEquals(1000, shown.user);
        assertEquals(Collections.singletonList(new AutoForwarding.Rule(1000, A, B, false)), shown.rules);
        // Your own chat is Saved Messages, a secret chat and the archive entry are left out, and a
        // chat with no name shows its id.
        assertEquals(Arrays.asList("11 Alice", "12 Bob", "1000 Saved Messages", "13 13"), ids(shown.chats));
        assertEquals("Bob", shown.name(B));
        assertEquals("404", shown.name(404));
        // Saved Messages can take forwards but never hands any over; Bob's chat can't be posted in.
        assertEquals(Arrays.asList("11 Alice", "12 Bob", "13 13"), ids(shown.sources()));
        assertEquals(Arrays.asList("1000 Saved Messages", "13 13"), ids(shown.destinations(A)));
    }

    private static List<String> ids(List<AutoForwarding.Chat> chats) {
        List<String> names = new ArrayList<>();
        for (AutoForwarding.Chat chat : chats) names.add(chat.id + " " + chat.name);
        return names;
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
