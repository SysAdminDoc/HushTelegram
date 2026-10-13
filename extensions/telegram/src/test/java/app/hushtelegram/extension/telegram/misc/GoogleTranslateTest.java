/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class GoogleTranslateTest {
    @Test public void aShortTextGoesInTheAddress() {
        assertEquals("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=de&dt=t&q=Hello+world%3F",
                GoogleTranslate.address("de", "Hello world?"));
        assertFalse(GoogleTranslate.needsBody("de", "Hello world?"));
    }

    @Test public void aLongTextGoesInTheBodyAndTheAddressKeepsOnlyTheQuery() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 400; i++) text.append("Привет ");
        assertTrue(GoogleTranslate.needsBody("en", text.toString()));
        assertEquals("client=gtx&sl=auto&tl=en&dt=t", GoogleTranslate.query("en"));
    }

    @Test public void theAnswerJoinsEveryPieceAndNamesTheLanguage() throws Exception {
        GoogleTranslate.Result result = GoogleTranslate.parse(
                "[[[\"Hello \",\"Привет \",null,null,10],[\"world\",\"мир\",null,null,10]],null,\"ru\",null,null,null,1,[]]");
        assertEquals("Hello world", result.text);
        assertEquals("ru", result.source);
    }

    @Test public void unicodeAndQuotesSurviveTheAnswer() throws Exception {
        GoogleTranslate.Result result = GoogleTranslate.parse("[[[\"Sagte \\\"Hallo\\\" \\u00fc\",\"x\"]],null,\"en\"]");
        assertEquals("Sagte \"Hallo\" \u00fc", result.text);
    }

    @Test public void anAnswerWithNothingToShowIsAFailure() {
        for (String bad : new String[] {"", "not json", "[]", "[[]]", "[[[null,\"x\"]],null,\"en\"]", "{\"a\":1}"}) {
            try {
                GoogleTranslate.parse(bad);
                fail(bad);
            } catch (JSONException expected) {
                // Nothing is shown for it.
            }
        }
    }

    @Test public void aMissingLanguageIsNeverTheSameLanguage() throws Exception {
        assertFalse(GoogleTranslate.parse("[[[\"a\",\"b\"]]]").sameLanguageAs("en"));
    }

    @Test public void theLanguageMatchesOnItsBase() {
        assertTrue(new GoogleTranslate.Result("a", "en").sameLanguageAs("en"));
        assertTrue(new GoogleTranslate.Result("a", "zh-CN").sameLanguageAs("zh-TW"));
        assertTrue(new GoogleTranslate.Result("a", "EN").sameLanguageAs("en-US"));
        assertFalse(new GoogleTranslate.Result("a", "ru").sameLanguageAs("en"));
    }

    /** A service sending one byte a second keeps every read inside the timeout. The whole answer still stops. */
    @Test public void aTrickleStopsAtTheDeadlineAndItsStreamIsClosed() throws IOException {
        long[] now = {0};
        boolean[] closed = {false};
        LongSupplier real = GoogleTranslate.nanos;
        GoogleTranslate.nanos = () -> now[0];
        InputStream trickle = new InputStream() {
            @Override public int read() {
                now[0] += TimeUnit.SECONDS.toNanos(1);
                return 'x';
            }

            @Override public int read(byte[] buffer, int offset, int length) {
                buffer[offset] = (byte) read();
                return 1;
            }

            @Override public void close() {
                closed[0] = true;
            }
        };
        try {
            GoogleTranslate.read(trickle, 512 * 1024, "too large", GoogleTranslate.deadline(20_000));
            fail("the trickle was read past its deadline");
        } catch (InterruptedIOException expected) {
            assertEquals("stopped at the first read past the deadline", TimeUnit.SECONDS.toNanos(21), now[0]);
        } finally {
            GoogleTranslate.nanos = real;
        }
        assertTrue(closed[0]);
    }

    @Test public void anAnswerPastItsByteLimitStopsTooAndOneInTimeIsRead() throws IOException {
        long deadline = GoogleTranslate.deadline(20_000);
        try {
            GoogleTranslate.read(new ByteArrayInputStream(new byte[2048]), 1024, "too large", deadline);
            fail("an answer past its limit was read whole");
        } catch (IOException expected) {
            assertEquals("too large", expected.getMessage());
        }
        assertEquals("[]", GoogleTranslate.read(new ByteArrayInputStream("[]".getBytes()), 1024, "too large", deadline));
    }

    /** Closing the connection is what wakes a worker blocked on it, so the watchdog closes it at the deadline. */
    @Test public void theWatchdogClosesTheConnectionAtTheDeadlineUnlessTheRequestIsDone() throws Exception {
        CountDownLatch late = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        ScheduledFuture<?> fires = GoogleTranslate.closeAt(connection(late), GoogleTranslate.deadline(50));
        ScheduledFuture<?> cancelled = GoogleTranslate.closeAt(connection(done), GoogleTranslate.deadline(300));
        cancelled.cancel(false);
        assertTrue("a request past its deadline stayed open", late.await(5, TimeUnit.SECONDS));
        // disconnect() counts down before the task is marked done, so wait for that rather than read it.
        fires.get(5, TimeUnit.SECONDS);
        assertFalse("a request done in time was closed anyway", done.await(600, TimeUnit.MILLISECONDS));
    }

    private static HttpURLConnection connection(CountDownLatch disconnected) throws IOException {
        return new HttpURLConnection(new URL("http://127.0.0.1/")) {
            @Override public void disconnect() {
                disconnected.countDown();
            }

            @Override public boolean usingProxy() {
                return false;
            }

            @Override public void connect() {}
        };
    }
}
