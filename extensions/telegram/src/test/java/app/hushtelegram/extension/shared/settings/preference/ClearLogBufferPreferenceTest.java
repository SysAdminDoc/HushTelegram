package app.hushtelegram.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;

import app.hushtelegram.extension.shared.Utils;
import app.hushtelegram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushtelegram.extension.shared.diagnostics.HookStatus;
import app.hushtelegram.extension.shared.settings.BaseSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** The diagnostic clear row keeps a complete, one-tap way back. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class ClearLogBufferPreferenceTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        emptyEverythingAndDropUndo();
        LogBufferManager.clearedMessage = "Diagnostic data cleared. Tap again to put it back.";
        LogBufferManager.nothingToClearMessage = "There is no diagnostic data to clear";
        LogBufferManager.restoredMessage = "Diagnostic data put back";
        LogBufferManager.nothingToRestoreMessage = "There is no diagnostic data to put back";
        LogBufferManager.restoreFailedMessage =
                "Couldn't put back the diagnostic data. Try again.";
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        // A test that stopped part way can leave a row's work on the worker.
        settle();
        emptyEverythingAndDropUndo();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        // The sentences are static, so a later test class in the same sandbox would read these
        // in place of the catalog's.
        LogBufferManager.clearedMessage = null;
        LogBufferManager.nothingToClearMessage = null;
        LogBufferManager.restoredMessage = null;
        LogBufferManager.nothingToRestoreMessage = null;
        LogBufferManager.restoreFailedMessage = null;
        ShadowToast.reset();
    }

    @Test public void theNextTapRestoresEveryDiagnosticSurfaceAndKeepsNewFindings()
            throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                "BeforeClear", "INFO", "old buffered event");
        HookStatus.bound("comments", "like_button");
        HookStatus.missingViewId("comments", "missing_button");
        LogBufferManager.persistCrashReport(context, "old java crash");
        LogBufferManager.persistNpthCrashReport(context, "old native crash");

        ClearLogBufferPreference first = row();
        assertEquals("Clear diagnostic data", first.getTitle());
        tap(first);
        assertTrue(LogBufferManager.canUndoClear());
        assertEquals("Cleared summary", first.getSummary());
        assertEquals("Undo", first.getTitle());
        // A report still has the build's facts, and none of what was cleared.
        String cleared = LogBufferManager.buildExportText();
        assertFalse(cleared, cleared.contains("old buffered event"));
        assertFalse(cleared, cleared.contains("[LATEST JAVA CRASH]"));
        assertFalse(cleared, cleared.contains("[LATEST NATIVE CRASH SIGNAL]"));
        assertFalse(cleared, cleared.contains("[HOOK STATUS]"));
        assertFalse(HookStatus.anyMissing());
        assertEquals("", LogBufferManager.readCrashReport(context));
        assertEquals("", LogBufferManager.readNpthCrashReport(context));
        assertEquals("Diagnostic data cleared. Tap again to put it back.",
                ShadowToast.getTextOfLatestToast());

        // The row can be rebuilt while its offer survives. New evidence arriving meanwhile is
        // retained after the older data is put back in front of it.
        ClearLogBufferPreference rebuilt = row();
        assertEquals("Cleared summary", rebuilt.getSummary());
        assertEquals("Undo", rebuilt.getTitle());
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                "AfterClear", "INFO", "new buffered event");
        HookStatus.bound("inbox", "item_view");
        tap(rebuilt);

        assertFalse(LogBufferManager.canUndoClear());
        assertEquals("Clear summary", rebuilt.getSummary());
        assertEquals("Clear diagnostic data", rebuilt.getTitle());
        assertEquals("old java crash", LogBufferManager.readCrashReport(context));
        assertEquals("old native crash", LogBufferManager.readNpthCrashReport(context));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("old buffered event"));
        assertTrue(report, report.contains("new buffered event"));
        assertTrue(report, report.contains("comments: invoked 0, 1 found, 1 missing"));
        assertTrue(report, report.contains("inbox: invoked 0, 1 found, 0 missing"));

        // Restoring a raw miss key, rather than only its displayed sentence, keeps the normal
        // repeat guard working when the same hook runs again.
        HookStatus.missingViewId("comments", "missing_button");
        assertEquals(1, HookStatus.missing("comments").size());
        assertEquals("Diagnostic data put back", ShadowToast.getTextOfLatestToast());
    }

    @Test public void clearingNothingDoesNotPromiseAnUndoThatDoesNotExist() throws Exception {
        ClearLogBufferPreference preference = row();

        tap(preference);

        assertFalse(LogBufferManager.canUndoClear());
        assertEquals("Clear summary", preference.getSummary());
        assertEquals("Clear diagnostic data", preference.getTitle());
        assertEquals("There is no diagnostic data to clear",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void anOlderRowKeepsItsClearTitleAfterAnotherRowUsesUndo() throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER, "BeforeClear", "INFO", "old event");
        ClearLogBufferPreference first = row();
        tap(first);
        ClearLogBufferPreference rebuilt = row();
        tap(rebuilt);
        assertEquals("Undo", first.getTitle());

        tap(first);
        assertEquals("Undo", first.getTitle());
        tap(first);
        assertEquals("Clear diagnostic data", first.getTitle());
        assertEquals("Clear summary", first.getSummary());
    }

    @Test public void aFailedUndoKeepsTheUndoLabelAndCanBeRetried() throws Exception {
        LogBufferManager.persistCrashReport(context, "saved crash");
        ClearLogBufferPreference preference = row();
        tap(preference);
        Utils.setContext(new ContextWrapper(context) {
            @Override public File getFilesDir() { throw new SecurityException("files unavailable"); }
        });
        try {
            tap(preference);
            assertTrue(LogBufferManager.canUndoClear());
            assertEquals("Undo", preference.getTitle());
            assertEquals("Cleared summary", preference.getSummary());
        } finally {
            Utils.setContext(context);
        }
        tap(preference);
        assertEquals("Clear diagnostic data", preference.getTitle());
        assertEquals("saved crash", LogBufferManager.readCrashReport(context));
    }

    @Test public void anEventArrivingDuringClearStartsTheNewGeneration() throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                "BeforeClear", "INFO", "old event");
        BlockingFilesContext blocked = new BlockingFilesContext(context, 1);
        Utils.setContext(blocked);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread clear = thread("clear diagnostics", failure, LogBufferManager::clearLogBuffer);
        clear.start();
        assertTrue("clear never reached its snapshot boundary",
                blocked.blocked.await(2, TimeUnit.SECONDS));

        Thread producer = thread("record during clear", failure, () ->
                LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                        "DuringClear", "INFO", "new event"));
        producer.start();
        blocked.release.countDown();
        join(clear);
        join(producer);
        if (failure.get() != null) throw new AssertionError(failure.get());

        String afterClear = LogBufferManager.buildExportText();
        assertFalse(afterClear, afterClear.contains("old event"));
        assertTrue(afterClear, afterClear.contains("new event"));
        assertEquals(LogBufferManager.UndoResult.RESTORED, LogBufferManager.undoClear());
        String restored = LogBufferManager.buildExportText();
        assertTrue(restored, restored.contains("old event"));
        assertTrue(restored, restored.contains("new event"));
    }

    /**
     * Recording an event never waits on Clear or Undo. Both hold their lock while they reach the
     * crash files, and an event used to wait on that lock, so whatever thread logged it, the main
     * thread included, stood still for as long as the disk took.
     */
    @Test public void anEventIsRecordedWithoutWaitingOnClearOrUndo() throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER, "BeforeClear", "INFO", "old event");
        LogBufferManager.persistCrashReport(context, "old java crash");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            BlockingFilesContext clearing = new BlockingFilesContext(context, 1);
            Utils.setContext(clearing);
            Thread clear = thread("clear diagnostics", failure, LogBufferManager::clearLogBuffer);
            clear.start();
            assertTrue("clear never reached the crash files", clearing.blocked.await(2, TimeUnit.SECONDS));
            boolean waited = recordedLate(failure, "event during clear");
            clearing.release.countDown();
            join(clear);
            assertFalse("an event waited on Clear's disk work", waited);
            assertTrue(LogBufferManager.canUndoClear());

            BlockingFilesContext undoing = new BlockingFilesContext(context, 1);
            Utils.setContext(undoing);
            Thread undo = thread("restore diagnostics", failure, LogBufferManager::undoClear);
            undo.start();
            assertTrue("undo never reached the crash files", undoing.blocked.await(2, TimeUnit.SECONDS));
            waited = recordedLate(failure, "event during undo");
            undoing.release.countDown();
            join(undo);
            assertFalse("an event waited on Undo's disk work", waited);
        } finally {
            Utils.setContext(context);
        }
        if (failure.get() != null) throw new AssertionError(failure.get());

        assertFalse(LogBufferManager.canUndoClear());
        assertEquals("old java crash", LogBufferManager.readCrashReport(context));
        String report = LogBufferManager.buildExportText();
        for (String event : new String[]{"old event", "event during clear", "event during undo"}) {
            assertTrue(event, report.contains(event));
        }
    }

    /** Records [message] on a thread of its own, and says whether it was still waiting two seconds on. */
    private static boolean recordedLate(AtomicReference<Throwable> failure, String message) throws Exception {
        Thread producer = thread("record " + message, failure, () ->
                LogBufferManager.appendEvent(DiagnosticCategory.OTHER, "During", "INFO", message));
        producer.start();
        producer.join(TimeUnit.SECONDS.toMillis(2));
        return producer.isAlive();
    }

    @Test public void undoDoesNotOverwriteACrashSavedWhileItRestores() throws Exception {
        LogBufferManager.persistCrashReport(context, "cleared crash");
        LogBufferManager.clearLogBuffer();
        assertTrue(LogBufferManager.canUndoClear());

        BlockingFilesContext blocked = new BlockingFilesContext(context, 2);
        Utils.setContext(blocked);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread undo = thread("restore diagnostics", failure, LogBufferManager::undoClear);
        undo.start();
        assertTrue("undo never reached its crash write boundary",
                blocked.blocked.await(2, TimeUnit.SECONDS));

        Thread producer = thread("save newer crash", failure,
                () -> LogBufferManager.persistCrashReport(blocked, "newer crash"));
        producer.start();
        // In the broken implementation the producer reaches the file while Undo is paused
        // between its check and write. Wait for that path before releasing the boundary so the
        // final value proves which write won. In the fixed implementation it waits on the
        // diagnostic generation lock and reaches the file only after Undo.
        if (blocked.laterCall.await(300, TimeUnit.MILLISECONDS)) join(producer);
        blocked.release.countDown();
        join(undo);
        join(producer);
        if (failure.get() != null) throw new AssertionError(failure.get());

        assertEquals("newer crash", LogBufferManager.readCrashReport(context));
    }

    /**
     * With both crash reports saved, the row's Clear reads and deletes them and its Undo writes
     * them back, and every one of those file lookups is made off the main thread. Each used to be
     * made inside the tap.
     */
    @Test public void clearAndUndoReachTheCrashFilesOnlyOffTheMainThread() throws Exception {
        LogBufferManager.persistCrashReport(context, "old java crash");
        LogBufferManager.persistNpthCrashReport(context, "old native crash");
        List<Thread> lookups = Collections.synchronizedList(new ArrayList<>());
        Utils.setContext(new ContextWrapper(context) {
            @Override public File getFilesDir() {
                lookups.add(Thread.currentThread());
                return super.getFilesDir();
            }
        });
        try {
            ClearLogBufferPreference preference = row();
            tap(preference);
            assertEquals("Undo", preference.getTitle());
            assertEquals("", LogBufferManager.readCrashReport(context));
            assertEquals("", LogBufferManager.readNpthCrashReport(context));
            int cleared = lookups.size();
            assertTrue("Clear never reached the crash files", cleared > 0);

            tap(preference);
            assertEquals("Clear diagnostic data", preference.getTitle());
            assertEquals("old java crash", LogBufferManager.readCrashReport(context));
            assertEquals("old native crash", LogBufferManager.readNpthCrashReport(context));
            assertTrue("Undo never reached the crash files", lookups.size() > cleared);
            assertFalse("a crash file was reached on the main thread",
                    lookups.contains(Looper.getMainLooper().getThread()));
        } finally {
            Utils.setContext(context);
        }
    }

    /**
     * Storage that's slow to answer holds the worker, not the screen. The tap comes straight back
     * and the row waits out of reach, so a second tap starts nothing. A row built meanwhile, as a
     * page drawn again builds one, asks what it can do without waiting and waits with the first.
     * Both show Undo once the Clear is done.
     */
    @Test public void slowStorageKeepsTheRowWaitingAndTheScreenFree() throws Exception {
        LogBufferManager.persistCrashReport(context, "old java crash");
        BlockingFilesContext blocked = new BlockingFilesContext(context, 1);
        Utils.setContext(blocked);
        try {
            ClearLogBufferPreference preference = row();
            assertTrue(preference.getOnPreferenceClickListener().onPreferenceClick(preference));
            assertTrue("Clear never reached the crash files", blocked.blocked.await(2, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the row doesn't say its work is out", preference.isBusy());
            assertFalse("a row with its work out takes a tap", preference.isEnabled());
            assertEquals("Clear diagnostic data", preference.getTitle());

            // Queued behind the Clear, a second tap would find its Undo and put everything back.
            preference.getOnPreferenceClickListener().onPreferenceClick(preference);

            ClearLogBufferPreference rebuilt = row();
            assertTrue("a row built while the work is out doesn't wait for it", rebuilt.isBusy());
            assertFalse(rebuilt.isEnabled());
            assertEquals("Clear diagnostic data", rebuilt.getTitle());

            blocked.release.countDown();
            settle();
            for (ClearLogBufferPreference row : new ClearLogBufferPreference[]{preference, rebuilt}) {
                assertFalse("the row still says its work is out", row.isBusy());
                assertTrue("the row stayed out of reach", row.isEnabled());
                assertEquals("Undo", row.getTitle());
                assertEquals("Cleared summary", row.getSummary());
            }
            assertTrue("the second tap undid the first", LogBufferManager.canUndoClear());
            assertEquals("", LogBufferManager.readCrashReport(context));
        } finally {
            blocked.release.countDown();
            Utils.setContext(context);
        }
    }

    /**
     * A row whose page went while its work was out isn't redrawn: the Clear still lands, and the
     * row a new page builds offers the Undo. A tap that reaches the old row afterwards starts
     * nothing.
     */
    @Test public void aRowWhosePageWentIsNotRedrawnAndTakesNoTap() throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER, "BeforeClear", "INFO", "old event");
        AtomicBoolean owned = new AtomicBoolean(true);
        ClearLogBufferPreference preference = new ClearLogBufferPreference(context) {
            @Override protected boolean isOwned() { return owned.get(); }
        };
        preference.setTitle("Clear diagnostic data");
        preference.setClearAndUndoSummaries("Clear summary", "Cleared summary");

        BlockingFilesContext blocked = new BlockingFilesContext(context, 1);
        Utils.setContext(blocked);
        try {
            preference.getOnPreferenceClickListener().onPreferenceClick(preference);
            assertTrue("Clear never reached the crash files", blocked.blocked.await(2, TimeUnit.SECONDS));
            owned.set(false);
            blocked.release.countDown();
            settle();
        } finally {
            blocked.release.countDown();
            Utils.setContext(context);
        }
        assertTrue("the Clear a page left behind is lost", LogBufferManager.canUndoClear());
        assertEquals("a row whose page went was redrawn", "Clear diagnostic data", preference.getTitle());
        assertEquals("Clear summary", preference.getSummary());
        assertEquals("Undo", row().getTitle());

        tap(preference);
        assertTrue("a row whose page went took a tap", LogBufferManager.canUndoClear());
        String report = LogBufferManager.buildExportText();
        assertFalse(report, report.contains("old event"));
    }

    /**
     * The worker takes 32 tasks behind the one it's on. A tap past that starts nothing and says so,
     * and the row is back in reach at once with what it offered.
     */
    @Test public void aTapTheWorkerHasNoRoomForSaysSoAndLeavesTheRowInReach() throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER, "BeforeClear", "INFO", "old event");
        ClearLogBufferPreference preference = row();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger unfinished = new AtomicInteger();
        Runnable filler = () -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                unfinished.decrementAndGet();
            }
        };
        try {
            // The worker has to be on the first one before the rest can fill everything behind it.
            unfinished.incrementAndGet();
            assertTrue(Utils.runInOrderOnBackgroundThread(filler));
            assertTrue("the worker never started", started.await(5, TimeUnit.SECONDS));
            while (true) {
                unfinished.incrementAndGet();
                if (!Utils.runInOrderOnBackgroundThread(filler)) break;
                assertTrue("the worker never ran out of room", unfinished.get() < 100);
            }
            unfinished.decrementAndGet();

            assertTrue(preference.getOnPreferenceClickListener().onPreferenceClick(preference));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Couldn't start that. Try again in a moment.", ShadowToast.getTextOfLatestToast());
            assertFalse("a tap that started nothing left the row waiting", preference.isBusy());
            assertTrue("a tap that started nothing left the row out of reach", preference.isEnabled());
            assertEquals("Clear diagnostic data", preference.getTitle());
            assertFalse(LogBufferManager.canUndoClear());
        } finally {
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (unfinished.get() > 0 && System.nanoTime() < deadline) Thread.sleep(5);
        }
        assertEquals("the worker never caught up", 0, unfinished.get());

        tap(preference);
        assertEquals("Undo", preference.getTitle());
    }

    private ClearLogBufferPreference row() {
        ClearLogBufferPreference preference = new ClearLogBufferPreference(context);
        preference.setTitle("Clear diagnostic data");
        preference.setClearAndUndoSummaries("Clear summary", "Cleared summary");
        return preference;
    }

    /** Taps [row] and waits for its work: Clear or Undo on the worker, then the row's answer on the main thread. */
    private static void tap(ClearLogBufferPreference row) throws Exception {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        settle();
    }

    private static void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void emptyEverythingAndDropUndo() {
        // The second empty clear replaces the first clear's snapshot with nothing.
        LogBufferManager.clearLogBuffer();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    private static Thread thread(
            String name,
            AtomicReference<Throwable> failure,
            ThrowingRunnable action
    ) {
        return new Thread(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, name);
    }

    private static void join(Thread thread) throws Exception {
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(thread.getName() + " did not finish", thread.isAlive());
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Pauses one exact file lookup so a producer can be placed on the other side of Clear. */
    private static final class BlockingFilesContext extends ContextWrapper {
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch laterCall = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final int blockAt;
        private final AtomicInteger calls = new AtomicInteger();

        BlockingFilesContext(Context base, int blockAt) {
            super(base);
            this.blockAt = blockAt;
        }

        @Override public File getFilesDir() {
            int call = calls.incrementAndGet();
            if (call > blockAt) laterCall.countDown();
            if (call == blockAt) {
                blocked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("file boundary was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return super.getFilesDir();
        }
    }
}
