import app.hushtelegram.extension.shared.diagnostics.DiagnosticRedactor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** Runs the production redactor on either the JDK or Android's ART/ICU runtime. */
public final class DiagnosticRedactorDevice {
    private DiagnosticRedactorDevice() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !("--export".equals(args[0]) || "--check".equals(args[0]))) {
            throw new IllegalArgumentException("Usage: DiagnosticRedactorDevice --export|--check <corpus.tsv>");
        }
        if ("--export".equals(args[0])) {
            // Reflection keeps the device payload independent of JUnit and the test class.
            Class<?> test = Class.forName("app.hushtelegram.extension.shared.diagnostics.DiagnosticRedactorTest");
            String[][] corpus = (String[][]) test.getField("CREDENTIAL_CORPUS").get(null);
            String[][] apiCorpus = (String[][]) test.getField("API_IDENTITY_CORPUS").get(null);
            String apiControls = (String) test.getField("API_IDENTITY_CONTROLS").get(null);
            String probe = (String) test.getField("TELEGRAM_EXPORT_PROBE").get(null);
            String expected = (String) test.getField("TELEGRAM_EXPORT_REDACTED").get(null);
            if (corpus.length == 0) throw new AssertionError("The test corpus is empty");
            if (apiCorpus.length == 0) throw new AssertionError("The API identity corpus is empty");
            for (String[] apiRow : apiCorpus) {
                boolean included = false;
                for (String[] row : corpus) included |= Arrays.equals(apiRow, row);
                if (!included) throw new AssertionError("An API identity canary is missing from the exported corpus");
            }
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(args[1]), StandardCharsets.UTF_8))) {
                for (String[] row : corpus) {
                    if (row.length < 2) throw new AssertionError("A corpus row has no secrets");
                    writeRow(out, "C", row);
                }
                for (int i = 0; i < apiCorpus.length; i++) {
                    String[] row = apiCorpus[i];
                    int counter = row[0].indexOf("counter");
                    String counterField = row[0].substring(counter, row[0].indexOf('8', counter) + 1);
                    writeRow(out, "P", new String[]{"api_case_" + i + " " + row[0] + " " + apiControls,
                            row[1], counterField, apiControls});
                }
                writeRow(out, "E", new String[]{probe, expected});
            }
            System.out.println("HUSHTELEGRAM_REDACTOR_EXPORT rows=" + corpus.length + " exact=1");
            return;
        }

        List<String[]> corpus = new ArrayList<>();
        List<String[]> exact = new ArrayList<>();
        List<String[]> preservation = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(args[1]), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null;) {
                String[] fields = line.split("\t", -1);
                if (fields.length < 3 || !("C".equals(fields[0]) || "E".equals(fields[0]) || "P".equals(fields[0]))) {
                    throw new AssertionError("Invalid corpus record");
                }
                String[] row = new String[fields.length - 1];
                for (int i = 1; i < fields.length; i++) {
                    row[i - 1] = new String(Base64.getDecoder().decode(fields[i]), StandardCharsets.UTF_8);
                    if (row[i - 1].isEmpty()) throw new AssertionError("Empty corpus field");
                }
                if ("C".equals(fields[0])) {
                    corpus.add(row);
                } else if ("P".equals(fields[0])) {
                    if (row.length != 4) throw new AssertionError("A preservation record needs input, canary and two controls");
                    preservation.add(row);
                } else {
                    if (row.length != 2) throw new AssertionError("An exact record needs input and expected text");
                    exact.add(row);
                }
            }
        }
        if (corpus.isEmpty() || exact.size() != 1 || preservation.isEmpty()) {
            throw new AssertionError("The corpus, API preservation records or Telegram expectation is missing");
        }

        StringBuilder joined = new StringBuilder("MORPHE DIAGNOSTIC REPORT\nschema: 1\n");
        for (int i = 0; i < corpus.size(); i++) {
            String[] row = corpus.get(i);
            requireNoSecrets(row, DiagnosticRedactor.redact(row[0]), "row " + i);
            joined.append(row[0]).append('\n');
        }
        for (int i = 0; i < preservation.size(); i++) {
            String[] row = preservation.get(i);
            requireControls(row, DiagnosticRedactor.redact(row[0]), "API row " + i);
            joined.append(row[0]).append('\n');
        }
        String report = DiagnosticRedactor.redact(joined.toString());
        for (int i = 0; i < corpus.size(); i++) {
            requireNoSecrets(corpus.get(i), report, "joined row " + i);
        }
        for (int i = 0; i < preservation.size(); i++) {
            String[] row = preservation.get(i);
            int from = report.indexOf("api_case_" + i + " ");
            int to = from < 0 ? -1 : report.indexOf('\n', from);
            if (from < 0 || to < from) throw new AssertionError("Joined report lost API row " + i);
            requireControls(row, report.substring(from, to), "joined API row " + i);
        }
        for (String[] row : exact) {
            String actual = DiagnosticRedactor.redact(row[0]);
            if (!row[1].equals(actual)) {
                throw new AssertionError("Telegram probe lost redaction or its version/timestamp/counters/digests: " + actual);
            }
            if (!report.contains(row[1] + "\n")) {
                throw new AssertionError("Joined report lost the redacted Telegram probe or its exact controls");
            }
        }
        System.out.println("HUSHTELEGRAM_REDACTOR_OK rows=" + corpus.size() + " joined=1 exact=" + exact.size());
    }

    private static void writeRow(BufferedWriter out, String kind, String[] row) throws Exception {
        out.write(kind);
        for (String field : row) {
            if (field == null || field.isEmpty()) throw new AssertionError("Empty corpus field");
            out.write('\t');
            out.write(Base64.getEncoder().encodeToString(field.getBytes(StandardCharsets.UTF_8)));
        }
        out.write('\n');
    }

    private static void requireNoSecrets(String[] row, String text, String where) {
        for (int i = 1; i < row.length; i++) {
            if (text.contains(row[i])) {
                throw new AssertionError("Synthetic secret survived " + where + ": " + row[i]);
            }
        }
    }

    private static void requireControls(String[] row, String text, String where) {
        if (text.contains(row[1])) throw new AssertionError("Synthetic API identity survived " + where);
        if (!text.contains(row[2]) || !text.endsWith(row[3])) {
            throw new AssertionError("API counter/version/digest controls changed " + where + ": " + text);
        }
    }
}
