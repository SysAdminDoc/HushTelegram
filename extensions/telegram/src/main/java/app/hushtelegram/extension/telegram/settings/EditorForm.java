/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.settings;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A scrollable dialog body with persistent labels and a readable validation result. */
final class EditorForm extends ScrollView {
    static final String ERROR = "hushtelegram_editor_error";
    static final String INVALID = "hushtelegram_editor_invalid";
    private final LinearLayout fields;
    private final TextView error;
    private EditText invalid;

    EditorForm(Context context, String instructions) {
        super(context);
        setFillViewport(false);
        setVerticalScrollBarEnabled(true);
        setScrollbarFadingEnabled(false);
        fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPaddingRelative(dp(20), dp(8), dp(20), dp(12));
        addView(fields, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        TextView note = new TextView(context);
        note.setText(instructions);
        note.setTextColor(ScreenColors.DEFAULT.summary);
        note.setTextSize(14);
        note.setLineSpacing(dp(2), 1);
        note.setPadding(0, 0, 0, dp(16));
        fields.addView(note);
        error = new TextView(context);
        error.setTag(ERROR);
        error.setTextColor(ScreenColors.DEFAULT.error);
        error.setTextSize(14);
        error.setPadding(0, dp(8), 0, 0);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(GONE);
        fields.addView(error);
    }

    void add(EditText field, String label) {
        TextView name = new TextView(getContext());
        name.setText(label);
        name.setTextColor(ScreenColors.DEFAULT.summary);
        name.setTextSize(14);
        name.setLabelFor(field.getId());
        name.setPadding(0, dp(8), 0, dp(6));
        int last = fields.getChildCount() - 1;
        fields.addView(name, last);
        fields.addView(field, last + 1, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        field.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (invalid == field) {
                    info.setContentInvalid(true);
                    info.setError(error.getText());
                }
            }
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (invalid != field) return;
                field.setTag(null);
                invalid = null;
                error.setText("");
                error.setVisibility(GONE);
                ScreenColors.DEFAULT.paintField(field);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    void refuse(EditText field, String reason) {
        if (invalid != null && invalid != field) {
            invalid.setTag(null);
            ScreenColors.DEFAULT.paintField(invalid);
        }
        invalid = field;
        fields.removeView(error);
        fields.addView(error, fields.indexOfChild(field) + 1);
        error.setText(reason);
        error.setVisibility(VISIBLE);
        field.setTag(INVALID);
        field.requestFocus();
        ScreenColors.DEFAULT.paintField(field);
        error.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                error.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (invalid == field && error.getVisibility() == VISIBLE) {
                    error.requestRectangleOnScreen(new android.graphics.Rect(0, 0, error.getWidth(), error.getHeight()), true);
                }
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
