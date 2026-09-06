package org.vrml.lsp.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.vrml.lsp.text.LineIndex;

/**
 * Where a user is told to look, pinned for the corpus files that are meant to fail.
 *
 * <p>The baseline ratchet ({@code CorpusIssueBaselineTest}) says which codes fire and how often;
 * that is not enough for M2, whose acceptance is "each error_handling case is reported at the
 * right place". A count cannot tell an error on the broken line from an error on the line after
 * it - a range can.
 *
 * <p>Positions are 0-based lines and UTF-16 columns, the numbers a client receives, so the golden
 * file is a direct reading of what an editor will underline.
 *
 * <p>Regenerate with {@code -Dvrml.updateGolden=true}. Each entry is a claim about the one place
 * the mistake is, so review the diff instead of accepting it.
 */
class CorpusGoldenDiagnosticsTest {

    /** Files whose reported positions are pinned, in the order they read best in the golden. */
    private static final List<String> PINNED = List.of(
            "vrml97/bad_header1.wrl",
            "vrml97/bad_header2.wrl",
            "vrml97/bad_header3.wrl",
            "vrml97/bad_header4.wrl",
            "vrml97/bad_header5.wrl",
            "error_handling/import.wrl",
            "error_handling/bad_field_name.wrl",
            "error_handling/bad_field_name2.wrl",
            "error_handling/bad_field_value.wrl",
            "error_handling/double_def.wrl",
            "error_handling/eventIn_set.wrl",
            "error_handling/non_child_root.wrl",
            "events/boolean_filter.wrl",
            "nurbs/simple_nurbssurface.wrl",
            "scripts/exposed_field.wrl");

    private static final String GOLDEN_PROPERTY = "vrml.golden.file";
    /** One entry, {@code VRL1002@7:7-7:7}; several are separated by {@code " ; "}. */
    private static final String ENTRY = "VRL\\d{4}@\\d+:\\d+-\\d+:\\d+";

    private static Path goldenFile() {
        String configured = System.getProperty(GOLDEN_PROPERTY);
        assertTrue(configured != null, GOLDEN_PROPERTY + " must be set by the build");
        return Path.of(configured);
    }

    @Test
    @Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
    void everyPinnedFileIsReportedWhereExpected() throws IOException {
        Map<String, String> actual = measure();
        if (Boolean.getBoolean("vrml.updateGolden")) {
            writeGolden(actual);
            System.out.println("golden rewritten: " + goldenFile() + " (" + actual.size()
                    + " files)");
            return;
        }
        Map<String, String> expected = readGolden();
        assertEquals(expected.keySet(), actual.keySet(),
                "the pinned set and the golden file must list the same files");
        List<String> drift = new ArrayList<>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String got = actual.get(entry.getKey());
            if (!entry.getValue().equals(got)) {
                drift.add(entry.getKey() + "\n  expected: " + entry.getValue() + "\n  actual:   "
                        + got);
            }
        }
        assertEquals(List.of(), drift, "diagnostic positions moved:\n" + String.join("\n", drift));
    }

    /** Each pinned file must exist, or the golden quietly stops testing anything. */
    @Test
    void everyPinnedPathIsStillInTheCorpus() {
        Path root = Path.of(System.getProperty("parsetest.dir"));
        for (String relative : PINNED) {
            assertTrue(Files.isRegularFile(root.resolve(relative)),
                    "the corpus no longer holds " + relative);
        }
    }

    private static Map<String, String> measure() throws IOException {
        Path root = Path.of(System.getProperty("parsetest.dir"));
        Map<String, String> out = new LinkedHashMap<>();
        for (String relative : PINNED) {
            String source = Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
            out.put(relative, render(DocumentAnalyzer.analyze(source), source));
        }
        return out;
    }

    /** {@code VRL1002@7:7-7:7 ; ...}, or {@code -} when the file is meant to stay silent. */
    private static String render(DocumentAnalyzer.Analysis analysis, String source) {
        LineIndex index = new LineIndex(source);
        StringBuilder sb = new StringBuilder();
        for (Issue issue : analysis.issues()) {
            if (sb.length() > 0) {
                sb.append(" ; ");
            }
            sb.append(issue.codeLabel()).append('@')
                    .append(index.lineOf(issue.start())).append(':')
                    .append(index.characterOf(issue.start())).append('-')
                    .append(index.lineOf(issue.end())).append(':')
                    .append(index.characterOf(issue.end()));
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static Map<String, String> readGolden() throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (String line : Files.readAllLines(goldenFile(), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int tab = trimmed.indexOf('\t');
            assertTrue(tab > 0, "malformed golden line (no tab): " + line);
            String path = trimmed.substring(0, tab).trim();
            String expectation = trimmed.substring(tab + 1);
            int comment = expectation.indexOf('#');
            if (comment >= 0) {
                expectation = expectation.substring(0, comment).trim();
            }
            assertTrue(expectation.equals("-")
                            || expectation.matches(ENTRY + "( ; " + ENTRY + ")*"),
                    "malformed expectation for " + path + ": " + expectation);
            out.put(path, expectation);
        }
        return out;
    }

    private static void writeGolden(Map<String, String> actual) throws IOException {
        Map<String, String> notes = readNotes();
        List<String> lines = new ArrayList<>();
        lines.add("# Expected diagnostics for the corpus files that are meant to fail.");
        lines.add("# Format: <path relative to parsetest> TAB <VRLcode@startLine:startChar-endLine:endChar>"
                + " [ ; ...] or \"-\" for none.");
        lines.add("# Lines and characters are 0-based UTF-16 units, exactly what publishDiagnostics sends.");
        lines.add("# Regenerate with -Dvrml.updateGolden=true; an entry is a claim about where the mistake"
                + " is, so review the diff.");
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            String note = notes.get(entry.getKey());
            lines.add(entry.getKey() + "\t" + entry.getValue()
                    + (note == null ? "\t# TODO reason" : "\t# " + note));
        }
        Files.write(goldenFile(), String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    /** The trailing prose of each line, keyed by path, so a rewrite keeps the reasoning. */
    private static Map<String, String> readNotes() throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isRegularFile(goldenFile())) {
            return out;
        }
        for (String line : Files.readAllLines(goldenFile(), StandardCharsets.UTF_8)) {
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
