/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * A translator that sits beside Telegram's own, which is Premium for a whole chat and whose
 * Premium checks stay exactly as they are. Nothing here asks Telegram to translate: the text
 * goes to Google's web translate, and the answer is shown by giving the message the same
 * translated-text fields and layout Telegram's own translation fills in.
 *
 * <p>A chat is translated from its header menu, and one message from its long-press menu. Text
 * leaves the phone for the service only for those, one message at a time, and only while the
 * switch is on. Telegram's message class asks here whenever it works out what to show: a message
 * with a translation in the cache shows it, one without gets a background request and shows its
 * original until the answer lands. A message that's yours, a service message, code, a message
 * Telegram's own translation already changed or text already in the app's language is left
 * alone. Switch off, paused or any failure gives Telegram's own path back.
 *
 * <p>Which chats are on is kept on the phone and stays out of the settings file. Translations are
 * kept in memory only, never in Telegram's database.
 */
public final class OutsideTranslate {
    private OutsideTranslate() {}

    /** Option numbers past Telegram's own, and past the message menu family's. */
    static final int MENU_OPTION = 0x48545405;
    static final int HEADER_ITEM = 0x48545406;
    /** Telegram's own Translate in the message menu, which ours goes after. */
    static final int TELEGRAM_TRANSLATE = 29;

    private static final int CACHE_SIZE = 400;
    private static final long RETRY_MS = 60_000L;
    private static final String PREFS = "hushtelegram_outside_translate";
    private static final String CHATS = "chats";
    /** Plain text and the kinds of message that carry a caption. */
    private static final int[] TYPES = {0, 1, 3, 9, 14};

    /** What the extension needs to know about a message, and to do to it. Telegram's own class stands behind it. */
    interface Message {
        long dialog();

        int id();

        boolean eligible();

        String original();

        boolean translated();

        void apply(String text, String language);

        void refresh();
    }

    /** A message waiting on a request. It's held only until the answer lands or fails. */
    private static final class Waiter {
        final Message message;

        Waiter(Message message) {
            this.message = message;
        }
    }

    private static Message of(Object message) {
        return new Message() {
            @Override public long dialog() { return OutsideTranslate.dialog(message); }
            @Override public int id() { return OutsideTranslate.id(message); }
            @Override public boolean eligible() { return OutsideTranslate.eligible(message); }
            @Override public String original() { return OutsideTranslate.original(message); }
            @Override public boolean translated() { return isTranslated(message); }
            @Override public void apply(String text, String language) { applyTranslated(message, text, language); }
            @Override public void refresh() { notifyTranslated(message); }
        };
    }

    interface Fetcher {
        GoogleTranslate.Result fetch(String target, String text) throws Exception;
    }

    private static final Object LOCK = new Object();
    private static final Map<String, GoogleTranslate.Result> CACHE = new LinkedHashMap<String, GoogleTranslate.Result>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, GoogleTranslate.Result> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private static final Map<String, List<Waiter>> PENDING = new HashMap<>();
    private static final Map<String, Long> FAILED = new HashMap<>();
    private static final Set<String> MESSAGES_ON = new HashSet<>();
    private static final Set<String> MESSAGES_OFF = new HashSet<>();
    private static final Set<Long> TOASTED = new HashSet<>();
    private static final Map<Object, String> APPLIED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> HEADERS = Collections.synchronizedMap(new WeakHashMap<>());
    private static Set<Long> chats;
    private static ExecutorService pool;

    static Fetcher fetcher = GoogleTranslate::fetch;
    static Executor executor = task -> {
        synchronized (LOCK) {
            if (pool == null) pool = Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "hushtelegram-translate");
                thread.setDaemon(true);
                return thread;
            });
        }
        pool.execute(task);
    };

    // The message class asks.

    /**
     * Asked first whenever a message works out what to show.
     *
     * @param message the message
     * @return true when the message now shows a translation, so Telegram's own check is skipped
     */
    public static boolean show(Object message) {
        try {
            return message != null && showMessage(message, of(message));
        } catch (Throwable failure) {
            APPLIED.remove(message);
            HookStatus.threw(FamilyNames.OUTSIDE_TRANSLATE, "translated message", failure);
            return false;
        }
    }

    /** [show] for any message the extension can read, keyed by Telegram's own object. */
    static boolean showMessage(Object key, Message message) {
        try {
            if (!active()) {
                APPLIED.remove(key);
                return false;
            }
            long dialog = message.dialog();
            int id = message.id();
            if (!wanted(dialog, id)) {
                APPLIED.remove(key);
                return false;
            }
            // Telegram's own translation is showing, so it stays.
            if (message.translated() && !APPLIED.containsKey(key)) return false;
            if (!message.eligible()) return false;
            String original = message.original();
            String target = target();
            GoogleTranslate.Result result = cached(target, original);
            if (result == null) {
                request(message, target, original);
                APPLIED.remove(key);
                return false;
            }
            if (result.sameLanguageAs(target)) {
                APPLIED.remove(key);
                return false;
            }
            String shown = result.text + "\n\n" + L10n.t("(Translated. Long-press for the original.)");
            if (shown.equals(APPLIED.get(key)) && message.translated()) return true;
            message.apply(shown, target);
            APPLIED.put(key, shown);
            HookStatus.counted(FamilyNames.OUTSIDE_TRANSLATE, "message translated");
            return true;
        } catch (Throwable failure) {
            APPLIED.remove(key);
            HookStatus.threw(FamilyNames.OUTSIDE_TRANSLATE, "translated message", failure);
            return false;
        }
    }

    /** Whether the switch is on and HushTelegram isn't paused. */
    static boolean active() {
        HookStatus.invoked(FamilyNames.OUTSIDE_TRANSLATE);
        try {
            if (!Utils.settingsReady()) return false;
            if (Settings.OUTSIDE_TRANSLATE.get()) return true;
            // Switched off, not just paused: a later switch-on starts with nothing chosen.
            if (!Settings.OUTSIDE_TRANSLATE.savedValue()) forgetChats();
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.OUTSIDE_TRANSLATE, "switch", failure);
            return false;
        }
    }

    /** Whether this message is one the person asked to see translated, by chat or by itself. */
    static boolean wanted(long dialog, int id) {
        String key = key(dialog, id);
        synchronized (LOCK) {
            if (MESSAGES_OFF.contains(key)) return false;
            return MESSAGES_ON.contains(key) || chatSet().contains(dialog);
        }
    }

    /** Whether the message is one Telegram itself would translate, and plain enough for this. */
    static boolean eligible(Object message) {
        if (!translatable(message)) return false;
        int type = messageType(message);
        boolean known = false;
        for (int allowed : TYPES) known |= allowed == type;
        if (!known) return false;
        String text = original(message);
        if (text == null || text.trim().isEmpty()) return false;
        return !hasCode(entities(message));
    }

    /** A code block or a run of code stays as written. */
    static boolean hasCode(List<?> entities) {
        if (entities == null) return false;
        for (Object entity : entities) {
            if (entity == null) continue;
            String name = entity.getClass().getName();
            if (name.endsWith("TL_messageEntityPre") || name.endsWith("TL_messageEntityCode")) return true;
        }
        return false;
    }

    // Requests and the cache.

    static String key(long dialog, int id) {
        return dialog + ":" + id;
    }

    static String cacheKey(String target, String text) {
        return target + "\u0000" + text;
    }

    static GoogleTranslate.Result cached(String target, String text) {
        synchronized (LOCK) {
            return CACHE.get(cacheKey(target, text));
        }
    }

    /** Asks the service for the text in the background, once however many messages want it. */
    static void request(Message message, String target, String text) {
        String key = cacheKey(target, text);
        synchronized (LOCK) {
            Long failed = FAILED.get(key);
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return;
            List<Waiter> waiters = PENDING.get(key);
            if (waiters != null) {
                waiters.add(new Waiter(message));
                return;
            }
            waiters = new ArrayList<>();
            waiters.add(new Waiter(message));
            PENDING.put(key, waiters);
        }
        executor.execute(() -> {
            GoogleTranslate.Result result = null;
            Throwable failure = null;
            try {
                result = fetcher.fetch(target, text);
            } catch (Throwable thrown) {
                failure = thrown;
            }
            finish(key, result, failure);
        });
    }

    private static void finish(String key, GoogleTranslate.Result result, Throwable failure) {
        List<Waiter> waiters;
        synchronized (LOCK) {
            waiters = PENDING.remove(key);
            if (result != null) {
                CACHE.put(key, result);
                FAILED.remove(key);
            } else {
                FAILED.put(key, System.currentTimeMillis());
            }
        }
        if (waiters == null) return;
        final List<Waiter> done = waiters;
        final boolean failed = result == null;
        if (failed) HookStatus.threw(FamilyNames.OUTSIDE_TRANSLATE, "translate request", failure);
        Utils.runOnMainThread(() -> {
            try {
                // Switched off or paused while the request was out: nothing to show or say.
                if (!active()) return;
                for (Waiter waiter : done) {
                    long dialog = waiter.message.dialog();
                    if (failed) {
                        toastOnce(dialog);
                        continue;
                    }
                    if (!wanted(dialog, waiter.message.id())) continue;
                    synchronized (LOCK) {
                        TOASTED.remove(dialog);
                    }
                    waiter.message.refresh();
                }
            } catch (Throwable thrown) {
                HookStatus.threw(FamilyNames.OUTSIDE_TRANSLATE, "translated message refresh", thrown);
            }
        });
    }

    private static void toastOnce(long dialog) {
        synchronized (LOCK) {
            if (!TOASTED.add(dialog)) return;
        }
        Utils.showToastShort(L10n.t("Translation isn't available right now"));
    }

    // The language the translation is written in.

    static String target() {
        Locale locale = null;
        try {
            locale = appLocale();
        } catch (Throwable ignored) {
            // Telegram's own language isn't readable, so the phone's is used.
        }
        return tagOf(locale != null ? locale : Locale.getDefault());
    }

    /** Google's code for a locale: Java still says "in" and "iw", and Chinese needs its script. */
    static String tagOf(Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) return "en";
        String language = locale.getLanguage();
        if (language.equals("in")) return "id";
        if (language.equals("iw")) return "he";
        if (language.equals("zh")) {
            String country = locale.getCountry();
            return country.equals("TW") || country.equals("HK") || country.equals("MO") ? "zh-TW" : "zh-CN";
        }
        return language;
    }

    // The chats that are on.

    private static Set<Long> chatSet() {
        synchronized (LOCK) {
            if (chats == null) {
                chats = new HashSet<>();
                try {
                    for (String id : prefs().getStringSet(CHATS, Collections.emptySet())) chats.add(Long.parseLong(id));
                } catch (Throwable unreadable) {
                    chats.clear();
                }
            }
            return chats;
        }
    }

    private static SharedPreferences prefs() {
        Context context = Utils.getContext();
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Turns a chat on or off and remembers it. Returns whether it's on now. */
    static boolean toggleChat(long dialog) {
        boolean on;
        synchronized (LOCK) {
            Set<Long> set = chatSet();
            on = set.add(dialog);
            if (!on) set.remove(dialog);
            String prefix = dialog + ":";
            // A new choice for the whole chat replaces the ones made message by message.
            MESSAGES_ON.removeIf(key -> key.startsWith(prefix));
            MESSAGES_OFF.removeIf(key -> key.startsWith(prefix));
            TOASTED.remove(dialog);
            Set<String> saved = new HashSet<>();
            for (Long id : set) saved.add(Long.toString(id));
            try {
                prefs().edit().putStringSet(CHATS, saved).apply();
            } catch (Throwable unsaved) {
                HookStatus.threw(FamilyNames.OUTSIDE_TRANSLATE, "chat list", unsaved);
            }
        }
        return on;
    }

    /**
     * Forgets every chat and message turned on, saved list included, so nothing is sent again
     * until the person chooses again. Run when the switch goes off.
     */
    public static void forgetChats() {
        synchronized (LOCK) {
            boolean saved = chats == null || !chats.isEmpty();
            if (!saved && MESSAGES_ON.isEmpty() && MESSAGES_OFF.isEmpty() && TOASTED.isEmpty()) return;
            chats = new HashSet<>();
            MESSAGES_ON.clear();
            MESSAGES_OFF.clear();
            TOASTED.clear();
            if (!saved) return;
            try {
                prefs().edit().remove(CHATS).apply();
            } catch (Throwable unsaved) {
                HookStatus.threw(FamilyNames.OUTSIDE_TRANSLATE, "chat list", unsaved);
            }
        }
    }

    static boolean chatOn(long dialog) {
        synchronized (LOCK) {
            return chatSet().contains(dialog);
        }
    }

    // The message menu.

    /**
     * Asked as Telegram finishes the menu's lists. Translate here goes right after Telegram's own
     * Translate, or last when it has none.
     *
     * @param chat the chat screen
     * @param primary the message the menu opened on, unused: the chat's selected message is what a choice acts on
     */
    public static void fill(Object chat, Object primary, ArrayList<Object> icons, ArrayList<Object> items, ArrayList<Object> options) {
        if (icons == null || items == null || options == null) return;
        if (!active()) return;
        try {
            Object message = selected(chat);
            if (message == null || !eligible(message)) return;
            offer(icons, items, options, APPLIED.containsKey(message));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.OUTSIDE_TRANSLATE, "message menu", failure);
        }
    }

    /** The three lists stay in step, so a menu whose lists already differ is left alone. */
    static void offer(List<Object> icons, List<Object> items, List<Object> options, boolean showing) {
        if (options.isEmpty() || icons.size() != items.size() || items.size() != options.size()) return;
        int at = options.indexOf(TELEGRAM_TRANSLATE);
        at = at >= 0 ? at + 1 : options.size();
        int icon = translateIcon();
        String label = showing ? L10n.t("Show original") : L10n.t("Translate here");
        icons.add(at, icon);
        items.add(at, label);
        options.add(at, MENU_OPTION);
    }

    /** Asked first when an option is chosen. Telegram's own numbers pass by untouched. */
    public static void chosen(Object chat, int option) {
        if (option != MENU_OPTION) return;
        try {
            if (!active()) return;
            Object message = selected(chat);
            if (message == null) return;
            boolean translate = !APPLIED.containsKey(message);
            String key = key(dialog(message), id(message));
            synchronized (LOCK) {
                if (translate) {
                    MESSAGES_OFF.remove(key);
                    MESSAGES_ON.add(key);
                    FAILED.clear();
                } else {
                    MESSAGES_ON.remove(key);
                    MESSAGES_OFF.add(key);
                }
            }
            // After the menu has closed, the way Telegram's own choices finish.
            Utils.runOnMainThread(() -> {
                try {
                    notifyTranslated(message);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.OUTSIDE_TRANSLATE, "message refresh", failure);
                }
            });
            HookStatus.counted(FamilyNames.OUTSIDE_TRANSLATE, translate ? "message translate chosen" : "message original chosen");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.OUTSIDE_TRANSLATE, "message menu choice", failure);
        }
    }

    // The chat's header menu.

    /** Asked whenever the chat updates its own Translate item, which exists when the chat has a header menu. */
    public static void headerMenu(Object chat) {
        try {
            if (chat == null || !active() || !hasTranslateItem(chat) || HEADERS.containsKey(chat)) return;
            HEADERS.put(chat, Boolean.TRUE);
            addHeaderItem(chat, HEADER_ITEM, translateIcon(), L10n.t("Translate this chat"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.OUTSIDE_TRANSLATE, "header menu", failure);
        }
    }

    /**
     * Asked first when a header item is chosen.
     *
     * @return true when the choice was ours, so Telegram's own handling is skipped
     */
    public static boolean headerClick(Object delegate, int id) {
        if (id != HEADER_ITEM) return false;
        try {
            // An item left in an open menu after the switch went off is still ours, and does nothing.
            if (!active()) return true;
            Object chat = chatOf(delegate);
            if (chat == null) return false;
            long dialog = chatDialog(chat);
            boolean on = toggleChat(dialog);
            Utils.showToastShort(on ? L10n.t("This chat will be translated") : L10n.t("This chat shows the original messages"));
            notifyDialog(dialog);
            HookStatus.counted(FamilyNames.OUTSIDE_TRANSLATE, on ? "chat translate on" : "chat translate off");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.OUTSIDE_TRANSLATE, "header menu choice", failure);
            return false;
        }
    }

    // Replaced when patching.

    /** The chat's selected message. */
    public static Object selected(Object chat) { return null; }

    /** The message's text as sent. */
    public static String original(Object message) { return null; }

    /** Telegram's own check that a message has translatable content. */
    public static boolean translatable(Object message) { return false; }

    /** The message's Telegram type. */
    public static int messageType(Object message) { return -1; }

    public static long dialog(Object message) { return 0L; }

    public static int id(Object message) { return 0; }

    /** The message's entities. */
    public static ArrayList<?> entities(Object message) { return null; }

    /** Whether the message is showing a translation, ours or Telegram's. */
    public static boolean isTranslated(Object message) { return false; }

    /** Shows the text as the message's translation into the language. */
    public static void applyTranslated(Object message, String text, String language) {}

    /** Tells the chat on screen the message's translation changed, so it draws the message again. */
    public static void notifyTranslated(Object message) {}

    /** Tells the chat on screen to look at every message's translation again. */
    public static void notifyDialog(long dialog) {}

    /** Telegram's icon for Translate. */
    public static int translateIcon() { return 0; }

    /** The app's language. */
    public static Locale appLocale() { return null; }

    /** Whether the chat built its own Translate item. */
    public static boolean hasTranslateItem(Object chat) { return false; }

    /** Adds an item to the chat's header menu. */
    public static void addHeaderItem(Object chat, int id, int icon, String text) {}

    /** The chat screen a header menu delegate belongs to. */
    public static Object chatOf(Object delegate) { return null; }

    /** The chat's ID. */
    public static long chatDialog(Object chat) { return 0L; }

    /** Clears what's remembered, for tests. */
    static void resetForTests() {
        synchronized (LOCK) {
            CACHE.clear();
            PENDING.clear();
            FAILED.clear();
            MESSAGES_ON.clear();
            MESSAGES_OFF.clear();
            TOASTED.clear();
            chats = null;
        }
        APPLIED.clear();
        HEADERS.clear();
    }
}
