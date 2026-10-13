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

import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.TwoStatePreference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.hushtelegram.extension.shared.settings.BooleanSetting;

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

    /**
     * Sets each switch row on [screen] named in [values] that shows something else, and answers
     * how many changed. A row that already shows its value, or whose change listener turns the
     * change down, doesn't count.
     */
    public static int apply(PreferenceGroup screen, Map<BooleanSetting, Boolean> values) {
        int changed = 0;
        for (Map.Entry<BooleanSetting, Boolean> value : values.entrySet()) {
            Preference row = screen.findPreference(value.getKey().key);
            if (!(row instanceof TwoStatePreference) || value.getValue() == null) continue;
            TwoStatePreference toggle = (TwoStatePreference) row;
            boolean wanted = value.getValue();
            if (toggle.isChecked() == wanted) continue;
            // setChecked skips the row's change listener, which a tap runs. Telegram's rows use it
            // for work a tap does, such as forgetting chats' translate choices when that switch goes off.
            Preference.OnPreferenceChangeListener listener = toggle.getOnPreferenceChangeListener();
            if (listener != null && !listener.onPreferenceChange(toggle, wanted)) continue;
            toggle.setChecked(wanted);
            changed++;
        }
        return changed;
    }
}
