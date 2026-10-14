/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at 44fb933 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.hushtelegram.extension.shared.settings.preference;

import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.TwoStatePreference;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.settings.BooleanSetting;
import app.hushtelegram.extension.shared.settings.Setting;

/**
 * Quick setups: a set of switches flipped in one tap. Each choice is a map from switch to the
 * value it puts there; which switches go in which choice is the app's call, in its own class.
 *
 * <p>Every change goes through the switch's row, the same as a tap on it: the row's own change
 * listener runs first and can turn the change down, then the page's listener saves it, keeps the
 * rows that depend on it in step and notes a restart when the switch needs one. A switch whose
 * patch isn't in this build has no row and is left alone.
 */
@SuppressWarnings("deprecation")
public final class QuickSetups {
    private QuickSetups() {
    }

    /** Each of [settings] turned on. */
    public static Map<BooleanSetting, Boolean> allOn(List<BooleanSetting> settings) {
        Map<BooleanSetting, Boolean> values = new LinkedHashMap<>();
        for (BooleanSetting setting : settings) values.put(setting, Boolean.TRUE);
        return Collections.unmodifiableMap(values);
    }

    /** Each of [settings] back to the value it started with. */
    public static Map<BooleanSetting, Boolean> defaults(List<BooleanSetting> settings) {
        Map<BooleanSetting, Boolean> values = new LinkedHashMap<>();
        for (BooleanSetting setting : settings) values.put(setting, setting.defaultValue);
        return Collections.unmodifiableMap(values);
    }

    /** What one choice did: the switches it changed, and how many it found set already or couldn't change. */
    public static final class Outcome {
        /** Each switch it changed and the value it put there, in the choice's order. */
        public final Map<BooleanSetting, Boolean> changed;
        /** Switches whose row already showed the value. */
        public final int already;
        /** Switches it couldn't change: an import held the row, its listener turned the change down, or the save didn't land. */
        public final int refused;

        Outcome(Map<BooleanSetting, Boolean> changed, int already, int refused) {
            this.changed = Collections.unmodifiableMap(changed);
            this.already = already;
            this.refused = refused;
        }
    }

    /**
     * The last choice that changed something, cut down to the switches nothing has written since.
     * Kept in memory only and never more than one choice's switches: a later choice that changes
     * something replaces it, a write from anywhere else takes that switch out, an import empties it,
     * and the process ending forgets it. Main thread only.
     */
    @Nullable
    private static Outcome last;
    /** How many of the last choice's switches were written by something else since. */
    private static int writtenSince;
    /**
     * How many choices and undos have started writing a switch, so a clear heard late can tell
     * whether the last choice's writes all came after it. Counted before the first write, so a clear
     * in the middle of a choice counts as after it.
     */
    private static volatile int choices;
    /**
     * {@link #choices} as the last choice started writing. An undo's leftover keeps it, since its
     * switches are the choice's own writes. Main thread only.
     */
    private static int lastStarted;
    /** True while a choice or an undo writes, so the watch below doesn't take its own writes for someone else's. */
    private static boolean writing;
    /** Hears every write to the settings file. Held here: the file keeps only a weak reference to it. */
    private static final SharedPreferences.OnSharedPreferenceChangeListener WATCH = (preferences, key) -> {
        // A null key is the whole file cleared. Android 11 and later say so here, but from another
        // thread only later, maybe after a newer choice. The file says so itself, through cleared().
        if (writing || last == null || key == null) return;
        Map<BooleanSetting, Boolean> left = new LinkedHashMap<>(last.changed);
        if (left.keySet().removeIf(setting -> setting.key.equals(key))) {
            writtenSince++;
            last = left.isEmpty() ? null : new Outcome(left, last.already, last.refused);
        }
    };
    /** The store {@link #WATCH} listens to: the settings file's, which a test may swap for a fresh one. */
    @Nullable
    private static SharedPreferences watched;

    /**
     * Sets each switch row on [screen] named in [values] that shows something else, and says what
     * changed. A row that already shows its value, or whose change listener turns the change down,
     * isn't among the changes. A choice that changes something becomes the one {@link #undo} puts back.
     */
    public static Outcome apply(PreferenceGroup screen, Map<BooleanSetting, Boolean> values) {
        Outcome outcome = write(screen, values);
        if (!outcome.changed.isEmpty()) {
            last = outcome;
            // write() counted this choice and nothing else counts off the main thread.
            lastStarted = choices;
            writtenSince = 0;
            SharedPreferences store = Setting.preferences.preferences;
            if (watched != store) {
                if (watched != null) watched.unregisterOnSharedPreferenceChangeListener(WATCH);
                store.registerOnSharedPreferenceChangeListener(WATCH);
                watched = store;
            }
        }
        return outcome;
    }

    /** What the last choice changed that nothing has written since, or null when there's nothing to undo. */
    @Nullable
    public static Outcome last() {
        return last;
    }

    /** How many of the last choice's switches something else wrote after it. */
    public static int writtenSince() {
        return writtenSince;
    }

    /**
     * Puts each switch the last choice changed back the way it was, through its row as a tap would,
     * and answers how many went back. Only switches go back: whatever a row's listener did as the
     * switch changed, such as forgetting chats' translate choices, stays done. A switch that didn't
     * go back stays on the list for another try.
     */
    public static int undo(PreferenceGroup screen) {
        Outcome undoing = last;
        if (undoing == null) return 0;
        Map<BooleanSetting, Boolean> before = new LinkedHashMap<>();
        for (Map.Entry<BooleanSetting, Boolean> change : undoing.changed.entrySet()) {
            before.put(change.getKey(), !change.getValue());
        }
        Outcome outcome = write(screen, before);
        Map<BooleanSetting, Boolean> left = new LinkedHashMap<>(undoing.changed);
        for (Map.Entry<BooleanSetting, Boolean> change : undoing.changed.entrySet()) {
            // Back the way it was, by this undo or already: nothing left to put back.
            if (!change.getValue().equals(change.getKey().savedValue())) left.remove(change.getKey());
        }
        // What's left is the undo's leftover, so the choice's own counts of what was already set,
        // couldn't change or was written since no longer describe it.
        last = left.isEmpty() ? null : new Outcome(left, 0, 0);
        writtenSince = 0;
        return outcome.changed.size();
    }

    /** Drops the last choice, so nothing is left to undo. A settings import calls this as it starts. */
    public static void forget() {
        last = null;
        writtenSince = 0;
    }

    /**
     * A settings file was cleared, on any thread. Before Android 11 clearing a file tells its
     * listeners nothing, so the file says so itself, and when it's the watched one, nothing is left to
     * undo. From another thread that's settled later on the main thread, where a choice that started
     * writing after the clear keeps its undo. One that changed nothing, all its saves failing, leaves
     * the older choice's undo to go.
     */
    static void cleared(SharedPreferences store) {
        int before = choices;
        Utils.runOnMainThreadNowOrLater(() -> {
            if (store == watched && lastStarted <= before) forget();
        });
    }

    private static Outcome write(PreferenceGroup screen, Map<BooleanSetting, Boolean> values) {
        Map<BooleanSetting, Boolean> changed = new LinkedHashMap<>();
        int already = 0;
        int refused = 0;
        boolean started = false;
        writing = true;
        try {
            for (Map.Entry<BooleanSetting, Boolean> value : values.entrySet()) {
                Preference row = screen.findPreference(value.getKey().key);
                if (!(row instanceof TwoStatePreference) || value.getValue() == null) continue;
                TwoStatePreference toggle = (TwoStatePreference) row;
                boolean wanted = value.getValue();
                if (toggle.isChecked() == wanted) {
                    already++;
                    continue;
                }
                // Out of reach of a tap while a settings import may write it, so out of reach here too.
                if (AbstractPreferenceFragment.importOwns(row.getKey())) {
                    refused++;
                    continue;
                }
                // setChecked skips the row's change listener, which a tap runs. Telegram's rows use it
                // for work a tap does, such as forgetting chats' translate choices when that switch goes off.
                Preference.OnPreferenceChangeListener listener = toggle.getOnPreferenceChangeListener();
                if (listener != null && !listener.onPreferenceChange(toggle, wanted)) {
                    refused++;
                    continue;
                }
                if (!started) {
                    started = true;
                    choices++;
                }
                toggle.setChecked(wanted);
                // The page saves the switch as the row changes. A save that failed put the row back.
                if (Boolean.valueOf(wanted).equals(value.getKey().savedValue())) changed.put(value.getKey(), wanted);
                else refused++;
            }
        } finally {
            writing = false;
        }
        return new Outcome(changed, already, refused);
    }
}
