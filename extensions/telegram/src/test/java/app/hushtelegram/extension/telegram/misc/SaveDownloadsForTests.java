/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lets tests outside this package ask the finished-download hook, and gives them a Telegram to ask it with. */
public final class SaveDownloadsForTests {
    private SaveDownloadsForTests() {}

    /** A document as Telegram's downloader names it, and its own file name. */
    static final class Document {
        final String attachName;
        final String name;

        Document(String attachName, String name) {
            this.attachName = attachName;
            this.name = name;
        }
    }

    /** A message with a document, and what Telegram's checks say about it. */
    static final class Message {
        final Document document;
        final boolean fileOrSong;
        final boolean forbidden;

        Message(Document document, boolean fileOrSong, boolean forbidden) {
            this.document = document;
            this.fileOrSong = fileOrSong;
            this.forbidden = forbidden;
        }
    }

    /** Answers the way the stubs do for {@link Message} parents. */
    static final SaveDownloads.Telegram TELEGRAM = new SaveDownloads.Telegram() {
        @Override
        public Object document(Object parent) {
            return parent instanceof Message ? ((Message) parent).document : null;
        }

        @Override
        public boolean fileOrSong(Object message) {
            return ((Message) message).fileOrSong;
        }

        @Override
        public boolean savingForbidden(Object message) {
            return ((Message) message).forbidden;
        }

        @Override
        public String attachName(Object document) {
            return ((Document) document).attachName;
        }

        @Override
        public String documentName(Object document) {
            return ((Document) document).name;
        }
    };

    /** Whether a PDF from an ordinary message, once downloaded, would be queued for a copy. */
    public static boolean queuesACopy() {
        SaveDownloads.Telegram telegram = SaveDownloads.telegram;
        Executor work = SaveDownloads.work;
        AtomicBoolean queued = new AtomicBoolean();
        SaveDownloads.telegram = TELEGRAM;
        SaveDownloads.work = task -> queued.set(true);
        try {
            SaveDownloads.fileLoaded("2_51.pdf", new File("2_51.pdf"),
                    new Message(new Document("2_51.pdf", "report.pdf"), true, false));
        } finally {
            SaveDownloads.telegram = telegram;
            SaveDownloads.work = work;
        }
        return queued.get();
    }
}
