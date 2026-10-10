/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import app.hushtelegram.extension.shared.L10n;

/**
 * An AI service with the person's own key, in place of Google's web translate. Any service that
 * takes OpenAI's chat completions format works: OpenAI, OpenRouter, DeepSeek, Groq, Google's
 * Gemini through its OpenAI address, or a model server running on the phone.
 *
 * <p>The address, model and key are kept by {@link OutsideTranslate} in its own preferences file,
 * beside the chats that are on, so they never reach a settings file, the diagnostic report or a
 * log. A failure carries only the service's HTTP status, because a service's error text can repeat
 * part of the key.
 *
 * <p>The fetch blocks, so it runs on a worker thread only.
 */
public final class AiTranslate {
    private AiTranslate() {}

    static final String DEFAULT_ADDRESS = "https://api.openai.com/v1";
    static final String DEFAULT_MODEL = "gpt-4o-mini";
    /** What the model answers for a message already in the target language. */
    static final String SAME = "[[SAME]]";
    static final int MAX_FIELD = 512;
    static final int CONNECT_MS = 8000;
    /** A model takes longer than Google to answer, more so on a long message. */
    static final int READ_MS = 45000;
    private static final int MAX_ANSWER = 512 * 1024;
    private static final Pattern THINKING = Pattern.compile("(?s)^\\s*<think>.*?</think>\\s*");

    /** The service as saved. An empty key means Google's web translate is used instead. */
    public static final class Service {
        static final Service GOOGLE = new Service(DEFAULT_ADDRESS, DEFAULT_MODEL, "");

        public final String address;
        public final String model;
        /** Shown only in the editor's masked field. */
        public final String key;

        Service(String address, String model, String key) {
            this.address = address;
            this.model = model;
            this.key = key;
        }

        public boolean enabled() {
            return !key.isEmpty();
        }

        /** The service's host name, for the settings row. Never the key. */
        public String host() {
            try {
                String host = new URI(address).getHost();
                return host != null ? host : address;
            } catch (URISyntaxException unreadable) {
                return address;
            }
        }
    }

    /** The service turned the key down (401 or 403), so the toast can say that instead of "not available". */
    static final class KeyRefused extends IOException {
        KeyRefused(int code) {
            super("AI service refused the key: " + code);
        }
    }

    /**
     * Why a typed service can't be saved, or null when it can. The key and the model can't hold
     * spaces, and a key only travels over https, apart from a model server on the phone itself.
     */
    @Nullable
    public static String refusal(String address, String model, String key) {
        String a = address.trim(), m = model.trim(), k = key.trim();
        if (k.isEmpty()) return L10n.t("Type your API key, or tap Use Google.");
        if (m.isEmpty()) return L10n.t("Type a model name.");
        if (a.length() > MAX_FIELD || m.length() > MAX_FIELD || k.length() > MAX_FIELD
                || hasSpace(a) || hasSpace(m) || hasSpace(k)) {
            return L10n.t("Take the spaces out of the address, model and key.");
        }
        URI uri;
        try {
            uri = new URI(a);
        } catch (URISyntaxException unreadable) {
            return L10n.t("The address has to start with https://.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean onPhone = host.equals("localhost") || host.equals("127.0.0.1");
        if (host.isEmpty() || !(scheme.equals("https") || (scheme.equals("http") && onPhone))) {
            return L10n.t("The address has to start with https://.");
        }
        return null;
    }

    private static boolean hasSpace(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isWhitespace(value.charAt(i))) return true;
        return false;
    }

    /** The chat completions address: the service's base address, or the full one if that's what was typed. */
    static String endpoint(String address) {
        String base = address.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    /** The target language in words, with its code, since a model reads "German (de)" better than "de". */
    static String languageName(String tag) {
        String name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() || name.equalsIgnoreCase(tag) ? tag : name + " (" + tag + ")";
    }

    static String instructions(String target) {
        return "You translate Telegram messages. Translate the user's message into " + languageName(target)
                + ". Keep its meaning, tone, line breaks, emoji, links, @mentions and #hashtags. Answer with the "
                + "translation only, with no notes, quotes or explanation. If the message is already in that "
                + "language, answer with " + SAME + " and nothing else.";
    }

    static String body(String model, String target, String text) throws JSONException {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", instructions(target)))
                .put(new JSONObject().put("role", "user").put("content", text));
        return new JSONObject()
                .put("model", model)
                .put("temperature", 0)
                .put("messages", messages)
                .toString();
    }

    /**
     * Reads the answer. A message already in the target language comes back as the original, marked
     * as in that language, so it shows untouched. A reasoning model's leading think block is dropped.
     */
    static GoogleTranslate.Result parse(String json, String target, String original) throws JSONException {
        JSONObject message = new JSONObject(json).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        String text = message.isNull("content") ? "" : message.getString("content");
        text = THINKING.matcher(text).replaceFirst("").trim();
        if (text.isEmpty()) throw new JSONException("no translated text");
        if (text.equals(SAME) || text.equals(original.trim())) return new GoogleTranslate.Result(original, target);
        return new GoogleTranslate.Result(text, "");
    }

    static GoogleTranslate.Result fetch(Service service, String target, String text) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint(service.address)).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Authorization", "Bearer " + service.key);
            connection.setRequestProperty("User-Agent", "HushTelegram");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body(service.model, target, text).getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) throw new KeyRefused(code);
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("AI service answered " + code);
            return parse(read(connection.getInputStream()), target, text);
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_ANSWER) throw new IOException("AI service answer too large");
            }
            return out.toString("UTF-8");
        }
    }
}
