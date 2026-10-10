/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.preference.Preference;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.util.List;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Logger;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.settings.StringSetting;
import app.hushtelegram.extension.telegram.misc.MessageFilters;

/**
 * The editor for one list of message filters: the list as text in a dialog, one filter a line.
 * Save checks every line first and keeps the dialog open on the first one that can't be used,
 * saying which and why, so what's saved is always a list the chat can use whole.
 */
final class FilterEditor {
    static final String GROUPS = "action_message_filters_groups";
    static final String CHANNELS = "action_message_filters_channels";

    private FilterEditor() {}

    /** What a list's row says under its title: how many filters it holds. */
    static String summary(StringSetting list) {
        int count = MessageFilters.lines(list.savedValue()).size();
        return count == 0 ? L10n.t("No filters yet.")
                : L10n.quantity(count, "%1$d filter.", "%1$d filters.", count);
    }

    /**
     * Why a list can't be saved, naming its first line that can't be used, or null when it can.
     * Lines count from one as the person sees them, blanks included.
     */
    @Nullable
    static String refusal(String typed) {
        String[] lines = typed.split("\r\n|\r|\n", -1);
        int filters = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (++filters > MessageFilters.MAX_FILTERS) {
                return L10n.f("A list holds up to %1$d filters.", MessageFilters.MAX_FILTERS);
            }
            MessageFilters.Problem problem = MessageFilters.problem(line);
            if (problem == null) continue;
            switch (problem) {
                case TOO_LONG:
                    return L10n.f("Line %1$d is longer than %2$d characters.", i + 1, MessageFilters.MAX_FILTER_CHARS);
                case BROKEN:
                    return L10n.f("Line %1$d isn't a regular expression that works. Fix it, or take the slashes off "
                            + "to match the text as written.", i + 1);
                default:
                    return L10n.f("Line %1$d could take too long on a long message. Use one open-ended repeat "
                            + "like + or * at most, a count like {1,9} for the rest, and leave out repeated groups "
                            + "that hold a repeat or a choice, like (a+)+ or (a|b)*, and references back to a group.",
                            i + 1);
            }
        }
        if (!MessageFilters.fits(MessageFilters.lines(typed))) {
            return L10n.t("This list is too long to fit in a settings file. Take out a few lines.");
        }
        return null;
    }

    /** Opens the editor over the settings page. The row's summary follows what's saved. */
    static void open(Activity activity, Preference row, StringSetting list, String title) {
        EditText field = new EditText(HushTelegramPreferenceFragment.themed(activity));
        field.setId(android.R.id.edit);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine(false);
        field.setMinLines(4);
        field.setMaxLines(12);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setHint(L10n.t("One filter a line"));
        field.setText(list.savedValue());
        FrameLayout frame = new FrameLayout(field.getContext());
        int side = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                activity.getResources().getDisplayMetrics()));
        frame.setPaddingRelative(side, side / 2, side, 0);
        frame.addView(field, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(HushTelegramPreferenceFragment.themed(activity))
                .setTitle(title)
                .setMessage(L10n.t("Text on a line matches wherever it appears in a message. A line in slashes, like "
                        + "/crypto|airdrop/, is a regular expression. Case doesn't matter."))
                .setView(frame)
                .setPositiveButton(L10n.t("Save"), null)
                .setNegativeButton(L10n.t("Cancel"), null)
                .show();
        ScreenColors.dialog(dialog);
        // Set after show, so a list that can't be saved keeps the dialog and what was typed.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String typed = field.getText().toString();
            String refused = refusal(typed);
            if (refused != null) {
                Utils.showToastLong(refused);
                return;
            }
            List<String> lines = MessageFilters.lines(typed);
            if (!list.save(MessageFilters.join(lines))) {
                Logger.printInfo(() -> "Message filters not saved: " + list.key);
                Utils.showToastLong(L10n.t("Couldn't save the filters. Try again in a moment."));
                return;
            }
            row.setSummary(summary(list));
            Utils.showToastShort(L10n.t("Filters saved."));
            dialog.dismiss();
        });
    }
}
