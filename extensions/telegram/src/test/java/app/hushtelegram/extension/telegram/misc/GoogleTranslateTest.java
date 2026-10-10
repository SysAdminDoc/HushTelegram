/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

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
}
