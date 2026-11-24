/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Keep more favorite stickers (#110): what addRecentSticker drops from a full favorites list stays
 * on this phone, shows after Telegram's favorites, and goes when it's removed or faved again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class KeptFavoritesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long ME = 777L;
    private static final long OTHER = 888L;
    private static final Object CONTROLLER = new Object();
    private static final Object OTHER_CONTROLLER = new Object();
    private static final int RECENT = 0;
    private static final Object FAV = "fav";

    private final Telegram telegram = new Telegram();
    private Function<Context, SharedPreferences> stockStore;
    private KeptFavorites.Telegram stockTelegram;

    @Before public void reset() {
        stockStore = KeptFavorites.store;
        stockTelegram = KeptFavorites.telegram;
        KeptFavorites.telegram = telegram;
        KeptFavorites.dropped(null);
        KeptFavorites.forget();
        prefs().edit().clear().commit();
        restore();
    }

    @After public void restore() {
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.KEEP_MORE_FAVORITES);
        Settings.KEEP_MORE_FAVORITES.resetToDefault();
        HookStatus.clear();
    }

    @After public void putTelegramBack() {
        KeptFavorites.forget();
        KeptFavorites.store = stockStore;
        KeptFavorites.telegram = stockTelegram;
    }

    @Test public void startsOffSoTelegramDropsItsOldestFavoriteAsBefore() {
        assertFalse(Settings.KEEP_MORE_FAVORITES.get());
        Doc old = telegram.fave(1, 2, 3, 4, 5).get(4);
        add(doc(6), old);
        assertTrue(prefs().getAll().isEmpty());
        assertEquals(ids(1, 2, 3, 4, 5), listed(CONTROLLER));
        assertFalse(KeptFavorites.favorite(CONTROLLER, old, 0));
        assertTrue(KeptFavorites.favorite(CONTROLLER, old, 1));
        assertSame(FAV, KeptFavorites.referenceParent(CONTROLLER, new Object[] {old.id}, FAV));
        assertFalse(report().contains(KeptFavorites.KEPT));
    }

    @Test public void turnedOnTheDroppedFavoriteStaysAndShowsAfterTelegrams() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        telegram.fave(1, 2, 3, 4, 5);
        add(doc(6), doc(5));
        telegram.fave(6, 1, 2, 3, 4);
        add(doc(7), doc(4));
        telegram.fave(7, 6, 1, 2, 3);
        assertEquals("the newest kept one first", ids(7, 6, 1, 2, 3, 4, 5), listed(CONTROLLER));
        assertTrue(KeptFavorites.favorite(CONTROLLER, doc(5), 0));
        assertFalse(KeptFavorites.favorite(CONTROLLER, doc(8), 0));
        assertFalse(KeptFavorites.favorite(CONTROLLER, null, 0));
        assertFalse(prefs().getString(String.valueOf(ME), "").isEmpty());
        String report = report();
        assertTrue(report, report.contains(KeptFavorites.KEPT));
        assertTrue(report, report.contains(KeptFavorites.SHOWN));
    }

    @Test public void onlyAStickerDroppedWhileAFavoriteIsAddedIsKept() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        telegram.fave(1, 2, 3, 4, 5);
        // Recent stickers drop their oldest too, and stay Telegram's.
        KeptFavorites.faving(CONTROLLER, RECENT, null, doc(9), 0, 0);
        KeptFavorites.dropped(doc(8));
        // A removal takes the sticker it was given, not the list's last.
        KeptFavorites.faving(CONTROLLER, KeptFavorites.FAVORITES, FAV, doc(5), 0, 1);
        KeptFavorites.dropped(doc(5));
        // Nothing being added on this thread.
        KeptFavorites.dropped(doc(4));
        // One addition, one drop.
        add(doc(6), doc(3));
        KeptFavorites.dropped(doc(2));
        telegram.fave(6, 1, 2, 4, 5);
        assertEquals(ids(6, 1, 2, 4, 5, 3), listed(CONTROLLER));
    }

    @Test public void removingAKeptFavoriteRemovesItFromThisPhoneOnly() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        telegram.fave(1, 2, 3, 4, 5);
        add(doc(6), doc(5));
        add(doc(7), doc(4));
        telegram.fave(7, 6, 1, 2, 3);
        KeptFavorites.faving(CONTROLLER, KeptFavorites.FAVORITES, FAV, doc(4), 0, 1);
        assertEquals(ids(7, 6, 1, 2, 3, 5), listed(CONTROLLER));
        assertFalse(KeptFavorites.favorite(CONTROLLER, doc(4), 0));
        KeptFavorites.faving(CONTROLLER, KeptFavorites.FAVORITES, FAV, doc(5), 0, 1);
        assertEquals(ids(7, 6, 1, 2, 3), listed(CONTROLLER));
        assertFalse("the last one leaves nothing stored", prefs().contains(String.valueOf(ME)));
    }

    @Test public void aKeptFavoriteFavedAgainIsTelegramsAndNeverShowsTwice() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        telegram.fave(1, 2, 3, 4, 5);
        add(doc(6), doc(5));
        add(doc(7), doc(4));
        telegram.fave(7, 6, 1, 2, 3);
        // Faved again here: it's Telegram's, and Telegram drops its own oldest.
        add(doc(5), doc(3));
        telegram.fave(5, 7, 6, 1, 2);
        assertEquals(ids(5, 7, 6, 1, 2, 3, 4), listed(CONTROLLER));
        // Faved again on another device: Telegram's list has it, so it stops being kept.
        telegram.fave(4, 5, 7, 6, 1);
        assertEquals(ids(4, 5, 7, 6, 1, 3), listed(CONTROLLER));
        KeptFavorites.forget();
        telegram.fave(9, 8, 7, 6, 5);
        assertEquals("the store forgot it too", ids(9, 8, 7, 6, 5, 3), listed(CONTROLLER));
    }

    @Test public void anAccountKeepsAtMostTwoHundredNewestFirst() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        telegram.fave(1000);
        for (int id = 1; id <= KeptFavorites.MAX + 5; id++) add(doc(2000 + id), doc(id));
        List<Long> shown = listed(CONTROLLER);
        assertEquals(KeptFavorites.MAX + 1, shown.size());
        assertEquals(Long.valueOf(1000), shown.get(0));
        assertEquals(Long.valueOf(KeptFavorites.MAX + 5), shown.get(1));
        assertEquals(Long.valueOf(6), shown.get(KeptFavorites.MAX));
        assertFalse(KeptFavorites.favorite(CONTROLLER, doc(5), 0));
    }

    @Test public void eachAccountKeepsItsOwnByUserId() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        telegram.fave(1, 2, 3, 4, 5);
        add(doc(6), doc(5));
        telegram.favorites.put(OTHER_CONTROLLER, new ArrayList<>(Arrays.asList(doc(1), doc(2))));
        assertEquals(ids(1, 2), listed(OTHER_CONTROLLER));
        assertFalse(KeptFavorites.favorite(OTHER_CONTROLLER, doc(5), 0));
        assertFalse(prefs().contains(String.valueOf(OTHER)));
        // An account Telegram can't name keeps nothing.
        Object nobody = new Object();
        telegram.favorites.put(nobody, new ArrayList<>(Collections.singletonList(doc(1))));
        KeptFavorites.faving(nobody, KeptFavorites.FAVORITES, FAV, doc(7), 0, 0);
        KeptFavorites.dropped(doc(1));
        assertEquals(ids(1), listed(nobody));
    }

    @Test public void keptFavoritesComeBackAfterARestart() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        telegram.fave(1, 2, 3, 4, 5);
        add(doc(6), doc(5));
        add(doc(7), doc(4));
        KeptFavorites.forget();
        telegram.fave(7, 6, 1, 2, 3);
        assertEquals(ids(7, 6, 1, 2, 3, 4, 5), listed(CONTROLLER));
        assertTrue(KeptFavorites.favorite(CONTROLLER, doc(4), 0));
        assertEquals("read back as the documents Telegram wrote", Arrays.asList(4L, 5L), telegram.read);
    }

    /** Android's Base64 skips characters it doesn't know, so garbage reads back as no document. */
    @Test public void anUnreadableEntryIsLeftOutAndTheRestStay() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        String good = Base64.encodeToString(telegram.serialized(doc(4)), Base64.NO_WRAP);
        String unknown = Base64.encodeToString("other:1".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        prefs().edit().putString(String.valueOf(ME), "%%%," + unknown + "," + good + "," + good).commit();
        telegram.fave(1);
        assertEquals(ids(1, 4), listed(CONTROLLER));
        assertTrue(report(), report().contains(KeptFavorites.UNREADABLE));
    }

    @Test public void anEntryTelegramCantReadIsLeftOutAndTheRestStay() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        String good = Base64.encodeToString(telegram.serialized(doc(4)), Base64.NO_WRAP);
        String broken = Base64.encodeToString("bad:3".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        prefs().edit().putString(String.valueOf(ME), broken + "," + good).commit();
        telegram.fave(1);
        assertEquals(ids(1, 4), listed(CONTROLLER));
        assertTrue(report(), report().contains(KeptFavorites.UNREADABLE));
    }

    @Test public void listsOtherThanTheFavoritesKeepTelegramsStickers() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        telegram.fave(1, 2, 3, 4, 5);
        add(doc(6), doc(5));
        List<Doc> recent = new ArrayList<>(Arrays.asList(doc(8), doc(9)));
        List<Object> copy = new ArrayList<>(recent);
        KeptFavorites.listed(CONTROLLER, recent, copy);
        assertEquals(new ArrayList<Object>(recent), copy);
        KeptFavorites.listed(CONTROLLER, null, copy);
        KeptFavorites.listed(null, recent, copy);
        assertEquals(2, copy.size());
    }

    @Test public void anExpiredKeptStickerIsRefreshedThroughItsPack() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        telegram.fave(1, 2, 3, 4, 5);
        add(doc(6), doc(5));
        add(doc(7), doc(1004));
        assertEquals("pack 5", KeptFavorites.referenceParent(CONTROLLER, new Object[] {5L}, FAV));
        assertTrue(report().contains(KeptFavorites.REFRESHED));
        // A kept sticker in no pack, a sticker that isn't kept, and anything that isn't a favorite go Telegram's way.
        assertSame(FAV, KeptFavorites.referenceParent(CONTROLLER, new Object[] {1004L}, FAV));
        assertSame(FAV, KeptFavorites.referenceParent(CONTROLLER, new Object[] {3L}, FAV));
        assertSame(FAV, KeptFavorites.referenceParent(CONTROLLER, new Object[] {"photo"}, FAV));
        Object recent = "recent";
        assertSame(recent, KeptFavorites.referenceParent(CONTROLLER, new Object[] {5L}, recent));
        Object message = new Object();
        assertSame(message, KeptFavorites.referenceParent(CONTROLLER, new Object[] {5L}, message));
        assertNull(KeptFavorites.referenceParent(CONTROLLER, new Object[] {5L}, null));
        assertSame(FAV, KeptFavorites.referenceParent(OTHER_CONTROLLER, new Object[] {5L}, FAV));
    }

    @Test public void offOrPausedTheKeptOnesWaitForTheSwitch() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        telegram.fave(1, 2, 3, 4, 5);
        add(doc(6), doc(5));
        telegram.fave(6, 1, 2, 3, 4);
        Settings.KEEP_MORE_FAVORITES.save(false);
        assertEquals(ids(6, 1, 2, 3, 4), listed(CONTROLLER));
        add(doc(7), doc(4));
        KeptFavorites.faving(CONTROLLER, KeptFavorites.FAVORITES, FAV, doc(5), 0, 1);
        assertFalse(KeptFavorites.favorite(CONTROLLER, doc(5), 0));
        assertSame(FAV, KeptFavorites.referenceParent(CONTROLLER, new Object[] {5L}, FAV));
        Settings.KEEP_MORE_FAVORITES.save(true);
        assertEquals("nothing changed while it was off", ids(6, 1, 2, 3, 4, 5), listed(CONTROLLER));
    }

    @Test public void pausingAnEarlyStartOrAnUnreadableSwitchKeepsTelegramsWay() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        telegram.fave(1, 2, 3, 4, 5);
        add(doc(6), doc(5));
        telegram.fave(6, 1, 2, 3, 4);
        String stored = prefs().getString(String.valueOf(ME), "");
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertEquals(reason.name(), ids(6, 1, 2, 3, 4), listed(CONTROLLER));
            add(doc(7), doc(4));
            assertFalse(reason.name(), KeptFavorites.favorite(CONTROLLER, doc(5), 0));
            assertSame(reason.name(), FAV, KeptFavorites.referenceParent(CONTROLLER, new Object[] {5L}, FAV));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertEquals(ids(6, 1, 2, 3, 4), listed(CONTROLLER));
            add(doc(7), doc(4));
            assertFalse(KeptFavorites.favorite(CONTROLLER, doc(5), 0));
        });
        SettingReadsForTests.breakReads(Settings.KEEP_MORE_FAVORITES);
        assertEquals(ids(6, 1, 2, 3, 4), listed(CONTROLLER));
        assertFalse(KeptFavorites.favorite(CONTROLLER, doc(5), 0));
        assertFalse(HookStatus.missing(FamilyNames.CHANGE_STICKER_SIZE).isEmpty());
        assertEquals(stored, prefs().getString(String.valueOf(ME), ""));
    }

    @Test public void aFailingTelegramCallLeavesTheFavoritesToTelegram() {
        Settings.KEEP_MORE_FAVORITES.save(true);
        telegram.fave(1, 2, 3, 4, 5);
        add(doc(6), doc(5));
        telegram.fave(6, 1, 2, 3, 4);
        telegram.broken = true;
        add(doc(7), doc(4));
        assertEquals(ids(6, 1, 2, 3, 4), listed(CONTROLLER));
        assertFalse(KeptFavorites.favorite(CONTROLLER, doc(5), 0));
        assertSame(FAV, KeptFavorites.referenceParent(CONTROLLER, new Object[] {5L}, FAV));
        assertTrue(report().contains("kept favorite stickers"));
        telegram.broken = false;
        assertEquals("nothing was lost or added", ids(6, 1, 2, 3, 4, 5), listed(CONTROLLER));
    }

    @Test public void theStubsDoNothingUntilThePatchFillsThem() {
        assertEquals(0, KeptFavorites.account(CONTROLLER));
        assertNull(KeptFavorites.favoritesOf(CONTROLLER));
        assertEquals(0, KeptFavorites.documentId(doc(1)));
        assertNull(KeptFavorites.serialized(doc(1)));
        assertNull(KeptFavorites.deserialized(new byte[] {1}));
    }

    @Test public void aRequestNamesItsDocumentByLocationRequestOrMedia() throws Exception {
        assertEquals(41L, KeptFavorites.askedFor(new Object[] {new FileLocation(41L), "operation"}));
        assertEquals(42L, KeptFavorites.askedFor(new Object[] {new FaveSticker(42L)}));
        assertEquals(43L, KeptFavorites.askedFor(new Object[] {new SendMedia(43L)}));
        assertEquals(0L, KeptFavorites.askedFor(new Object[] {new Object()}));
        assertEquals(0L, KeptFavorites.askedFor(new Object[] {new GetMessages()}));
        assertEquals(0L, KeptFavorites.askedFor(new Object[] {null}));
        assertEquals(0L, KeptFavorites.askedFor(new Object[0]));
        assertEquals(0L, KeptFavorites.askedFor("not an array"));
    }

    @Test public void aStickersPackIsItsFirstAttributeNamingOne() throws Exception {
        Object pack = new InputStickerSetID();
        assertSame(pack, KeptFavorites.packOf(new Document(new Attribute(null), null, new Attribute(pack))));
        assertNull(KeptFavorites.packOf(new Document(new Attribute(new TL_inputStickerSetEmpty()))));
        assertNull(KeptFavorites.packOf(new Document()));
        assertNull(KeptFavorites.packOf(new NoAttributes()));
    }

    /** Adds [added] to the favorites, which drops [dropped] off Telegram's full list. */
    private static void add(Doc added, Doc dropped) {
        KeptFavorites.faving(CONTROLLER, KeptFavorites.FAVORITES, FAV, added, 0, 0);
        KeptFavorites.dropped(dropped);
    }

    /** What getRecentStickers hands out for [controller]'s favorites, as ids. */
    private List<Long> listed(Object controller) {
        List<Doc> source = telegram.favorites.get(controller);
        List<Object> copy = new ArrayList<>(source);
        KeptFavorites.listed(controller, source, copy);
        List<Long> ids = new ArrayList<>();
        for (Object document : copy) ids.add(((Doc) document).id);
        return ids;
    }

    private static List<Long> ids(long... ids) {
        List<Long> list = new ArrayList<>();
        for (long id : ids) list.add(id);
        return list;
    }

    private static Doc doc(long id) {
        return new Doc(id);
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    private static SharedPreferences prefs() {
        return KeptFavorites.store.apply(Utils.getContext());
    }

    /** A sticker document: a fresh object each time, the same id. */
    private static final class Doc {
        final long id;

        Doc(long id) { this.id = id; }
    }

    private static final class Telegram implements KeptFavorites.Telegram {
        final Map<Object, List<Doc>> favorites = new HashMap<>();
        final List<Long> read = new ArrayList<>();
        boolean broken;

        List<Doc> fave(long... ids) {
            List<Doc> list = new ArrayList<>();
            for (long id : ids) list.add(doc(id));
            favorites.put(CONTROLLER, list);
            return list;
        }

        @Override public long account(Object controller) {
            if (broken) throw new IllegalStateException("no UserConfig");
            if (controller == CONTROLLER) return ME;
            return controller == OTHER_CONTROLLER ? OTHER : 0;
        }

        @Override public Object favoritesOf(Object controller) { return favorites.get(controller); }

        @Override public long documentId(Object document) { return ((Doc) document).id; }

        @Override public byte[] serialized(Object document) {
            return ("doc:" + ((Doc) document).id).getBytes(StandardCharsets.UTF_8);
        }

        @Override public Object deserialized(byte[] data) {
            String text = new String(data, StandardCharsets.UTF_8);
            if (text.startsWith("bad:")) throw new IllegalStateException("a constructor this build doesn't know");
            if (!text.startsWith("doc:")) return null;
            long id = Long.parseLong(text.substring(4));
            read.add(id);
            return doc(id);
        }

        @Override public Object stickerSet(Object document) {
            long id = ((Doc) document).id;
            return id < 1000 ? "pack " + id : null;
        }

        @Override public long requestedId(Object args) {
            Object first = ((Object[]) args)[0];
            return first instanceof Long ? (Long) first : 0;
        }
    }

    // Stand-ins with the public fields of Telegram's TL classes the reflection reads.

    public static final class FileLocation {
        public long id;

        FileLocation(long id) { this.id = id; }
    }

    public static final class InputDocument {
        public long id;

        InputDocument(long id) { this.id = id; }
    }

    public static final class FaveSticker {
        public InputDocument id;

        FaveSticker(long id) { this.id = new InputDocument(id); }
    }

    public static final class InputMediaDocument {
        public InputDocument id;

        InputMediaDocument(long id) { this.id = new InputDocument(id); }
    }

    public static final class SendMedia {
        public InputMediaDocument media;
        public long random_id = 99L;

        SendMedia(long id) { media = new InputMediaDocument(id); }
    }

    public static final class GetMessages {
        public ArrayList<Integer> id = new ArrayList<>(Collections.singletonList(5));
    }

    public static final class Document {
        public ArrayList<Attribute> attributes = new ArrayList<>();

        Document(Attribute... attributes) { this.attributes.addAll(Arrays.asList(attributes)); }
    }

    public static final class NoAttributes {
        public long id = 1L;
    }

    public static final class Attribute {
        public Object stickerset;

        Attribute(Object stickerset) { this.stickerset = stickerset; }
    }

    public static final class InputStickerSetID {}

    public static final class TL_inputStickerSetEmpty {}
}
