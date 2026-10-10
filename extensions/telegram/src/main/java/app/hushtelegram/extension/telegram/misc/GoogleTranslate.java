/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtelegram.extension.telegram.misc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * Google's keyless web translate endpoint, the one the browser extensions use. A short text goes
 * in the address and a long one in a form body, since an address can only be so long. The answer
 * is a JSON array: the translated pieces first, then the language it detected in the third slot.
 *
 * <p>The fetch blocks, so it runs on a worker thread only.
 */
final class GoogleTranslate {
    private GoogleTranslate() {}

    static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single";
    /** Past this an address risks being refused, so the text goes in the body instead. */
    static final int MAX_ADDRESS = 1800;
    static final int CONNECT_MS = 6000;
    static final int READ_MS = 8000;
    private static final int MAX_ANSWER = 512 * 1024;

    /** What came back: the translation and the language Google says the text was in. */
    static final class Result {
        final String text;
        final String source;

        Result(String text, String source) {
            this.text = text;
            this.source = source;
        }

        /** Whether the text already reads in [target], so there's nothing to show. */
        boolean sameLanguageAs(String target) {
            return !source.isEmpty() && base(source).equals(base(target));
        }

        private static String base(String tag) {
            String lower = tag.toLowerCase(Locale.ROOT);
            int dash = lower.indexOf('-');
            return dash < 0 ? lower : lower.substring(0, dash);
        }
    }

    static String query(String target) {
        return "client=gtx&sl=auto&tl=" + encode(target) + "&dt=t";
    }

    static String address(String target, String text) {
        return ENDPOINT + "?" + query(target) + "&q=" + encode(text);
    }

    static boolean needsBody(String target, String text) {
        return address(target, text).length() > MAX_ADDRESS;
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Reads the answer: every translated piece joined, and the detected language. */
    static Result parse(String json) throws JSONException {
        JSONArray root = new JSONArray(json);
        JSONArray pieces = root.getJSONArray(0);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < pieces.length(); i++) {
            JSONArray piece = pieces.optJSONArray(i);
            if (piece != null && !piece.isNull(0)) text.append(piece.getString(0));
        }
        if (text.length() == 0) throw new JSONException("no translated text");
        String source = root.length() > 2 && !root.isNull(2) ? root.optString(2, "") : "";
        return new Result(text.toString(), source);
    }

    static Result fetch(String target, String text) throws IOException, JSONException {
        boolean body = needsBody(target, text);
        URL url = new URL(body ? ENDPOINT + "?" + query(target) : address(target, text));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);
            connection.setRequestProperty("User-Agent", "HushTelegram");
            if (body) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(("q=" + encode(text)).getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("translate answered " + code);
            return parse(read(connection.getInputStream()));
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
                if (out.size() > MAX_ANSWER) throw new IOException("translate answer too large");
            }
            return out.toString("UTF-8");
        }
    }
}
