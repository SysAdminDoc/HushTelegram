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
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, shadows = OutsideTranslateTest.HeaderHost.class)
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
        HeaderHost.refreshed = 0;
        app.hushtelegram.extension.shared.Utils.getContext().getSharedPreferences("hushtelegram_outside_translate", 0).edit().clear().commit();
    }

    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.OUTSIDE_TRANSLATE);
        Settings.OUTSIDE_TRANSLATE.resetToDefault();
        HookStatus.clear();
        if (originalFetcher != null) OutsideTranslate.fetcher = originalFetcher;
        if (originalExecutor != null) OutsideTranslate.executor = originalExecutor;
        OutsideTranslate.clock = System::currentTimeMillis;
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
        assertTrue(message.shown.endsWith("(Translated. Open the message menu for the original.)"));
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

    @Test public void aFailureWhoseWaitIsOverIsForgottenWhenTheNextOneLands() {
        long[] now = {1_000_000L};
        OutsideTranslate.clock = () -> now[0];
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        OutsideTranslate.fetcher = (target, text) -> {
            fetches.incrementAndGet();
            throw new java.io.IOException("offline");
        };
        Fake first = new Fake();
        OutsideTranslate.showMessage(first, first);
        now[0] += 59_999L;
        Fake second = new Fake();
        second.id = 2;
        second.original = "Пока, мир";
        OutsideTranslate.showMessage(second, second);
        assertEquals("both still wait", 2, OutsideTranslate.failuresForTests());
        now[0] += 1L;
        Fake third = new Fake();
        third.id = 3;
        third.original = "Доброе утро";
        OutsideTranslate.showMessage(third, third);
        assertEquals("the first one's minute is up, so only the newer two are kept", 2, OutsideTranslate.failuresForTests());
        // The forgotten text is asked again, as it would have been anyway.
        OutsideTranslate.showMessage(first, first);
        assertEquals(4, fetches.get());
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

    /** The settings row promises nothing is sent until a chat or message is turned on, so switching off starts over. */
    @Test public void turningTheSwitchOffForgetsTheChatsSoTurningItBackOnSendsNothing() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        Settings.OUTSIDE_TRANSLATE.save(false);
        assertFalse(OutsideTranslate.active());
        Settings.OUTSIDE_TRANSLATE.save(true);
        assertFalse(OutsideTranslate.chatOn(10));
        OutsideTranslate.resetForTests();
        assertFalse("the saved list kept the chat", OutsideTranslate.chatOn(10));
        Fake message = new Fake();
        assertFalse(OutsideTranslate.showMessage(message, message));
        assertEquals(0, fetches.get());
    }

    @Test public void theSwitchRowForgetsTheChatsAsItGoesOff() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        OutsideTranslate.forgetChats();
        OutsideTranslate.resetForTests();
        assertFalse(OutsideTranslate.chatOn(10));
    }

    /** Pause is a break, not a choice, so the chats turned on come back with Resume. */
    @Test public void pausingKeepsTheChatsTurnedOn() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        assertFalse(OutsideTranslate.active());
        PauseForTests.resume();
        assertTrue(OutsideTranslate.chatOn(10));
    }

    @Test public void aFailureThatLandsAfterTheSwitchWentOffSaysNothing() {
        List<Runnable> queued = new ArrayList<>();
        OutsideTranslate.executor = queued::add;
        OutsideTranslate.fetcher = (target, text) -> { throw new java.io.IOException("offline"); };
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        Fake message = new Fake();
        assertFalse(OutsideTranslate.showMessage(message, message));
        assertEquals(1, queued.size());
        Settings.OUTSIDE_TRANSLATE.save(false);
        queued.get(0).run();
        ShadowLooper.idleMainLooper();
        assertNull(org.robolectric.shadows.ShadowToast.getLatestToast());
        assertEquals(0, message.refreshed);
    }

    @Test public void googleIsTheServiceUntilAKeyIsSavedAndUseGoogleForgetsTheKey() {
        assertFalse(OutsideTranslate.service().enabled());
        assertTrue(OutsideTranslate.saveService(" https://openrouter.ai/api/v1 ", "deepseek/deepseek-chat", " sk-1 "));
        OutsideTranslate.resetForTests();
        // Read back from the phone, trimmed.
        AiTranslate.Service saved = OutsideTranslate.service();
        assertTrue(saved.enabled());
        assertEquals("https://openrouter.ai/api/v1", saved.address);
        assertEquals("deepseek/deepseek-chat", saved.model);
        assertEquals("sk-1", saved.key);
        assertTrue(OutsideTranslate.saveService(saved.address, saved.model, ""));
        OutsideTranslate.resetForTests();
        assertFalse(OutsideTranslate.service().enabled());
        assertEquals("the address is kept for next time", "https://openrouter.ai/api/v1", OutsideTranslate.service().address);
        assertFalse(app.hushtelegram.extension.shared.Utils.getContext()
                .getSharedPreferences("hushtelegram_outside_translate", 0).contains("ai_key"));
    }

    @Test public void aNewServiceDropsTheOldTranslationsAndAnAnswerStillOut() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        Fake message = new Fake();
        OutsideTranslate.showMessage(message, message);
        ShadowLooper.idleMainLooper();
        assertTrue(OutsideTranslate.showMessage(message, message));
        assertEquals(1, fetches.get());

        List<Runnable> queued = new ArrayList<>();
        OutsideTranslate.executor = queued::add;
        assertTrue(OutsideTranslate.saveService("https://api.openai.com/v1", "gpt-4o-mini", "sk-1"));
        // The translation from Google is gone, so the next look asks the new service.
        Fake again = new Fake();
        assertFalse(OutsideTranslate.showMessage(again, again));
        assertEquals(1, queued.size());
        // The service changes again before that answer lands: the answer isn't kept or shown.
        assertTrue(OutsideTranslate.saveService("https://api.openai.com/v1", "gpt-4o", "sk-1"));
        queued.get(0).run();
        ShadowLooper.idleMainLooper();
        assertEquals(0, again.refreshed);
        assertNull(OutsideTranslate.cached(OutsideTranslate.target(), again.original));
    }

    @Test public void aKeyTheServiceTurnsDownSaysSo() {
        org.robolectric.shadows.ShadowToast.reset();
        OutsideTranslate.fetcher = (target, text) -> { throw new AiTranslate.KeyRefused(401); };
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        Fake message = new Fake();
        OutsideTranslate.showMessage(message, message);
        ShadowLooper.idleMainLooper();
        assertEquals("Your AI service didn't accept the key", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        // What the diagnostic report keeps of the failure is its status, never the key.
        assertFalse(String.join("\n", HookStatus.report()).contains("sk-"));
        // A turned-down key is counted, and the family's hooks still read as working.
        assertTrue(String.join("\n", HookStatus.report()).contains("translate key refused"));
        assertTrue(HookStatus.missing(FamilyNames.OUTSIDE_TRANSLATE).isEmpty());
    }

    @Test public void onlyAnUnexpectedFailureReadsAsABrokenHook() {
        OutsideTranslate.fetcher = (target, text) -> { throw new java.io.IOException("no route"); };
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(10);
        Fake offline = new Fake();
        OutsideTranslate.showMessage(offline, offline);
        ShadowLooper.idleMainLooper();
        assertTrue(String.join("\n", HookStatus.report()).contains("translate request failed"));
        assertTrue(HookStatus.missing(FamilyNames.OUTSIDE_TRANSLATE).isEmpty());

        OutsideTranslate.fetcher = (target, text) -> { throw new IllegalStateException("parser bug"); };
        Fake broken = new Fake();
        broken.original = "Другой текст";
        OutsideTranslate.showMessage(broken, broken);
        ShadowLooper.idleMainLooper();
        assertFalse(HookStatus.missing(FamilyNames.OUTSIDE_TRANSLATE).isEmpty());
    }

    /** Telegram doesn't know the chat item's number, so a tap on one left in an open menu stays ours. */
    @Test public void theChatItemIsOursEvenAfterTheSwitchWentOff() {
        assertFalse(Settings.OUTSIDE_TRANSLATE.get());
        assertTrue(OutsideTranslate.headerClick(new Object(), OutsideTranslate.HEADER_ITEM));
        assertFalse(OutsideTranslate.chatOn(0));
        assertFalse(OutsideTranslate.headerClick(new Object(), OutsideTranslate.HEADER_ITEM + 1));
    }

    @Test public void theChatMenuLabelFollowsSavedStateWithoutAddingDuplicates() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        OutsideTranslate.toggleChat(42);
        OutsideTranslate.resetForTests();
        Chat chat = new Chat(42);
        OutsideTranslate.headerMenu(chat);
        OutsideTranslate.headerMenu(chat);
        assertEquals(1, chat.items.size());
        HeaderItem item = chat.items.get(0);
        assertEquals("Stop translating this chat", item.text);
        assertEquals(OutsideTranslate.HEADER_ITEM, item.id);
        OutsideTranslate.toggleChat(42);
        OutsideTranslate.headerMenu(chat);
        assertEquals("Translate this chat", item.text);
        assertEquals(1, chat.items.size());
        assertEquals(0, fetches.get());
    }

    @Test public void clickingTheChatActionUpdatesItsLabelAndRefreshesMessages() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        Chat chat = new Chat(42);
        OutsideTranslate.headerMenu(chat);
        assertEquals("Translate this chat", chat.items.get(0).text);
        assertTrue(OutsideTranslate.headerClick(chat, OutsideTranslate.HEADER_ITEM));
        assertTrue(OutsideTranslate.chatOn(42));
        assertEquals("Stop translating this chat", chat.items.get(0).text);
        assertTrue(OutsideTranslate.headerClick(chat, OutsideTranslate.HEADER_ITEM));
        assertFalse(OutsideTranslate.chatOn(42));
        assertEquals("Translate this chat", chat.items.get(0).text);
        assertEquals(1, chat.items.size());
        assertEquals(2, HeaderHost.refreshed);
        assertEquals(0, fetches.get());
    }

    @Test public void aMissingNativeItemAndADisabledSwitchDoNotAddChatActions() {
        Chat chat = new Chat(42);
        OutsideTranslate.headerMenu(chat);
        assertTrue(chat.items.isEmpty());
        Settings.OUTSIDE_TRANSLATE.save(true);
        chat.hasTranslateItem = false;
        OutsideTranslate.headerMenu(chat);
        assertTrue(chat.items.isEmpty());
        assertEquals(0, fetches.get());
    }

    @Test public void nativeLabelFailureDoesNotInterruptAToggleAndFailedInsertionCanRetry() {
        Settings.OUTSIDE_TRANSLATE.save(true);
        Chat chat = new Chat(42);
        chat.failAdd = true;
        OutsideTranslate.headerMenu(chat);
        assertTrue(chat.items.isEmpty());
        chat.failAdd = false;
        OutsideTranslate.headerMenu(chat);
        assertEquals(1, chat.items.size());
        chat.items.get(0).failText = true;
        assertTrue(OutsideTranslate.headerClick(chat, OutsideTranslate.HEADER_ITEM));
        assertTrue(OutsideTranslate.chatOn(42));
        assertEquals(1, HeaderHost.refreshed);
        chat.items.get(0).failText = false;
        OutsideTranslate.headerMenu(chat);
        assertEquals("Stop translating this chat", chat.items.get(0).text);
        assertEquals(1, chat.items.size());
        assertEquals(0, fetches.get());
    }

    @Test public void rememberingANativeItemCannotKeepItsOwningChatAlive() throws Exception {
        Settings.OUTSIDE_TRANSLATE.save(true);
        Chat chat = new Chat(42);
        OutsideTranslate.headerMenu(chat);
        java.lang.reflect.Field field = OutsideTranslate.class.getDeclaredField("HEADERS");
        field.setAccessible(true);
        Object remembered = ((Map<?, ?>) field.get(null)).get(chat);
        assertTrue("The native item owns its chat and must only be referenced weakly", remembered instanceof WeakReference);
        assertSame(chat, ((HeaderItem) ((WeakReference<?>) remembered).get()).owner);
        ((WeakReference<?>) remembered).clear();
        OutsideTranslate.headerMenu(chat);
        assertTrue(OutsideTranslate.headerClick(chat, OutsideTranslate.HEADER_ITEM));
        assertEquals(1, chat.items.size());
        assertEquals(1, HeaderHost.refreshed);
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

    private static final class Chat {
        final long dialog;
        final List<HeaderItem> items = new ArrayList<>();
        boolean hasTranslateItem = true;
        boolean failAdd;
        Chat(long dialog) { this.dialog = dialog; }
    }

    private static final class HeaderItem {
        final Chat owner;
        final int id;
        String text;
        boolean failText;
        HeaderItem(Chat owner, int id, String text) { this.owner = owner; this.id = id; this.text = text; }
    }

    @Implements(value = OutsideTranslate.class, isInAndroidSdk = false)
    public static class HeaderHost {
        static int refreshed;
        @Implementation protected static boolean hasTranslateItem(Object chat) { return ((Chat) chat).hasTranslateItem; }
        @Implementation protected static long chatDialog(Object chat) { return ((Chat) chat).dialog; }
        @Implementation protected static Object chatOf(Object delegate) { return delegate; }
        @Implementation protected static Object addHeaderItem(Object chat, int id, int icon, String text) {
            Chat owner = (Chat) chat;
            if (owner.failAdd) throw new IllegalStateException("native menu unavailable");
            HeaderItem item = new HeaderItem(owner, id, text);
            owner.items.add(item);
            return item;
        }
        @Implementation protected static void setHeaderText(Object item, String text) {
            HeaderItem nativeItem = (HeaderItem) item;
            if (nativeItem.failText) throw new IllegalStateException("native label unavailable");
            nativeItem.text = text;
        }
        @Implementation protected static void notifyDialog(long dialog) { refreshed++; }
    }
}
