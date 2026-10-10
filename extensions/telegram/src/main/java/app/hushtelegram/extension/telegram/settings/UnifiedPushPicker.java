/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.preference.Preference;

import java.util.List;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.telegram.misc.UnifiedPush;

/**
 * The list of installed UnifiedPush apps, for the Push app row. Picking one signs up with it at
 * once when the switch is on, and ends the sign-up with the one before.
 */
final class UnifiedPushPicker {
    static final String KEY = "action_push_app";

    private UnifiedPushPicker() {}

    static void open(Activity activity, Preference row) {
        List<String> apps = UnifiedPush.distributors(activity);
        if (apps.isEmpty()) {
            Utils.showToastLong(L10n.t("Install a UnifiedPush app like ntfy first."));
            return;
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = UnifiedPush.label(activity, apps.get(i));
        AlertDialog dialog = new AlertDialog.Builder(HushTelegramPreferenceFragment.themed(activity))
                .setTitle(L10n.t("Push app"))
                .setSingleChoiceItems(labels, apps.indexOf(UnifiedPush.chosen(activity)), (shown, which) -> {
                    boolean on = Settings.UNIFIED_PUSH.savedValue();
                    UnifiedPush.choose(activity, apps.get(which), on);
                    row.setSummary(UnifiedPush.status(activity, on));
                    Utils.runOnMainThreadDelayed(() -> row.setSummary(UnifiedPush.status(activity,
                            Settings.UNIFIED_PUSH.savedValue())), 2000);
                    shown.dismiss();
                })
                .setNegativeButton(L10n.t("Cancel"), null)
                .show();
        ScreenColors.dialog(dialog);
    }
}
