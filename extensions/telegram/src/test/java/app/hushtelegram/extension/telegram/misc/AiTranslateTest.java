/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AiTranslateTest {
    @Test public void theBaseAddressGetsTheChatPathAndAFullOneIsKept() {
        assertEquals("https://api.openai.com/v1/chat/completions", AiTranslate.endpoint("https://api.openai.com/v1"));
        assertEquals("https://openrouter.ai/api/v1/chat/completions", AiTranslate.endpoint(" https://openrouter.ai/api/v1// "));
        assertEquals("https://api.groq.com/openai/v1/chat/completions",
                AiTranslate.endpoint("https://api.groq.com/openai/v1/chat/completions"));
    }

    @Test public void theRequestNamesTheModelAndTheLanguageAndCarriesTheTextAsIs() throws Exception {
        JSONObject body = new JSONObject(AiTranslate.body("gpt-4o-mini", "de", "Привет, \"мир\"\nline two"));
        assertEquals("gpt-4o-mini", body.getString("model"));
        assertEquals(0, body.getInt("temperature"));
        JSONArray messages = body.getJSONArray("messages");
        assertEquals(2, messages.length());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertTrue(messages.getJSONObject(0).getString("content").contains("into German (de)."));
        assertTrue(messages.getJSONObject(0).getString("content").contains(AiTranslate.SAME));
        assertEquals("user", messages.getJSONObject(1).getString("role"));
        assertEquals("Привет, \"мир\"\nline two", messages.getJSONObject(1).getString("content"));
    }

    @Test public void theLanguageIsNamedInEnglishWithItsCodeOrJustTheCode() {
        assertEquals("Portuguese (pt)", AiTranslate.languageName("pt"));
        assertEquals("Chinese (zh-TW)", AiTranslate.languageName("zh-TW"));
        assertEquals("qqq", AiTranslate.languageName("qqq"));
    }

    @Test public void theAnswerIsTheFirstChoiceTrimmedAndShows() throws Exception {
        GoogleTranslate.Result result = AiTranslate.parse(
                "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"  Hello, world \\n\"}}]}", "en", "Привет, мир");
        assertEquals("Hello, world", result.text);
        assertFalse("a translation shows", result.sameLanguageAs("en"));
    }

    @Test public void aMessageAlreadyInTheLanguageComesBackUntouched() throws Exception {
        GoogleTranslate.Result marked = AiTranslate.parse(
                "{\"choices\":[{\"message\":{\"content\":\"[[SAME]]\"}}]}", "en", "Hello there");
        assertEquals("Hello there", marked.text);
        assertTrue(marked.sameLanguageAs("en"));
        // A model that ignores the instruction and repeats the text is read the same way.
        GoogleTranslate.Result echoed = AiTranslate.parse(
                "{\"choices\":[{\"message\":{\"content\":\"Hello there\\n\"}}]}", "en", "Hello there");
        assertTrue(echoed.sameLanguageAs("en"));
    }

    @Test public void aReasoningModelsThinkBlockIsDropped() throws Exception {
        GoogleTranslate.Result result = AiTranslate.parse(
                "{\"choices\":[{\"message\":{\"content\":\"<think>\\nThe user wants English.\\n</think>\\n\\nHello, world\"}}]}",
                "en", "Привет, мир");
        assertEquals("Hello, world", result.text);
    }

    @Test(expected = JSONException.class)
    public void anEmptyAnswerIsAFailure() throws Exception {
        AiTranslate.parse("{\"choices\":[{\"message\":{\"content\":null}}]}", "en", "Привет");
    }

    @Test(expected = JSONException.class)
    public void anErrorBodyIsAFailure() throws Exception {
        AiTranslate.parse("{\"error\":{\"message\":\"Incorrect API key provided: sk-abc***xyz\"}}", "en", "Привет");
    }

    @Test public void aServiceIsSavedOnlyWithAKeyAModelAndHttps() {
        assertEquals("Type your API key, or tap Use Google.", AiTranslate.refusal("https://api.openai.com/v1", "gpt-4o-mini", "  "));
        assertEquals("Type a model name.", AiTranslate.refusal("https://api.openai.com/v1", " ", "sk-1"));
        assertEquals("Take the spaces out of the address, model and key.", AiTranslate.refusal("https://api.openai.com/v1", "gpt 4o", "sk-1"));
        assertEquals("Take the spaces out of the address, model and key.", AiTranslate.refusal("https://api.openai.com/v1", "gpt-4o", "sk 1"));
        assertEquals("The address has to start with https://.", AiTranslate.refusal("http://api.example.com/v1", "m", "k"));
        assertEquals("The address has to start with https://.", AiTranslate.refusal("api.openai.com/v1", "m", "k"));
        assertEquals("The address has to start with https://.", AiTranslate.refusal("ftp://example.com", "m", "k"));
        assertNull(AiTranslate.refusal(" https://api.openai.com/v1 ", "gpt-4o-mini", " sk-1 "));
        // A model server on the phone itself can take plain http, since the key never leaves it.
        assertNull(AiTranslate.refusal("http://127.0.0.1:1234/v1", "local", "k"));
        assertNull(AiTranslate.refusal("http://localhost:8080/v1", "local", "k"));
    }

    @Test public void theRowNamesTheHostAndNeverTheKey() {
        AiTranslate.Service service = new AiTranslate.Service("https://openrouter.ai/api/v1", "deepseek/deepseek-chat", "sk-secret");
        assertTrue(service.enabled());
        assertEquals("openrouter.ai", service.host());
        assertFalse(new AiTranslate.Service("https://api.openai.com/v1", "gpt-4o-mini", "").enabled());
    }

    @Test public void aRefusedKeySaysOnlyItsStatus() {
        String message = new AiTranslate.KeyRefused(401).getMessage();
        assertEquals("AI service refused the key: 401", message);
    }
}
