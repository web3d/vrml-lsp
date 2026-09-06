package org.vrml.lsp.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.vrml.lsp.diagnostics.DocumentAnalyzer;
import org.vrml.lsp.diagnostics.Issue;

/**
 * Ratchet over what the corpus reports, read the way the server reads it (UTF-8, through
 * {@link DocumentAnalyzer} so the header rules are included).
 *
 * <p>{@link CorpusRoundTripTest} proves nothing is lost; this proves nothing is <em>invented</em>.
 * Since M4 the analysis behind a baseline line covers semantic codes too, so the ratchet guards both
 * passes at once. Every well-formed VRML97 file in the corpus still analyses clean: what the baseline
 * lists is X3D wearing a .wrl name, the five deliberate {@code bad_header*} cases, the
 * {@code error_handling/} files that exist to be caught, and the grammar tests that use Java3D's
 * {@code TransformGroup} where a Transform would do. A new entry therefore means either a bug or a
 * deliberate change in policy - never a surprise.
 *
 * <p>Regenerate with {@code -Dvrml.updateBaseline=true} after reviewing the diff.
 */
class CorpusIssueBaselineTest {

    private static final String BASELINE_PROPERTY = "vrml.baseline.file";

    private static Path baselineFile() {
        String configured = System.getProperty(BASELINE_PROPERTY);
        assertTrue(configured != null, BASELINE_PROPERTY + " must be set by the build");
        return Path.of(configured);
    }

    @Test
    @Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
    void corpusMatchesTheRecordedBaseline() throws IOException {
        Map<String, String> actual = measure();
        if (Boolean.getBoolean("vrml.updateBaseline")) {
            writeBaseline(actual);
            System.out.println("baseline rewritten: " + baselineFile());
            return;
        }
        Map<String, String> expected = readBaseline();
        List<String> diff = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String got = actual.get(e.getKey());
            if (got == null) {
                diff.add("fixed but still recorded: " + e.getKey() + " was " + e.getValue());
            } else if (!got.equals(e.getValue())) {
                diff.add("changed: " + e.getKey() + " expected [" + e.getValue() + "] got [" + got + "]");
            }
        }
        for (Map.Entry<String, String> e : actual.entrySet()) {
            if (!expected.containsKey(e.getKey())) {
                diff.add("new: " + e.getKey() + " -> " + e.getValue());
            }
        }
        assertEquals(0, diff.size(), "baseline drift:\n" + String.join("\n", diff));
    }

    /** Guards the baseline: it is a short list on purpose, not a dumping ground. */
    @Test
    void onlyTheKnownBrokenFilesReportAnything() throws IOException {
        Map<String, String> actual = measure();
        // Twenty-four files, all accounted for in the baseline: five bad_header*, the six
        // error_handling cases (reporting them is the point), two more .wrl-named X3D files, six
        // grammar tests naming Java3D's TransformGroup, four Script tests writing three numbers for
        // an SFInt32, and one exposedField declared inside a Script. Growing this number needs a
        // reason in the baseline, not just a relaxed bound.
        assertTrue(actual.size() <= 24, () -> "expected only the known-broken corpus files to report, got "
                + actual.keySet());
    }

    private static Map<String, String> measure() throws IOException {
        Path root = Path.of(System.getProperty("parsetest.dir"));
        Map<String, String> out = new TreeMap<>();
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".wrl")).sorted().toList();
        }
        for (Path file : files) {
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            List<Issue> found = DocumentAnalyzer.analyze(source).issues();
            if (found.isEmpty()) {
                continue;
            }
            Map<Integer, Integer> counts = new TreeMap<>();
            for (Issue issue : found) {
                counts.merge(issue.code(), 1, Integer::sum);
            }
            StringBuilder sb = new StringBuilder();
            counts.forEach((code, n) -> sb.append(sb.length() == 0 ? "" : ";")
                    .append(String.format("VRL%04d", code)).append("*").append(n));
            out.put(root.relativize(file).toString().replace(java.io.File.separatorChar, '/'),
                    sb.toString());
        }
        return out;
    }

    private static Map<String, String> readBaseline() throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (String line : Files.readAllLines(baselineFile(), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int tab = trimmed.indexOf('\t');
            assertTrue(tab > 0, "malformed baseline line: " + line);
            String codes = trimmed.substring(tab + 1);
            int hash = codes.indexOf('#');
            if (hash >= 0) {
                codes = codes.substring(0, hash).trim();
            }
            out.put(trimmed.substring(0, tab).trim(), codes);
        }
        return out;
    }

    private static void writeBaseline(Map<String, String> actual) throws IOException {
        Map<String, String> reasons = readReasons();
        List<String> lines = new ArrayList<>();
        lines.add("# Issues the corpus reports, counted per file and per code.");
        lines.add("# Format: <path relative to parsetest> TAB <VRLcode*count>[;<VRLcode*count>...]  # why");
        lines.add("# Everything not listed here must analyse clean; an entry here is a known limitation,");
        lines.add("# not a TODO. Regenerate with -Dvrml.updateBaseline=true and review the diff.");
        for (Map.Entry<String, String> e : actual.entrySet()) {
            String why = reasons.getOrDefault(e.getKey(), "TODO reason");
            lines.add(e.getKey() + "\t" + e.getValue() + "\t# " + why);
        }
        Files.write(baselineFile(), String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    /** The trailing {@code # why} of each line, keyed by path, so a rewrite keeps the prose. */
    private static Map<String, String> readReasons() throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isRegularFile(baselineFile())) {
            return out;
        }
        for (String line : Files.readAllLines(baselineFile(), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int tab = trimmed.indexOf('\t');
            int hash = trimmed.indexOf('#');
            if (tab < 0 || hash < tab) {
                continue;
            }
            out.put(trimmed.substring(0, tab).trim(), trimmed.substring(hash + 1).trim());
        }
        return out;
    }
}
