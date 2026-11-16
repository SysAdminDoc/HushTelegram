/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;
import android.util.SparseIntArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Lets a test outside this package hand ListBackground a theme's colors and a picture without Telegram. */
public final class ListBackgroundForTests {
    private ListBackgroundForTests() {}

    private static final ListBackground.Tables TELEGRAM = ListBackground.tables;

    /** Ids 1 to 3 for the page colors, and [table] as the built-in theme's set in use. */
    public static void useTable(SparseIntArray table) {
        ListBackground.ids = new int[]{1, 2, 3};
        ListBackground.builtIn(table);
        ListBackground.tables = () -> table;
    }

    /** Some bytes where the picture behind the lists is kept, which is all a refresh looks for. */
    public static void keepPicture(Context context) {
        File file = SavedPicture.file(context, SavedPicture.LIST_BACKGROUND);
        File folder = file.getParentFile();
        if (folder == null || !folder.mkdirs() && !folder.isDirectory()) throw new AssertionError("no folder for " + file);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3});
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    public static void forget(Context context) {
        SavedPicture.remove(context, SavedPicture.LIST_BACKGROUND);
        ListBackground.tables = TELEGRAM;
        ListBackground.ids = null;
    }
}
