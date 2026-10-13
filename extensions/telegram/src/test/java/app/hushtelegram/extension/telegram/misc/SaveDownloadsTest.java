/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.HushTelegramPause;
import app.hushtelegram.extension.shared.settings.PauseForTests;
import app.hushtelegram.extension.shared.settings.SettingReadsForTests;
import app.hushtelegram.extension.telegram.misc.SaveDownloadsForTests.Document;
import app.hushtelegram.extension.telegram.misc.SaveDownloadsForTests.Message;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SaveDownloadsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder elsewhere = new TemporaryFolder();

    private final SaveDownloads.Telegram realTelegram = SaveDownloads.telegram;
    private final Executor realWork = SaveDownloads.work;
    private Downloads downloads;

    @Before public void setUp() {
        restore();
        SaveDownloads.telegram = SaveDownloadsForTests.TELEGRAM;
        SaveDownloads.work = Runnable::run;
        downloads = Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
    }

    @After public void restore() {
        SaveDownloads.telegram = realTelegram;
        SaveDownloads.work = realWork;
        PauseForTests.resume();
        SettingReadsForTests.mend(Settings.SAVE_DOWNLOADS);
        Settings.SAVE_DOWNLOADS.resetToDefault();
        HookStatus.clear();
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    /** A finished download where Telegram keeps it on Android 11 and up: its own external files folder. */
    private static File downloaded(String name, String text) throws IOException {
        File folder = new File(context().getExternalFilesDir(null), "Telegram/Telegram Documents");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        File file = new File(folder, name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static Message pdf() {
        return new Message(new Document("2_51.pdf", "report.pdf"), true, false);
    }

    private ByteArrayOutputStream bodyOf(long id) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Shadows.shadowOf(context().getContentResolver()).registerOutputStream(downloads.uriFor(id), body);
        return body;
    }

    @Test public void offByDefaultNothingIsCopied() throws IOException {
        assertFalse(Settings.SAVE_DOWNLOADS.get());
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"), pdf());
        assertTrue(downloads.rows.isEmpty());
        assertTrue(String.join("\n", HookStatus.report()).contains(FamilyNames.SAVE_DOWNLOADS));
    }

    @Test public void onAFileFromAMessageGoesToDownloadTelegramUnderItsOwnName() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        ByteArrayOutputStream body = bodyOf(1);
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "the whole report"), pdf());

        ContentValues row = downloads.row(1);
        assertEquals("report.pdf", row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("Download/Telegram/", row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        // Hidden while it's written, then shown.
        assertEquals(Integer.valueOf(0), row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertEquals(1, downloads.pendingInserts);
        assertEquals("the whole report", body.toString(StandardCharsets.UTF_8.name()));
        assertTrue(String.join("\n", HookStatus.report()).contains("file saved to Download/Telegram"));
    }

    @Test public void thumbnailsOtherMediaAndOtherParentsStayInTelegram() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        File file = downloaded("2_51.pdf", "pdf");
        // The document's thumbnail finishes under its own name, with the same message as its parent.
        SaveDownloads.fileLoaded("2_77.jpg", file, pdf());
        // A GIF, voice or round video, as Telegram's checks call it.
        SaveDownloads.fileLoaded("2_51.pdf", file, new Message(new Document("2_51.pdf", "clip.mp4"), false, false));
        // A sticker set, a profile photo or a wallpaper isn't a message.
        SaveDownloads.fileLoaded("2_51.pdf", file, "a sticker set");
        SaveDownloads.fileLoaded("2_51.pdf", file, null);
        SaveDownloads.fileLoaded(null, file, pdf());
        SaveDownloads.fileLoaded("2_51.pdf", null, pdf());
        assertTrue(downloads.rows.isEmpty());
    }

    @Test public void aFileTheChatDoesntAllowSavingStaysInTelegram() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"),
                new Message(new Document("2_51.pdf", "report.pdf"), true, true));
        assertTrue(downloads.rows.isEmpty());
        assertTrue(String.join("\n", HookStatus.report()).contains("saving not allowed"));
    }

    private static android.content.SharedPreferences receipts() {
        return context().getSharedPreferences(SaveDownloads.RECEIPTS, Context.MODE_PRIVATE);
    }

    @Test public void theSameDocumentDownloadedAgainIsCopiedOnce() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        bodyOf(1);
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"), pdf());
        assertEquals(1, downloads.rows.size());
        // The note is on disk, not in memory, so it holds across a restart.
        assertEquals(1L, receipts().getLong("2_51.pdf", -1));

        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"), pdf());
        assertEquals(1, downloads.rows.size());
        assertTrue(String.join("\n", HookStatus.report()).contains("file already in Download/Telegram"));
    }

    @Test public void twoDocumentsWithTheSameNameAndSizeBothGetACopy() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        ByteArrayOutputStream first = bodyOf(1);
        ByteArrayOutputStream second = bodyOf(2);
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "one"), pdf());
        SaveDownloads.fileLoaded("2_52.pdf", downloaded("2_52.pdf", "two"),
                new Message(new Document("2_52.pdf", "report.pdf"), true, false));
        assertEquals("report.pdf", downloads.row(1).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("report (1).pdf", downloads.row(2).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("one", first.toString(StandardCharsets.UTF_8.name()));
        assertEquals("two", second.toString(StandardCharsets.UTF_8.name()));
    }

    /** A report.pdf of three bytes already in Download/Telegram, added at [added] in MediaStore's seconds. */
    private void anEntryAddedAt(long added) {
        ContentValues earlier = new ContentValues();
        earlier.put(MediaStore.MediaColumns.DISPLAY_NAME, "report.pdf");
        earlier.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Telegram/");
        earlier.put(MediaStore.MediaColumns.SIZE, 3L);
        earlier.put(MediaStore.MediaColumns.DATE_ADDED, added);
        downloads.rows.put(1L, earlier);
        downloads.nextId = 2;
    }

    /** Since the list began, a name and size match can be another file, so only a note counts. */
    @Test public void anEntryAddedSinceTheListBeganNeedsItsNote() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        assertTrue(receipts().edit().putLong(SaveDownloads.BEGAN, 1000).commit());
        anEntryAddedAt(2000);
        ByteArrayOutputStream body = bodyOf(2);
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"), pdf());
        assertEquals("report (1).pdf", downloads.row(2).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("pdf", body.toString(StandardCharsets.UTF_8.name()));
    }

    /** Copies from a version without the list are matched the way it matched them, once each. */
    @Test public void aCopyFromBeforeTheListIsTakenOnceInsteadOfCopiedAgain() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        anEntryAddedAt(1000);
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"), pdf());
        assertEquals(1, downloads.rows.size());
        assertEquals(1L, receipts().getLong("2_51.pdf", -1));
        // The first copy that found no list started it.
        assertTrue(receipts().getLong(SaveDownloads.BEGAN, -1) > 1000);
        assertTrue(String.join("\n", HookStatus.report()).contains(SaveDownloads.ALREADY_SAVED));

        // Another document of the same name and size finds the old entry taken.
        ByteArrayOutputStream body = bodyOf(2);
        SaveDownloads.fileLoaded("2_52.pdf", downloaded("2_52.pdf", "two"),
                new Message(new Document("2_52.pdf", "report.pdf"), true, false));
        assertEquals("report (1).pdf", downloads.row(2).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("two", body.toString(StandardCharsets.UTF_8.name()));
        assertEquals(2L, receipts().getLong("2_52.pdf", -1));
    }

    @Test public void aCopyDeletedFromDownloadsIsMadeAgain() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        bodyOf(1);
        bodyOf(2);
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"), pdf());
        downloads.rows.clear();
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"), pdf());
        assertEquals("report.pdf", downloads.row(2).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals(2L, receipts().getLong("2_51.pdf", -1));
    }

    @Test public void theNotesKeepTheNewestAndDropTheOldest() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        android.content.SharedPreferences.Editor full = receipts().edit();
        for (int i = 0; i < SaveDownloads.MAX_RECEIPTS; i++) full.putLong("2_" + (1000 + i) + ".pdf", 100 + i);
        full.commit();
        downloads.nextId = 5000;
        bodyOf(5000);
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"), pdf());
        // The notes, plus when the list began.
        assertEquals(SaveDownloads.MAX_RECEIPTS + 1, receipts().getAll().size());
        assertEquals(5000L, receipts().getLong("2_51.pdf", -1));
        assertFalse("the oldest note stayed", receipts().contains("2_1000.pdf"));
        assertTrue(receipts().contains("2_1001.pdf"));
    }

    @Test public void aCopyWaitingWhenTheSwitchGoesOffOrPausesPublishesNothing() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        SaveDownloads.work = queued::add;
        File file = downloaded("2_51.pdf", "pdf");
        SaveDownloads.fileLoaded("2_51.pdf", file, pdf());
        Settings.SAVE_DOWNLOADS.save(false);
        SaveDownloads.fileLoaded("2_51.pdf", file, pdf());
        assertEquals(1, queued.size());
        queued.remove(0).run();

        Settings.SAVE_DOWNLOADS.save(true);
        SaveDownloads.fileLoaded("2_51.pdf", file, pdf());
        PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
        queued.remove(0).run();
        assertTrue(downloads.rows.isEmpty());
        assertEquals("nothing was even started", 0, downloads.pendingInserts);
        assertTrue(String.join("\n", HookStatus.report()).contains("copy dropped, switched off or paused"));
        assertFalse(receipts().contains("2_51.pdf"));
    }

    @Test public void pausingWhileTheCopyIsWrittenRemovesTheHiddenEntry() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        Shadows.shadowOf(context().getContentResolver()).registerOutputStream(downloads.uriFor(1), new OutputStream() {
            @Override public void write(int b) {
                PauseForTests.pause(HushTelegramPause.Reason.SWITCH);
            }
        });
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"), pdf());
        assertEquals(1, downloads.pendingInserts);
        assertTrue("the hidden entry stayed behind", downloads.rows.isEmpty());
        assertTrue(String.join("\n", HookStatus.report()).contains("copy dropped, switched off or paused"));
        assertFalse(receipts().contains("2_51.pdf"));
    }

    @Test public void aFileAlreadyInSharedStorageIsntCopiedAgain() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        // Android 10 with storage access keeps Telegram's folder on shared storage.
        File shared = elsewhere.newFile("2_51.pdf");
        assertFalse(SaveDownloads.insideApp(context(), shared));
        SaveDownloads.fileLoaded("2_51.pdf", shared, pdf());
        assertTrue(downloads.rows.isEmpty());
        assertTrue(String.join("\n", HookStatus.report()).contains("file already in shared storage"));
        assertTrue(SaveDownloads.insideApp(context(), downloaded("2_51.pdf", "pdf")));
        assertTrue(SaveDownloads.insideApp(context(), new File(context().getExternalCacheDir(), "2_51.pdf")));
        assertTrue(SaveDownloads.insideApp(context(), new File(context().getFilesDir(), "2_51.pdf")));
    }

    @Config(sdk = 28)
    @Test public void android9KeepsTelegramsSharedFolder() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"), pdf());
        assertTrue(downloads.rows.isEmpty());
        assertTrue(String.join("\n", HookStatus.report()).contains("Android 9"));
    }

    @Test public void aCopyThatFailsLeavesNothingBehind() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        Shadows.shadowOf(context().getContentResolver()).registerOutputStream(downloads.uriFor(1), new OutputStream() {
            @Override public void write(int b) throws IOException {
                throw new IOException("storage full");
            }
        });
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"), pdf());
        assertEquals(1, downloads.pendingInserts);
        assertTrue("the half-written entry is removed", downloads.rows.isEmpty());
        assertFalse(HookStatus.missing(FamilyNames.SAVE_DOWNLOADS).isEmpty());
    }

    @Test public void pausingAnEarlyStartOrAnUnreadableSwitchCopiesNothing() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        File file = downloaded("2_51.pdf", "pdf");
        for (HushTelegramPause.Reason reason : HushTelegramPause.Reason.values()) {
            if (reason == HushTelegramPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            SaveDownloads.fileLoaded("2_51.pdf", file, pdf());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> SaveDownloads.fileLoaded("2_51.pdf", file, pdf()));
        SettingReadsForTests.breakReads(Settings.SAVE_DOWNLOADS);
        SaveDownloads.fileLoaded("2_51.pdf", file, pdf());
        assertTrue(downloads.rows.isEmpty());
        assertFalse(HookStatus.missing(FamilyNames.SAVE_DOWNLOADS).isEmpty());
    }

    @Test public void theNameIsTheDocumentsOwnWithAnExtension() {
        assertEquals("report.pdf", SaveDownloads.displayName("report.pdf", "2_51.pdf"));
        // A song sent without a file name, or a name without an extension, takes the download's.
        assertEquals("2_51.mp3", SaveDownloads.displayName("", "2_51.mp3"));
        assertEquals("2_51.mp3", SaveDownloads.displayName(null, "2_51.mp3"));
        assertEquals("notes.txt", SaveDownloads.displayName("notes", "2_51.txt"));
        assertEquals("notes", SaveDownloads.displayName("notes", "2_51"));
        // A name can't reach outside the folder.
        assertEquals("_.._etc_passwd.txt", SaveDownloads.displayName("/../etc/passwd.txt", "2_51.txt"));
        assertEquals("2_51.txt", SaveDownloads.displayName("..", "2_51.txt"));
        assertEquals("2_51.txt", SaveDownloads.displayName("  ", "2_51.txt"));
        // A right-to-left override (U+202E) in "report<RLO>exe.pdf" would show it as "reportfdp.exe" in a file manager.
        assertEquals("report_exe.pdf", SaveDownloads.displayName("report\u202eexe.pdf", "2_51.pdf"));
        assertEquals("a_b_c.txt", SaveDownloads.displayName("a\u2066b\u2069c.txt", "2_51.txt"));
        // Marks that don't reorder text, like the zero-width joiner in an emoji sequence, stay.
        String coder = "\uD83D\uDC68\u200D\uD83D\uDCBB.pdf";
        assertEquals(coder, SaveDownloads.displayName(coder, "2_51.pdf"));
    }

    @Test public void aNameTooLongForTheFileSystemLosesTheEndOfItsStemAndKeepsItsExtension() {
        String stem = new String(new char[300]).replace('\0', 'x');
        String name = SaveDownloads.displayName(stem + ".pdf", "2_51.pdf");
        assertEquals(SaveDownloads.MAX_NAME_BYTES, name.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(name, name.endsWith(".pdf"));
        assertEquals(SaveDownloads.MAX_NAME_BYTES, SaveDownloads.displayName(stem, "2_51").getBytes(StandardCharsets.UTF_8).length);
        // Cut by code points, so a four-byte emoji at the edge isn't halved into two broken characters.
        StringBuilder faces = new StringBuilder();
        for (int i = 0; i < 100; i++) faces.append("😀");
        String cut = SaveDownloads.displayName(faces + ".mp3", "2_51.mp3");
        assertTrue(cut, cut.getBytes(StandardCharsets.UTF_8).length <= SaveDownloads.MAX_NAME_BYTES);
        assertTrue(cut, cut.endsWith(".mp3"));
        assertEquals((SaveDownloads.MAX_NAME_BYTES - 4) / 4 * 2 + 4, cut.length());
        // A name that's all extension has nothing left to cut, so the download's own name is used.
        assertEquals("2_51.pdf", SaveDownloads.displayName("." + stem, "2_51.pdf"));
    }

    /** MediaStore's Downloads table, as much of it as a copy touches. */
    public static final class Downloads extends ContentProvider {
        final Map<Long, ContentValues> rows = new LinkedHashMap<>();
        long nextId = 1;
        int pendingInserts;

        Uri uriFor(long id) {
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
        }

        ContentValues row(long id) {
            ContentValues row = rows.get(id);
            assertNotNull("no Downloads entry " + id, row);
            return row;
        }

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            ContentValues row = new ContentValues(values);
            row.put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000);
            if (Integer.valueOf(1).equals(row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))) pendingInserts++;
            // MediaStore keeps names in a folder unique by numbering the newcomer.
            String name = row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
            for (ContentValues other : rows.values()) {
                if (name.equals(other.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))) {
                    row.put(MediaStore.MediaColumns.DISPLAY_NAME, name.replace(".pdf", " (1).pdf"));
                }
            }
            long id = nextId++;
            rows.put(id, row);
            return uriFor(id);
        }

        /** One entry by its own address, or the entries a version without the list could have made. */
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            MatrixCursor cursor = new MatrixCursor(projection);
            if (selection == null) {
                long id = ContentUris.parseId(uri);
                if (rows.containsKey(id)) cursor.addRow(new Object[]{id});
                return cursor;
            }
            assertEquals("only copies from before the list are found by name and size",
                    SaveDownloads.OLDER_COPY, selection);
            for (Map.Entry<Long, ContentValues> entry : rows.entrySet()) {
                ContentValues row = entry.getValue();
                Long added = row.getAsLong(MediaStore.MediaColumns.DATE_ADDED);
                if (selectionArgs[0].equals(row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
                        && selectionArgs[1].equals(row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
                        && selectionArgs[2].equals(String.valueOf(row.getAsLong(MediaStore.MediaColumns.SIZE)))
                        && added != null && added < Long.parseLong(selectionArgs[3])) {
                    cursor.addRow(new Object[]{entry.getKey()});
                }
            }
            return cursor;
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }

        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            return rows.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }

        @Override public String getType(Uri uri) {
            return "application/octet-stream";
        }
    }
}
