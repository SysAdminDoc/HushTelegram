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
import android.widget.EditText;
import android.widget.LinearLayout;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.telegram.misc.AiTranslate;
import app.hushtelegram.extension.telegram.misc.OutsideTranslate;

/**
 * The editor for the outside translate's service: Google's web translate, or an AI service with
 * the person's own key. Save checks the address, model and key first and keeps the dialog open on
 * one that can't be used. Use Google forgets the key.
 */
final class TranslateServiceEditor {
    static final String KEY = "action_translate_service";

    private TranslateServiceEditor() {}

    /** What the row says under its title: which service the text goes to. */
    static String summary(AiTranslate.Service service) {
        return service.enabled()
                ? L10n.f("Your AI service at %1$s, model %2$s.", service.host(), service.model)
                : L10n.t("Google's web translate. Tap to use an AI service with your own key.");
    }

    /** Opens the editor over the settings page. The row's summary follows what's saved. */
    static void open(Activity activity, Preference row) {
        AiTranslate.Service saved = OutsideTranslate.service();
        EditText address = field(activity, android.R.id.text1, L10n.t("Service address"), saved.address,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText model = field(activity, android.R.id.text2, L10n.t("Model"), saved.model,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText key = field(activity, android.R.id.edit, L10n.t("API key"), saved.key,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout fields = new LinearLayout(address.getContext());
        fields.setOrientation(LinearLayout.VERTICAL);
        int side = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                activity.getResources().getDisplayMetrics()));
        fields.setPaddingRelative(side, side / 2, side, 0);
        for (EditText field : new EditText[]{address, model, key}) {
            fields.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        AlertDialog dialog = new AlertDialog.Builder(HushTelegramPreferenceFragment.themed(activity))
                .setTitle(L10n.t("Translation service"))
                .setMessage(L10n.t("With a key, the text you translate goes to this AI service instead of Google. Any "
                        + "service that takes OpenAI's chat format works, like OpenAI, OpenRouter, DeepSeek or Groq. "
                        + "The key stays on this phone and never goes in a settings file."))
                .setView(fields)
                .setPositiveButton(L10n.t("Save"), null)
                .setNeutralButton(L10n.t("Use Google"), null)
                .setNegativeButton(L10n.t("Cancel"), null)
                .show();
        ScreenColors.dialog(dialog);
        // Set after show, so a service that can't be saved keeps the dialog and what was typed.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = address.getText().toString(), m = model.getText().toString(), k = key.getText().toString();
            String refused = AiTranslate.refusal(a, m, k);
            if (refused != null) {
                Utils.showToastLong(refused);
                return;
            }
            if (!OutsideTranslate.saveService(a, m, k)) {
                Utils.showToastLong(L10n.t("Couldn't save the translation service. Try again in a moment."));
                return;
            }
            row.setSummary(summary(OutsideTranslate.service()));
            Utils.showToastShort(L10n.t("Translation service saved."));
            dialog.dismiss();
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (!OutsideTranslate.saveService(address.getText().toString(), model.getText().toString(), "")) {
                Utils.showToastLong(L10n.t("Couldn't save the translation service. Try again in a moment."));
                return;
            }
            row.setSummary(summary(OutsideTranslate.service()));
            Utils.showToastShort(L10n.t("Back to Google's web translate."));
            dialog.dismiss();
        });
    }

    private static EditText field(Activity activity, int id, String hint, String text, int type) {
        EditText field = new EditText(HushTelegramPreferenceFragment.themed(activity));
        field.setId(id);
        field.setInputType(type);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setText(text);
        return field;
    }
}
