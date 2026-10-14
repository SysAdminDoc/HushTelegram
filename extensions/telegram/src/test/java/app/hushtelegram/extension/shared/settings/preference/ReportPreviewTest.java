/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import app.hushtelegram.extension.shared.SettingsContextRule;
import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.WorkerPoolForTests;
import app.hushtelegram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.BaseSettings;

/**
 * Preview the report: the same redacted report Copy and Save make, any part of it left out before
 * it's shared, and nothing copied or saved until one of those is tapped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ReportPreviewTest {
    private static final String SECRET_PART = "probe line only in the PROBE part";
    private static final String EVENT = "event only in the events part";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        LogBufferManager.clearReportSectionsForTests();
        HookStatus.missingViewId("Hide ads", "jlk");
        LogBufferManager.registerReportSection(new LogBufferManager.ReportSection() {
            @Override public String title() {
                return "PROBE";
            }

            @Override public List<String> lines() {
                return java.util.Collections.singletonList(SECRET_PART);
            }
        });
        LogBufferManager.appendEvent(DiagnosticCategory.FEED, "Probe", "INFO", EVENT);
        clearClipboard();
        for (File saved : savedFiles()) assertTrue(saved.delete());
    }

    @After public void tearDown() {
        LogBufferManager.clearReportSectionsForTests();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
    }

    @Test public void withEveryPartKeptThePreviewIsTheSavedReport() throws Exception {
        Object[] built = build();
        LogBufferManager.Preview preview = (LogBufferManager.Preview) built[0];
        assertEquals(Arrays.asList("HOOK STATUS", "PROBE", LogBufferManager.EVENTS_TITLE), preview.parts());
        assertEquals(timeless(LogBufferManager.buildExportText()), timeless((String) built[1]));
        assertEquals(built[1], preview.text(new boolean[]{true, true, true}));
    }

    @Test public void aPartLeftOutIsInNoCopyAndNoFile() throws Exception {
        LogBufferManager.Preview preview = (LogBufferManager.Preview) build()[0];
        boolean[] kept = {true, false, false};
        String text = preview.text(kept);
        assertTrue(text, text.contains("[HOOK STATUS]"));
        assertTrue(text, text.startsWith("MORPHE DIAGNOSTIC REPORT\n"));
        for (String gone : new String[]{"[PROBE]", SECRET_PART, "[SELECTED EVENTS]", EVENT}) {
            assertFalse(gone + " stayed in: " + text, text.contains(gone));
        }

        LogBufferManager.copyPreview(preview, kept, null);
        // Changing the boxes after Copy was tapped doesn't change what it copies.
        kept[1] = true;
        settle();
        String copied = clipboard();
        assertNotNull("nothing was copied", copied);
        assertFalse(copied, copied.contains(SECRET_PART));
        assertFalse(copied, copied.contains(EVENT));
        assertTrue(copied, copied.contains("[HOOK STATUS]"));

        LogBufferManager.savePreview(preview, new boolean[]{true, false, true}, null);
        settle();
        String saved = savedReport();
        assertFalse(saved, saved.contains(SECRET_PART));
        assertTrue(saved, saved.contains(EVENT));
    }

    @Test public void theRowShowsThePreviewAndCancelLeavesNothingBehind() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ExportDiagnosticReportPreference row = new ExportDiagnosticReportPreference(controller.get());
            ViewGroup content = openPreview(row);
            List<CheckBox> boxes = boxes(content);
            assertEquals(3, boxes.size());
            assertEquals("[PROBE]", boxes.get(1).getText().toString());
            TextView body = body(content);
            assertTrue(body.getText().toString().contains(SECRET_PART));
            assertEquals("the report reads left to right in any language", View.TEXT_DIRECTION_LTR, body.getTextDirection());

            // Unticked, the part goes from the preview once it's worked out on the worker.
            boxes.get(1).setChecked(false);
            assertTrue("drawn before the worker ran", body.getText().toString().contains(SECRET_PART));
            settle();
            assertFalse(body.getText().toString().contains(SECRET_PART));
            assertTrue(body.getText().toString().contains(EVENT));

            AlertDialog preview = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
            preview.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            settle();
            assertNull("Cancel copied something", clipboard());
            assertEquals("Cancel saved something", 0, savedFiles().length);
            assertFalse(row.isBusy());
        }
    }

    @Test public void copyFromTheRowTakesOnlyWhatsTicked() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ExportDiagnosticReportPreference row = new ExportDiagnosticReportPreference(controller.get());
            ViewGroup content = openPreview(row);
            boxes(content).get(2).setChecked(false);
            settle();
            AlertDialog preview = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
            preview.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            // A dialog button hands its tap to the main looper. Run just that, so the copy the
            // worker posts back hasn't landed yet.
            ShadowLooper.runMainLooperOneTask();
            assertTrue("the row takes no second tap while it copies", row.isBusy());
            settle();
            assertFalse(row.isBusy());
            String copied = clipboard();
            assertNotNull(copied);
            assertTrue(copied, copied.contains(SECRET_PART));
            assertFalse(copied, copied.contains(EVENT));
            assertEquals(0, savedFiles().length);
        }
    }

    /** A redraw that comes back after its dialog closed draws nothing. */
    @Test public void aRedrawForAClosedPreviewIsDropped() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ExportDiagnosticReportPreference row = new ExportDiagnosticReportPreference(controller.get());
            ViewGroup content = openPreview(row);
            TextView body = body(content);
            boxes(content).get(1).setChecked(false);
            ((AlertDialog) ShadowAlertDialog.getLatestAlertDialog()).dismiss();
            settle();
            assertTrue(body.getText().toString().contains(SECRET_PART));
        }
    }

    /** A redraw that comes back after the page went draws nothing. */
    @Test public void aRedrawForAPageThatWentIsDropped() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            boolean[] owned = {true};
            ExportDiagnosticReportPreference row = new ExportDiagnosticReportPreference(controller.get()) {
                @Override protected boolean canStartExport() {
                    return owned[0];
                }
            };
            ViewGroup content = openPreview(row);
            TextView body = body(content);
            boxes(content).get(1).setChecked(false);
            owned[0] = false;
            settle();
            assertTrue(body.getText().toString().contains(SECRET_PART));
        }
    }

    /** With no worker free, the preview says it couldn't follow the box, and Save still does. */
    @Test public void aRedrawThatCantStartSaysSoAndSaveStillFollowsTheBoxes() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ExportDiagnosticReportPreference row = new ExportDiagnosticReportPreference(controller.get());
            ViewGroup content = openPreview(row);
            TextView body = body(content);
            TextView note = note(content);
            assertEquals(View.GONE, note.getVisibility());
            try (WorkerPoolForTests full = WorkerPoolForTests.fill()) {
                boxes(content).get(1).setChecked(false);
            }
            assertEquals(View.VISIBLE, note.getVisibility());
            assertEquals("Couldn't update the preview. Copy and Save still use only what's ticked.", note.getText().toString());
            assertTrue("the old text stays", body.getText().toString().contains(SECRET_PART));

            ((AlertDialog) ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            String saved = savedReport();
            assertFalse(saved, saved.contains(SECRET_PART));
            assertTrue(saved, saved.contains(EVENT));
        }
    }

    /** A redraw whose worker fails says so, but only while it's the newest choice. */
    @Test public void aRedrawThatFailsSaysSoOnlyWhileItsTheNewest() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ExportDiagnosticReportPreference row = new ExportDiagnosticReportPreference(controller.get());
            ViewGroup content = openPreview(row);
            TextView body = body(content);
            TextView note = note(content);
            ExportDiagnosticReportPreference.failNextRedraw = new IllegalStateException("probe");
            boxes(content).get(1).setChecked(false);
            settle();
            assertEquals(View.VISIBLE, note.getVisibility());
            assertEquals("Couldn't update the preview. Copy and Save still use only what's ticked.", note.getText().toString());
            assertTrue("the old text stays", body.getText().toString().contains(SECRET_PART));

            // A redraw that works clears the note. Box 0 goes back on, so the ticks are as they were.
            boxes(content).get(0).setChecked(false);
            settle();
            assertEquals(View.GONE, note.getVisibility());
            boxes(content).get(0).setChecked(true);
            settle();

            // A failure overtaken by a newer choice says nothing; the newer one draws.
            ExportDiagnosticReportPreference.failNextRedraw = new IllegalStateException("probe");
            boxes(content).get(2).setChecked(false);
            // Its worker fails and posts back, but the main thread hasn't run that yet when the next box changes.
            Utils.awaitBackgroundTasksForTests();
            CountDownLatch newer = new CountDownLatch(1);
            ExportDiagnosticReportPreference.holdNextRedraw = newer;
            boxes(content).get(0).setChecked(false);
            // The overtaken failure's post runs while the newer choice is still working, so nothing
            // drawn since can hide a note it shouldn't have left.
            ShadowLooper.idleMainLooper();
            assertEquals(View.GONE, note.getVisibility());
            newer.countDown();
            settle();
            assertEquals(View.GONE, note.getVisibility());
            assertFalse(body.getText().toString(), body.getText().toString().contains(SECRET_PART));
            assertFalse(body.getText().toString(), body.getText().toString().contains(EVENT));
        } finally {
            ExportDiagnosticReportPreference.failNextRedraw = null;
            CountDownLatch held = ExportDiagnosticReportPreference.holdNextRedraw;
            ExportDiagnosticReportPreference.holdNextRedraw = null;
            if (held != null) held.countDown();
        }
    }

    /** A file with a part left out isn't called the full report. */
    @Test public void saveFromTheRowLeavesOutWhatsUntickedAndSaysSo() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ExportDiagnosticReportPreference row = new ExportDiagnosticReportPreference(controller.get());
            ViewGroup content = openPreview(row);
            boxes(content).get(1).setChecked(false);
            settle();
            ((AlertDialog) ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertFalse(row.isBusy());
            String saved = savedReport();
            assertFalse(saved, saved.contains(SECRET_PART));
            assertFalse(saved, saved.contains("[PROBE]"));
            assertTrue(saved, saved.contains(EVENT));
            String toast = String.valueOf(ShadowToast.getTextOfLatestToast());
            assertTrue(toast, toast.startsWith("Report saved to "));
            assertNull("Save copied something", clipboard());
        }
    }

    /** A letter written as two chars is never cut in half at the end of what's shown. */
    @Test public void thePreviewNeverEndsHalfwayThroughALetter() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i < ExportDiagnosticReportPreference.PREVIEW_MAX_CHARS; i++) text.append('a');
        text.append("😀tail");
        TextView body = new TextView(context);
        TextView note = new TextView(context);
        ExportDiagnosticReportPreference.bind(context, body, note, text.toString());
        assertEquals(ExportDiagnosticReportPreference.PREVIEW_MAX_CHARS - 1, body.getText().length());
        assertFalse(Character.isHighSurrogate(body.getText().charAt(body.getText().length() - 1)));
        assertTrue(note.getText().toString(), note.getText().toString().startsWith("Showing the first 29,999 of 30,005 "));
    }

    /** The counts read the way the phone's language writes numbers. */
    @Test @Config(qualifiers = "de") public void theCountsUseThePhonesNumbers() {
        // Robolectric sets the JVM default from the qualifiers too; with it English, only the phone's
        // language can give German grouping.
        Locale jvm = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < ExportDiagnosticReportPreference.PREVIEW_MAX_CHARS + 10; i++) text.append('a');
            TextView note = new TextView(context);
            ExportDiagnosticReportPreference.bind(context, new TextView(context), note, text.toString());
            assertTrue(note.getText().toString(), note.getText().toString().contains("30.000")
                    && note.getText().toString().contains("30.010"));
        } finally {
            Locale.setDefault(jvm);
        }
    }

    /** A page gone while the report was built gets no preview. */
    @Test public void aPreviewForAPageThatWentIsNotShown() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            boolean[] owned = {true};
            ExportDiagnosticReportPreference row = new ExportDiagnosticReportPreference(controller.get()) {
                @Override protected boolean canStartExport() {
                    return owned[0];
                }
            };
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            AlertDialog choices = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
            Shadows.shadowOf(choices).clickOnItem(2);
            owned[0] = false;
            settle();
            assertFalse(row.isBusy());
            assertNull("a preview opened for a page that had gone", previewContent());
        }
    }

    @Test public void aLongReportIsShownInPartAndSaysSo() throws Exception {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 100; i++) line.append('x');
        for (int i = 0; i < 1_000; i++) LogBufferManager.appendEvent(DiagnosticCategory.FEED, "Probe", "INFO", line + " " + i);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ExportDiagnosticReportPreference row = new ExportDiagnosticReportPreference(controller.get());
            ViewGroup content = openPreview(row);
            TextView body = body(content);
            assertEquals(ExportDiagnosticReportPreference.PREVIEW_MAX_CHARS, body.getText().length());
            TextView note = note(content);
            assertEquals(View.VISIBLE, note.getVisibility());
            assertTrue(note.getText().toString(), note.getText().toString().startsWith("Showing the first 30,000 of "));

            // Save still writes all of it, and with every part ticked it's the full report.
            ((AlertDialog) ShadowAlertDialog.getLatestAlertDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertTrue(savedReport(), savedReport().contains(line + " 999"));
            String toast = String.valueOf(ShadowToast.getTextOfLatestToast());
            assertTrue(toast, toast.startsWith("Full report saved to "));
        }
    }

    private Object[] build() throws Exception {
        Object[] built = new Object[2];
        LogBufferManager.preview((preview, text) -> {
            built[0] = preview;
            built[1] = text;
        });
        settle();
        assertNotNull("no preview was built", built[0]);
        return built;
    }

    private ViewGroup openPreview(ExportDiagnosticReportPreference row) throws Exception {
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        AlertDialog choices = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
        assertEquals(3, choices.getListView().getAdapter().getCount());
        Shadows.shadowOf(choices).clickOnItem(2);
        assertTrue(row.isBusy());
        settle();
        assertFalse(row.isBusy());
        ViewGroup content = previewContent();
        assertNotNull("the preview didn't open", content);
        return content;
    }

    private static ViewGroup previewContent() {
        AlertDialog dialog = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
        if (dialog == null || dialog.getWindow() == null) return null;
        return (ViewGroup) dialog.getWindow().getDecorView().findViewWithTag(ExportDiagnosticReportPreference.PREVIEW_TAG);
    }

    private static List<CheckBox> boxes(ViewGroup content) {
        List<CheckBox> boxes = new ArrayList<>();
        for (int i = 0; i < content.getChildCount(); i++) {
            if (content.getChildAt(i) instanceof CheckBox) boxes.add((CheckBox) content.getChildAt(i));
        }
        return boxes;
    }

    private static TextView body(ViewGroup content) {
        return (TextView) content.getChildAt(content.getChildCount() - 1);
    }

    private static TextView note(ViewGroup content) {
        return (TextView) content.getChildAt(content.getChildCount() - 2);
    }

    /** Worker, then the main thread, twice over, for a worker that hands back to the main thread. */
    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
        }
    }

    private static String timeless(String report) {
        return report.replaceFirst("generated_utc: [^\n]*\n", "generated_utc: -\n");
    }

    private void clearClipboard() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.clearPrimaryClip();
    }

    private String clipboard() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        return clip == null || clip.getItemCount() == 0 ? null : String.valueOf(clip.getItemAt(0).getText());
    }

    private File[] savedFiles() {
        File folder = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Morphe");
        File[] files = folder.listFiles();
        return files == null ? new File[0] : files;
    }

    private String savedReport() throws Exception {
        File[] files = savedFiles();
        assertEquals("one saved report", 1, files.length);
        return new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
    }
}
