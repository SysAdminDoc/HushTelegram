/*
 * Forked from https://github.com/SysAdminDoc/HushThreads at b141524 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at c15d4f79 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.hushtelegram.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.hushtelegram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushtelegram.extension.shared.settings.preference.LogBufferManager;
import app.hushtelegram.extension.shared.SettingsContextRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class UtilsSchedulingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void setUp() {
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        LogBufferManager.clearLogBuffer();
    }

    @Test
    public void aBackgroundErrorIsLoggedAndThePoolKeepsRunning() throws Exception {
        AtomicBoolean laterTaskRan = new AtomicBoolean();
        Utils.runOnBackgroundThread(() -> { throw new AssertionError("background sentinel"); });
        Utils.runOnBackgroundThread(() -> laterTaskRan.set(true));
        Utils.awaitBackgroundTasksForTests();

        assertTrue(laterTaskRan.get());
        assertTrue(LogBufferManager.buildExportText().contains("background sentinel"));
    }

    /**
     * The ordered worker runs one task at a time, in the order they were handed over, and refuses
     * the one its queue has no room for. The first is held here, so a pool with a free thread would
     * have run the rest ahead of it.
     */
    @Test
    public void orderedTasksRunOneAtATimeInTheOrderHandedOver() throws Exception {
        List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertTrue(Utils.runInOrderOnBackgroundThread(() -> {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                ran.add(0);
            }));
            assertTrue("the worker never started", started.await(5, TimeUnit.SECONDS));
            int handed = 1;
            while (true) {
                int index = handed;
                if (!Utils.runInOrderOnBackgroundThread(() -> ran.add(index))) break;
                assertTrue("the worker never ran out of room", ++handed < 100);
            }
            assertEquals("the queue behind the running task holds 32", 33, handed);
            assertTrue("a later task ran ahead of the one before it", ran.isEmpty());

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (ran.size() < handed && System.nanoTime() < deadline) Thread.sleep(5);
            Utils.awaitBackgroundTasksForTests();
            List<Integer> inOrder = new ArrayList<>();
            for (int i = 0; i < handed; i++) inOrder.add(i);
            assertEquals(inOrder, ran);
        } finally {
            release.countDown();
        }
    }

    @Test
    public void anImmediateMainThreadErrorIsLoggedInsteadOfEscaping() {
        assertTrue(Utils.isCurrentlyOnMainThread());
        Utils.runOnMainThreadNowOrLater(() -> { throw new AssertionError("main sentinel"); });
        assertTrue(LogBufferManager.buildExportText().contains("main sentinel"));
    }

    @Test
    public void aBrokenFailureDescriptionCannotEscapeMessageFallback() {
        RuntimeException[] failures = {
                new RuntimeException() {
                    @Override public String getMessage() {
                        throw new AssertionError("getMessage must stay contained");
                    }
                },
                new RuntimeException() {
                    @Override public String toString() {
                        throw new AssertionError("toString must stay contained");
                    }
                }
        };

        for (RuntimeException failure : failures) {
            Logger.diagnosticError(
                    DiagnosticCategory.PATCH_ERRORS,
                    "LoggerFailureContainmentTest",
                    () -> { throw failure; },
                    null
            );
        }

        String report = LogBufferManager.buildExportText();
        assertEquals(2, occurrences(report, "Could not build the log message."));
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
