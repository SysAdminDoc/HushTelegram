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

    @Test public void theSameFileAlreadyThereIsLeftAlone() throws IOException {
        Settings.SAVE_DOWNLOADS.save(true);
        ContentValues earlier = new ContentValues();
        earlier.put(MediaStore.MediaColumns.DISPLAY_NAME, "report.pdf");
        earlier.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Telegram/");
        earlier.put(MediaStore.MediaColumns.SIZE, 3L);
        downloads.rows.put(1L, earlier);
        downloads.nextId = 2;

        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "pdf"), pdf());
        assertEquals(1, downloads.rows.size());
        assertTrue(String.join("\n", HookStatus.report()).contains("file already in Download/Telegram"));

        // Another file under that name, a different size, still gets its copy, and MediaStore numbers it.
        ByteArrayOutputStream body = bodyOf(2);
        SaveDownloads.fileLoaded("2_51.pdf", downloaded("2_51.pdf", "a newer report"), pdf());
        assertEquals("report (1).pdf", downloads.row(2).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("a newer report", body.toString(StandardCharsets.UTF_8.name()));
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

        /** The collection, by name, folder and size. */
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, uri);
            assertEquals("_display_name=? AND relative_path=? AND _size=?", selection);
            MatrixCursor cursor = new MatrixCursor(projection);
            for (Map.Entry<Long, ContentValues> row : rows.entrySet()) {
                ContentValues values = row.getValue();
                if (selectionArgs[0].equals(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
                        && selectionArgs[1].equals(values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
                        && selectionArgs[2].equals(values.getAsString(MediaStore.MediaColumns.SIZE))) {
                    cursor.addRow(new Object[]{row.getKey()});
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
