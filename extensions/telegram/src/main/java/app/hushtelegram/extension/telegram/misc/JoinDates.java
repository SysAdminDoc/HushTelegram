/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.telegram.settings.FamilyNames;
import app.hushtelegram.extension.telegram.settings.Settings;

/**
 * Shows when each person joined a group in its member lists (#79): the Members page and the list on
 * the group's profile. The date is the one Telegram already sends with every member, so nothing
 * more is asked of it. A list binds a member and then its row, and the row writes its second line
 * afterwards, so the patch tells this class which member each row shows and the date goes after
 * the last seen text that line carries. A row showing its own text instead, such as an admin's
 * role or who removed someone, keeps it, and a member without a date gets nothing added.
 */
public final class JoinDates {
    private JoinDates() {}

    /** When the member each bound row shows joined, in seconds since 1970. A dropped row drops out. */
    private static final Map<Object, Integer> JOINED = new WeakHashMap<>();
    /** The date of the member being bound, until the patch names its row. */
    private static int pending;
    /** The row writing its second line right now. */
    private static WeakReference<Object> current = new WeakReference<>(null);

    /** Called with the member a list is about to bind to a row. */
    public static void member(Object participant) {
        try {
            pending = participant != null && on() ? participantDate(participant) : 0;
        } catch (Throwable failure) {
            pending = 0;
            HookStatus.threw(FamilyNames.SHOW_LOCAL_IDS, "member join date", failure);
        }
    }

    /** Called with the row the member from {@link #member} is bound to. */
    public static void cell(Object cell) {
        try {
            if (cell == null) return;
            if (pending > 0) JOINED.put(cell, pending);
            else JOINED.remove(cell);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHOW_LOCAL_IDS, "member join date row", failure);
        } finally {
            pending = 0;
        }
    }

    /** Called first thing when a member row writes its lines. */
    public static void updating(Object cell) {
        try {
            current = new WeakReference<>(cell);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHOW_LOCAL_IDS, "member join date row", failure);
        }
    }

    /** The last seen text a member row is about to show, with when its member joined after it. */
    public static String status(String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            if (!on()) return text;
            Object cell = current.get();
            if (!(cell instanceof View)) return text;
            Integer joined = JOINED.get(cell);
            if (joined == null || joined <= 0) return text;
            Context context = ((View) cell).getContext();
            String shown = L10n.f(context, "%1$s · joined %2$s", text, when(context, joined));
            HookStatus.counted(FamilyNames.SHOW_LOCAL_IDS, "member join date shown");
            return shown;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHOW_LOCAL_IDS, "member join date", failure);
            return text;
        }
    }

    /** [seconds] as the phone writes a date and time, such as "12 Mar 2024, 14:05". */
    static String when(Context context, int seconds) {
        return DateUtils.formatDateTime(context, seconds * 1000L, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
    }

    static boolean on() {
        try {
            return Utils.settingsReady() && Settings.MEMBER_JOIN_DATES.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHOW_LOCAL_IDS, "member join dates switch", failure);
            return false;
        }
    }

    /** Forgets every row, for tests. */
    static void clearForTests() {
        JOINED.clear();
        pending = 0;
        current = new WeakReference<>(null);
    }

    /** Replaced when patching: when [participant], one of Telegram's group or channel members, joined; 0 when it doesn't say. */
    public static int participantDate(Object participant) { return 0; }
}
