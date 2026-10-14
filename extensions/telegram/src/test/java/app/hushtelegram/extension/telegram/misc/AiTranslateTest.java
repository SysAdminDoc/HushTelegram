/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** The request as a service sees it: one POST to the chat path, the key as a bearer, the answer read back. */
    @Test public void theRequestCarriesTheKeyToTheChatPathAndTheAnswerComesBack() throws Exception {
        try (LocalService service = new LocalService(path -> answer(200, "OK", "",
                "{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}"))) {
            GoogleTranslate.Result result = AiTranslate.fetch(new AiTranslate.Service(service.base(), "local-model", "sk-secret"), "en", "Привет");
            assertEquals("Hello", result.text);
            assertEquals(1, service.seen.size());
            LocalService.Request request = service.seen.get(0);
            assertEquals("POST /v1/chat/completions", request.line);
            assertEquals("Bearer sk-secret", request.authorization);
            assertEquals("local-model", new JSONObject(request.body).getString("model"));
        }
    }

    /** The person's own words on how to translate go after ours in the system message, and the text stays as it is. */
    @Test public void thePersonsOwnInstructionsComeAfterOursAndNoneLeavesOursAsTheyWere() throws Exception {
        String ours = new JSONObject(AiTranslate.body("m", "en", "Hola")).getJSONArray("messages").getJSONObject(0).getString("content");
        assertEquals(AiTranslate.instructions("en"), ours);
        assertEquals("blank instructions add nothing", ours, AiTranslate.instructions("en", " \n "));
        assertEquals(ours, AiTranslate.instructions("en", null));

        JSONArray messages = new JSONObject(AiTranslate.body("m", "en", "Hola", "  Keep it casual.\nLeave names as they are.  "))
                .getJSONArray("messages");
        String system = messages.getJSONObject(0).getString("content");
        assertTrue("ours come first", system.startsWith(ours));
        assertTrue(system.endsWith("\nKeep it casual.\nLeave names as they are."));
        assertTrue("the answer still has to be only the translation", system.substring(ours.length()).contains("translation only"));
        assertEquals("Hola", messages.getJSONObject(1).getString("content"));
    }

    @Test public void instructionsMayHoldSpacesAndLinesButNotPastTheirLimit() {
        String address = "https://api.openai.com/v1";
        assertNull(AiTranslate.validate(address, "m", "k", "Keep it casual.\nNo notes, please."));
        assertNull(AiTranslate.validate(address, "m", "k", ""));
        String tooLong = new String(new char[AiTranslate.MAX_STYLE + 1]).replace('\0', 'x');
        AiTranslate.Validation refused = AiTranslate.validate(address, "m", "k", tooLong);
        assertEquals("the instructions field is the one pointed at", 3, refused.field);
        assertEquals("This field is too long. Use 1000 characters or fewer.", refused.message);
        assertNull("spaces around them don't count", AiTranslate.validate(address, "m", "k",
                "  " + tooLong.substring(1) + "  "));
        assertEquals("a missing key is still named first", 2, AiTranslate.validate(address, "m", " ", tooLong).field);
    }

    @Test public void aServiceWithInstructionsSendsThem() throws Exception {
        try (LocalService service = new LocalService(path -> answer(200, "OK", "",
                "{\"choices\":[{\"message\":{\"content\":\"Hey\"}}]}"))) {
            AiTranslate.fetch(new AiTranslate.Service(service.base(), "m", "sk-secret", "Keep it casual."), "en", "Привет");
            String system = new JSONObject(service.seen.get(0).body).getJSONArray("messages").getJSONObject(0).getString("content");
            assertTrue(system.endsWith("\nKeep it casual."));
        }
    }

    /** A service that answers with a redirect gets a failure, not a second request carrying the key. */
    @Test public void aRedirectIsRefusedSoTheKeyGoesNowhereElse() throws Exception {
        LocalService[] holder = new LocalService[1];
        try (LocalService service = new LocalService(path -> path.equals("/v1/chat/completions")
                ? answer(302, "Found", "Location: " + holder[0].base().replace("/v1", "/elsewhere") + "\r\n", "")
                : answer(200, "OK", "", "{\"choices\":[{\"message\":{\"content\":\"stolen\"}}]}"))) {
            holder[0] = service;
            try {
                AiTranslate.fetch(new AiTranslate.Service(service.base(), "m", "sk-secret"), "en", "Привет");
                fail("the redirect was followed");
            } catch (IOException refused) {
                assertEquals("AI service answered 302", refused.getMessage());
            }
            assertEquals(1, service.seen.size());
            assertEquals("POST /v1/chat/completions", service.seen.get(0).line);
        }
    }

    /** A service that answers a byte at a time never trips the read timeout, so the whole request has a deadline. */
    @Test public void aTricklingAnswerStopsAtTheDeadlineAndFreesTheWorker() throws Exception {
        long kept = AiTranslate.totalMs;
        AiTranslate.totalMs = 600;
        try (ServerSocket socket = new ServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                try (Socket client = socket.accept()) {
                    LocalService.read(client.getInputStream());
                    java.io.OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\nConnection: close\r\n\r\n{")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    for (int i = 0; i < 2000; i++) {
                        Thread.sleep(50);
                        out.write(' ');
                        out.flush();
                    }
                } catch (IOException | InterruptedException closed) {
                    // The client gave up, which is the point.
                }
            }, "trickling-service");
            server.setDaemon(true);
            server.start();
            long started = System.nanoTime();
            try {
                AiTranslate.fetch(new AiTranslate.Service("http://127.0.0.1:" + socket.getLocalPort() + "/v1", "m", "sk-1"), "en", "Привет");
                fail("a trickling answer was read to the end");
            } catch (IOException expected) {
                long took = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue("took " + took + " ms", took < 5000);
            }
        } finally {
            AiTranslate.totalMs = kept;
        }
    }

    private static String answer(int code, String reason, String headers, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return "HTTP/1.1 " + code + " " + reason + "\r\n" + headers + "Content-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n" + body;
    }

    /**
     * An HTTP service on the phone's own address, the one the translator takes over plain http.
     * Android's unit tests compile against android.jar, which has no com.sun.net.httpserver, so
     * this reads each request off a ServerSocket itself and answers with what [answer] says.
     */
    private static final class LocalService implements AutoCloseable {
        static final class Request {
            final String line;
            final String authorization;
            final String body;

            Request(String line, String authorization, String body) {
                this.line = line;
                this.authorization = authorization;
                this.body = body;
            }
        }

        private static final Pattern CONTENT_LENGTH = Pattern.compile("(?im)^Content-Length:\\s*(\\d+)\\s*$");
        private static final Pattern AUTHORIZATION = Pattern.compile("(?im)^Authorization:\\s*(.+?)\\s*$");

        final List<Request> seen = Collections.synchronizedList(new ArrayList<>());
        private final ServerSocket socket;

        LocalService(Function<String, String> answer) throws IOException {
            socket = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        Request request = read(client.getInputStream());
                        seen.add(request);
                        client.getOutputStream().write(answer.apply(request.line.split(" ")[1]).getBytes(StandardCharsets.UTF_8));
                        client.getOutputStream().flush();
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "local-service");
            thread.setDaemon(true);
            thread.start();
        }

        String base() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/v1";
        }

        private static Request read(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            for (int last = 0, b; (b = in.read()) != -1; ) {
                head.write(b);
                last = (last << 8) | b;
                if (last == 0x0d0a0d0a) break;
            }
            String headers = head.toString("UTF-8");
            Matcher length = CONTENT_LENGTH.matcher(headers);
            byte[] body = new byte[length.find() ? Integer.parseInt(length.group(1)) : 0];
            for (int read = 0, n; read < body.length && (n = in.read(body, read, body.length - read)) != -1; ) read += n;
            Matcher authorization = AUTHORIZATION.matcher(headers);
            return new Request(headers.substring(0, headers.indexOf("\r\n")).replaceAll(" HTTP/1\\.[01]$", ""),
                    authorization.find() ? authorization.group(1) : "", new String(body, StandardCharsets.UTF_8));
        }

        @Override public void close() throws IOException {
            socket.close();
        }
    }
}
