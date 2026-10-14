/*
 * Forked from https://github.com/SysAdminDoc/HushThreads at b141524 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at c15d4f79 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Forked from MorpheApp/morphe-patches (GPL-3.0), by way of
 * icysymmetra/tiktok-patches-for-morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Imported carrying no notice of its own. Morphe hard forked ReVanced, so parts of
 * this file may originate there.
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.hushtelegram.extension.shared.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushtelegram.extension.shared.L10n;
import app.hushtelegram.extension.shared.Utils;

/**
 * A custom preference that clears the Morphe debug log buffer when clicked, and puts it back when
 * clicked again. Both read, delete or write the crash files, so the tap hands the work to the
 * ordered worker ({@link LogBufferManager#clearLogBuffer}, {@link LogBufferManager#undoClear}) and
 * the row waits out of reach until it's done.
 */
@SuppressWarnings({"unused", "deprecation"})
public class ClearLogBufferPreference extends Preference implements ImmediateAction {
    @Override public boolean actsOnTap() { return true; }

    /** The rows waiting on a tap's work: the one tapped, and any built while it was out. Main thread only. */
    private static final Set<ClearLogBufferPreference> WAITING = Collections.newSetFromMap(new WeakHashMap<>());
    /** How many taps' work is still out. Main thread only. */
    private static int pending;

    private CharSequence clearTitle;
    private CharSequence clearSummary;
    private CharSequence undoSummary = L10n.t("Diagnostic data cleared. Tap again to put it back.");
    private boolean busy;

    {
        // A key so the settings search can index this row. Nothing in the settings
        // framework treats it as a setting: a key with no Setting behind it is skipped.
        setKey("action_clear_diagnostic_data");
        setOnPreferenceClickListener(pref -> {
            if (busy || !isOwned()) return true;
            if (!LogBufferManager.canUndoClear()) {
                if (clearSummary == null) clearSummary = getSummary();
                if (clearTitle == null) clearTitle = getTitle();
            }
            pending++;
            showBusy();
            // The worker picks Undo or Clear when the tap's turn comes, so taps on two rows act
            // in the order they were made, as they did when each ran inside its tap.
            boolean started = Utils.runInOrderOnBackgroundThread(() -> {
                try {
                    if (LogBufferManager.canUndoClear()) LogBufferManager.undoClear();
                    else LogBufferManager.clearLogBuffer();
                } finally {
                    Utils.runOnMainThread(ClearLogBufferPreference::finished);
                }
            });
            if (!started) {
                Utils.showToastLong(L10n.t("Couldn't start that. Try again in a moment."));
                finished();
            }
            return true;
        });
        // Built while a tap's work is out, on a page that replaced the one tapped: this row waits
        // with it, and shows what the work ended as.
        if (pending > 0) showBusy();
    }

    /** A host can say the settings view that owns this row is gone: its tap starts nothing, and it's shown nothing. */
    protected boolean isOwned() {
        return true;
    }

    /** Whether a tap's work this row waits on is still out. */
    public boolean isBusy() {
        return busy;
    }

    private void showBusy() {
        busy = true;
        WAITING.add(this);
        setEnabled(false);
    }

    /**
     * One tap's work is done, however it ended. Once none is out, each waiting row shows what its
     * next tap does: a failed Undo leaves that as Undo, to try again.
     */
    private static void finished() {
        if (--pending > 0) return;
        for (ClearLogBufferPreference row : new ArrayList<>(WAITING)) {
            row.busy = false;
            // A row whose page went while it waited is drawn nowhere.
            if (!row.isOwned()) continue;
            row.setEnabled(true);
            row.refreshAction();
        }
        WAITING.clear();
    }

    /** Supplies the two row summaries in the bundle's language. */
    public final void setClearAndUndoSummaries(CharSequence clearSummary, CharSequence undoSummary) {
        this.clearSummary = clearSummary;
        this.undoSummary = undoSummary;
        if (clearTitle == null) clearTitle = getTitle();
        refreshAction();
    }

    private void refreshAction() {
        boolean undo = LogBufferManager.canUndoClear();
        setTitle(undo ? L10n.t(getContext(), "Undo") : clearTitle);
        setSummary(undo ? undoSummary : clearSummary);
    }

    public ClearLogBufferPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public ClearLogBufferPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public ClearLogBufferPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public ClearLogBufferPreference(Context context) {
        super(context);
    }
}
