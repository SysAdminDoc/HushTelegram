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
import app.hushtelegram.extension.telegram.settings.Settings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class OutsideTranslateTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final AtomicInteger fetches = new AtomicInteger();
    private OutsideTranslate.Fetcher originalFetcher;
    private java.util.concurrent.Executor originalExecutor;

    /** A message the tests can read and change without Telegram's classes. */
    private static final class Fake implements OutsideTranslate.Message {
        long dialog = 10;
        int id = 1;
        boolean eligible = true;
        String original = "Привет, мир";
        boolean translated;
        int applied;
        int refreshed;
        String shown;

        @Override public long dialog() { return dialog; }
        @Override public int id() { return id; }
        @Override public boolean eligible() { return eligible; }
        @Override public String original() { return original; }
        @Override public boolean translated() { return translated; }
        @Override public void apply(String text, String language) { applied++; shown = text; translated = true; }
        @Override public void refresh() { refreshed++; }
    }

    @Before public void reset() {
        restore();
        originalFetcher = OutsideTranslate.fetcher;
        originalExecutor = OutsideTranslate.executor;
        OutsideTranslate.executor = Runnable::run;
        OutsideTranslate.fetcher = (target, text) -> {
            fetches.incrementAndGet();
            return new GoogleTranslate.Result("Hello, world", "ru");
        };
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.OUTSIDE_TRANSLATE);
        Settings.OUTSIDE_TRANSLATE.resetToDefault();
        HookStatus.clear();
        OutsideTranslate.resetForTests();
        app.hushtelegram.extension.shared.Utils.getContext().getSharedPreferences("hushtelegram_outside_translate", 0).edit().clear().commit();
    }

    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.OUTSIDE_TRANSLATE);
        Settings.OUTSIDE_TRANSLATE.resetToDefault();
        HookStatus.clear();
        if (originalFetcher != null) OutsideTranslate.fetcher = originalFetcher;
        if (originalExecutor != null) OutsideTranslate.executor = originalExecutor;
    }

    @Test public void offByDefaultChangesNothingAndSendsNothing() {
        assertFalse(Settings.OUTSIDE_TRANSLATE.get());
        OutsideTranslate.toggleChat(10);
        Fake message = new Fake();
        assertFalse(OutsideTranslate.showMessage(message, message));
        assertEquals(0, fetches.get());
        assertEquals(0, message.applied);
    }

    @Test public void aChatThatIsOnAsksOnceThenShowsTheTranslation() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        Fake message = new Fake();
        // The original stays until the answer lands.
        assertFalse(OutsideTranslate.showMessage(message, message));
        assertEquals(1, fetches.get());
        ShadowLooper.idleMainLooper();
        assertEquals("The chat is told to draw the message again", 1, message.refreshed);
        assertTrue(OutsideTranslate.showMessage(message, message));
        assertEquals(1, message.applied);
        assertTrue(message.shown.startsWith("Hello, world\n\n"));
        // A message already showing it isn't laid out again, and its answer still skips Telegram's own check.
        assertTrue(OutsideTranslate.showMessage(message, message));
        assertEquals(1, message.applied);
        assertEquals(1, fetches.get());
    }

    @Test public void theSameTextInTwoMessagesIsAskedOnce() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        Fake first = new Fake();
        Fake second = new Fake();
        second.id = 2;
        OutsideTranslate.showMessage(first, first);
        OutsideTranslate.showMessage(second, second);
        assertEquals(1, fetches.get());
        assertTrue(OutsideTranslate.showMessage(second, second));
    }

    @Test public void turningTheChatOffClearsTheChoicesMadeMessageByMessage() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        assertTrue(OutsideTranslate.wanted(10, 1));
        OutsideTranslate.toggleChat(10);
        assertFalse(OutsideTranslate.wanted(10, 1));
        assertFalse(OutsideTranslate.wanted(11, 1));
    }

    @Test public void textAlreadyInTheAppLanguageIsLeftAlone() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        OutsideTranslate.fetcher = (target, text) -> new GoogleTranslate.Result("same", target);
        Fake message = new Fake();
        OutsideTranslate.showMessage(message, message);
        ShadowLooper.idleMainLooper();
        assertFalse(OutsideTranslate.showMessage(message, message));
        assertEquals(0, message.applied);
    }

    @Test public void aFailedRequestIsNotRepeatedAtOnceAndNothingIsShown() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        OutsideTranslate.fetcher = (target, text) -> {
            fetches.incrementAndGet();
            throw new java.io.IOException("offline");
        };
        Fake message = new Fake();
        assertFalse(OutsideTranslate.showMessage(message, message));
        ShadowLooper.idleMainLooper();
        assertFalse(OutsideTranslate.showMessage(message, message));
        assertEquals(1, fetches.get());
        assertEquals(0, message.applied);
        assertEquals(0, message.refreshed);
    }

    @Test public void aMessageThatIsNotEligibleIsNeverSent() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        Fake message = new Fake();
        message.eligible = false;
        assertFalse(OutsideTranslate.showMessage(message, message));
        assertEquals(0, fetches.get());
    }

    @Test public void telegramsOwnTranslationOfAMessageIsLeftAlone() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        Fake message = new Fake();
        message.translated = true;
        assertFalse(OutsideTranslate.showMessage(message, message));
        assertEquals(0, fetches.get());
    }

    @Test public void pausedTakesTelegramsPathAndSendsNothing() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        Fake message = new Fake();
        assertFalse(OutsideTranslate.showMessage(message, message));
        assertEquals(0, fetches.get());
    }

    @Test public void aTurnedOffSwitchSendsTelegramsCodeOnToPutTheOriginalBack() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        Fake message = new Fake();
        OutsideTranslate.showMessage(message, message);
        ShadowLooper.idleMainLooper();
        assertTrue(OutsideTranslate.showMessage(message, message));
        Settings.OUTSIDE_TRANSLATE.save(false);
        assertFalse(OutsideTranslate.showMessage(message, message));
    }

    @Test public void theChatsThatAreOnAreRememberedOnThePhone() {
        assertTrue(OutsideTranslate.toggleChat(42));
        OutsideTranslate.resetForTests();
        assertTrue(OutsideTranslate.chatOn(42));
        assertFalse(OutsideTranslate.chatOn(43));
        assertFalse(OutsideTranslate.toggleChat(42));
        OutsideTranslate.resetForTests();
        assertFalse(OutsideTranslate.chatOn(42));
    }

    @Test public void theCacheKeepsEachLanguageApart() {
        OutsideTranslate.resetForTests();
        assertNull(OutsideTranslate.cached("de", "x"));
        assertNotEquals(OutsideTranslate.cacheKey("de", "x"), OutsideTranslate.cacheKey("en", "x"));
    }

    @Test public void googlesCodesForJavasLanguages() {
        assertEquals("id", OutsideTranslate.tagOf(new Locale("in", "ID")));
        assertEquals("he", OutsideTranslate.tagOf(new Locale("iw")));
        assertEquals("zh-TW", OutsideTranslate.tagOf(new Locale("zh", "TW")));
        assertEquals("zh-CN", OutsideTranslate.tagOf(new Locale("zh", "CN")));
        assertEquals("pt", OutsideTranslate.tagOf(new Locale("pt", "BR")));
        assertEquals("en", OutsideTranslate.tagOf(null));
    }

    @Test public void codeBlocksAreLeftAsWritten() {
        class TL_messageEntityPre {}
        class TL_messageEntityBold {}
        assertTrue(OutsideTranslate.hasCode(Arrays.asList(new TL_messageEntityBold(), new TL_messageEntityPre())));
        assertFalse(OutsideTranslate.hasCode(Collections.singletonList(new TL_messageEntityBold())));
        assertFalse(OutsideTranslate.hasCode(null));
    }

    @Test public void theMenuEntryGoesAfterTelegramsOwnTranslateAndKeepsTheListsInStep() {
        List<Object> icons = new ArrayList<>(Arrays.asList(1, 2, 3));
        List<Object> items = new ArrayList<>(Arrays.asList("Reply", "Translate", "Delete"));
        List<Object> options = new ArrayList<>(Arrays.asList(8, 29, 1));
        OutsideTranslate.offer(icons, items, options, false);
        assertEquals(Arrays.asList(8, 29, OutsideTranslate.MENU_OPTION, 1), options);
        assertEquals("Translate here", items.get(2));
        assertEquals(4, icons.size());

        List<Object> bare = new ArrayList<>(Arrays.asList("Reply"));
        List<Object> bareOptions = new ArrayList<>(Arrays.asList(8));
        OutsideTranslate.offer(new ArrayList<>(Arrays.asList(1)), bare, bareOptions, true);
        assertEquals("Show original", bare.get(1));
        assertEquals(Arrays.asList(8, OutsideTranslate.MENU_OPTION), bareOptions);

        // Lists already out of step are left alone.
        List<Object> odd = new ArrayList<>(Arrays.asList(1));
        OutsideTranslate.offer(odd, new ArrayList<>(Arrays.asList("a", "b")), new ArrayList<>(Arrays.asList(8, 9)), false);
        assertEquals(1, odd.size());
    }

    @Test public void theNumbersStayPastTelegramsAndTheMessageMenuFamily() {
        assertTrue(OutsideTranslate.MENU_OPTION > 0x48544d04);
        assertNotEquals(OutsideTranslate.MENU_OPTION, OutsideTranslate.HEADER_ITEM);
        assertFalse(OutsideTranslate.headerClick(new Object(), 5));
        OutsideTranslate.chosen(new Object(), 5);
    }

    @Test public void theTargetIsTheAppLanguageOrThePhones() {
        assertFalse(OutsideTranslate.target().isEmpty());
    }
}
