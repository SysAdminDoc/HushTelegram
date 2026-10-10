/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Copies a file or song to Download/Telegram when it finishes downloading, where file managers
 * and other apps can open it.
 *
 * <p>Since Android 11 Telegram keeps what it downloads in its own app folder, and its message menu
 * saves one file at a time with Save to Downloads. The patch has Telegram's finished-download
 * callback, ImageLoader's {@code FileLoaderDelegate.fileDidLoaded}, call {@link #fileLoaded} first.
 * With the switch on, the file goes to the folder Save to Downloads uses.
 *
 * <p>Only what Telegram would let you save by hand: a document or a song from a message. Photos and
 * videos have Telegram's own Save to gallery, and GIFs, voice and round videos stay put. Nothing
 * from a secret chat, self-destructing media, or a chat or message that doesn't allow saving. A
 * copy already there with the same name and size is left alone. On Android 9 and 10 with storage
 * access, Telegram's folder is already shared, so there's nothing to copy.
 */
public final class SaveDownloads {
    /** Where the copies go, as MediaStore writes it: the shared Download folder's Telegram folder. */
    static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/Telegram/";

    private SaveDownloads() {}

    /** Telegram's side, swapped out by tests. The real one calls the stubs the patch writes. */
    interface Telegram {
        /** The message's document, or null when what finished downloading isn't for a message. */
        Object document(Object parent);

        /** Whether the message is a file or a song, not a GIF, voice, round video or self-destructing media. */
        boolean fileOrSong(Object message);

        /** Whether the message, a secret chat or the chat's own setting keeps it from being saved. */
        boolean savingForbidden(Object message);

        /** The name Telegram's downloader knows the document by. */
        String attachName(Object document);

        /** The document's own file name, empty when it has none. */
        String documentName(Object document);
    }

    static Telegram telegram = new Telegram() {
        @Override
        public Object document(Object parent) {
            return messageDocument(parent);
        }

        @Override
        public boolean fileOrSong(Object message) {
            return SaveDownloads.fileOrSong(message);
        }

        @Override
        public boolean savingForbidden(Object message) {
            return SaveDownloads.savingForbidden(message);
        }

        @Override
        public String attachName(Object document) {
            return SaveDownloads.attachName(document);
        }

        @Override
        public String documentName(Object document) {
            return SaveDownloads.documentName(document);
        }
    };

    /** Copies run here one at a time, off Telegram's download queue. Tests swap in a direct one. */
    static Executor work = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "HushTelegram SaveDownloads");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Injected at the start of the finished-download callback, on FileLoader's queue.
     *
     * @param name   the downloader's name for the file
     * @param file   the finished file
     * @param parent what it was downloaded for, a MessageObject for a message's file
     */
    public static void fileLoaded(String name, File file, Object parent) {
        HookStatus.invoked(FamilyNames.SAVE_DOWNLOADS);
        try {
            if (name == null || file == null || !Utils.settingsReady() || !Settings.SAVE_DOWNLOADS.get()) return;
            Object document = telegram.document(parent);
            // A message's thumbnail or cover loads with the message as its parent too; only the document itself counts.
            if (document == null || !name.equals(telegram.attachName(document)) || !telegram.fileOrSong(parent)) return;
            if (telegram.savingForbidden(parent)) {
                HookStatus.counted(FamilyNames.SAVE_DOWNLOADS, "file kept in Telegram, saving not allowed");
                return;
            }
            String display = displayName(telegram.documentName(document), file.getName());
            work.execute(() -> save(file, display));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVE_DOWNLOADS, "download", failure);
        }
    }

    /** The document's own name, with the downloaded file's extension when it has none of its own. */
    static String displayName(String documentName, String fileName) {
        String name = documentName == null ? "" : documentName.replaceAll("[/\\\\\\p{Cntrl}]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return fileName;
        if (extension(name).isEmpty() && !extension(fileName).isEmpty()) name += "." + extension(fileName);
        return name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    static void save(File file, String name) {
        try {
            Context context = Utils.getContext();
            if (context == null || !file.isFile()) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                HookStatus.counted(FamilyNames.SAVE_DOWNLOADS, "file left in Telegram's shared folder on Android 9");
                return;
            }
            if (!insideApp(context, file)) {
                HookStatus.counted(FamilyNames.SAVE_DOWNLOADS, "file already in shared storage");
                return;
            }
            HookStatus.counted(FamilyNames.SAVE_DOWNLOADS, copy(context.getContentResolver(), file, name)
                    ? "file saved to Download/Telegram" : "file already in Download/Telegram");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVE_DOWNLOADS, "copy", failure);
        }
    }

    /**
     * Whether the file is in one of Telegram's own folders, which other apps can't open: its data
     * folder, or Android/data/&lt;package&gt; on the phone or a memory card Telegram stores to.
     */
    static boolean insideApp(Context context, File file) throws IOException {
        String path = file.getCanonicalPath();
        File data = context.getDataDir();
        if (data != null && path.startsWith(data.getCanonicalPath() + File.separator)) return true;
        List<File> own = new ArrayList<>(Arrays.asList(context.getExternalFilesDirs(null)));
        own.addAll(Arrays.asList(context.getExternalCacheDirs()));
        for (File folder : own) {
            File root = folder == null ? null : folder.getParentFile();
            if (root != null && path.startsWith(root.getCanonicalPath() + File.separator)) return true;
        }
        return false;
    }

    /** Copies into MediaStore's Downloads, hidden until it's whole. False when the same file is there already. */
    private static boolean copy(ContentResolver resolver, File file, String name) throws IOException {
        Uri downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        try (Cursor same = resolver.query(downloads, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                        + MediaStore.MediaColumns.SIZE + "=?",
                new String[]{name, FOLDER, Long.toString(file.length())}, null)) {
            if (same != null && same.moveToFirst()) return false;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        // From the name, the way Save to Downloads does, so MediaStore doesn't add a second extension.
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(name).toLowerCase(Locale.ROOT));
        if (mime != null) values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri pending = resolver.insert(downloads, values);
        if (pending == null) throw new IOException("MediaStore didn't take the file");
        try {
            try (InputStream in = new FileInputStream(file); OutputStream out = resolver.openOutputStream(pending, "w")) {
                if (out == null) throw new IOException("MediaStore gave no stream");
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(pending, values, null, null) != 1) throw new IOException("MediaStore didn't publish the file");
            return true;
        } catch (IOException | RuntimeException failure) {
            try {
                resolver.delete(pending, null, null);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    // Stubs. The patch gives each one Telegram's code; until then they do nothing.

    /** {@code parent instanceof MessageObject ? getDocument() : null}. */
    public static Object messageDocument(Object parent) { return null; }

    /** Not {@code isSecretMedia()}, and {@code isMusic()} or {@code isDocument()} without {@code isGif()}, {@code isRoundVideo()} or {@code isVoice()}. */
    public static boolean fileOrSong(Object message) { return false; }

    /** {@code messageOwner.noforwards}, a secret chat, or {@code MessagesController.isPeerNoForwards} for the chat. */
    public static boolean savingForbidden(Object message) { return true; }

    /** {@code FileLoader.getAttachFileName(document)}. */
    public static String attachName(Object document) { return null; }

    /** {@code FileLoader.getDocumentFileName(document)}. */
    public static String documentName(Object document) { return null; }
}
