package org.vrml.lsp.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.vrml.lsp.lexer.Token;
import org.vrml.lsp.lexer.TokenType;

/**
 * The M1 acceptance gate: parse every {@code .wrl} in the project's copy of the Xj3D parse-test
 * corpus and print the CST back. Byte equality is the whole point - a server that loses a character
 * while re-deriving a document from its own tree cannot format or edit safely ever again.
 *
 * <p>Files are read as ISO-8859-1 so that one character is one byte. That keeps the assertion
 * about bytes rather than about decoders, and it covers the corpus honestly, including the six
 * files that start with a UTF-8 BOM - broken input a real server must survive either way.
 *
 * <p>The issue counts printed here are therefore the latin-1 view and are deliberately not the
 * contract: a BOM is one forgiving character under UTF-8 and three identifier characters here.
 * {@link CorpusIssueBaselineTest} owns the numbers the server actually reports.
 */
class CorpusRoundTripTest {

    /** Each corpus failure costs one line; anything longer buries the pattern. */
    private static final int MAX_REPORTED = 25;

    private static Path corpusRoot() {
        String dir = System.getProperty("parsetest.dir");
        assertTrue(dir != null && Files.isDirectory(Path.of(dir)),
                "parsetest.dir must point at the corpus this project carries, whose contents"
                        + " parsetest/PROVENANCE.txt describes; got " + dir);
        return Path.of(dir);
    }

    private static List<Path> corpusFiles() {
        try (Stream<Path> walk = Files.walk(corpusRoot())) {
            List<Path> files = new ArrayList<>();
            walk.filter(p -> p.getFileName().toString().endsWith(".wrl")).sorted().forEach(files::add);
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void everyCorpusFileRoundTripsByteForByte() throws IOException {
        List<Path> files = corpusFiles();
        assertTrue(files.size() > 200, "expected the whole corpus, found " + files.size());

        List<String> failures = new ArrayList<>();
        int withIssues = 0;
        int totalIssues = 0;
        long slowestNanos = 0;
        String slowest = "";

        for (Path file : files) {
            String source = read(file);
            long t0 = System.nanoTime();
            ParseResult result = VrmlParser.parse(source);
            long took = System.nanoTime() - t0;
            if (took > slowestNanos) {
                slowestNanos = took;
                slowest = file.toString();
            }

            List<String> holes = result.cstHoles();
            if (!result.issues().isEmpty()) {
                withIssues++;
                totalIssues += result.issues().size();
            }
            if (failures.size() < MAX_REPORTED) {
                if (!holes.isEmpty()) {
                    failures.add(describe(file, "CST holes: " + holes));
                } else if (!source.equals(result.printCst())) {
                    failures.add(describe(file, "printCst differs at offset "
                            + firstDifference(source, result.printCst())));
                } else if (!source.equals(result.printTokens())) {
                    failures.add(describe(file, "token stream differs at offset "
                            + firstDifference(source, result.printTokens())));
                } else if (!result.coverageGaps().isEmpty()) {
                    failures.add(describe(file, "lexing gaps: " + result.coverageGaps()));
                }
            }
        }

        System.out.println("corpus (latin-1 view): " + files.size() + " files, " + withIssues
                + " with issues, " + totalIssues + " issues total, slowest "
                + TimeUnit.NANOSECONDS.toMillis(slowestNanos) + " ms in " + slowest
                + (failures.isEmpty() ? "" : ", " + failures.size() + " files failed"));

        assertEquals(0, failures.size(), "round-trip failures:\n" + String.join("\n", failures));
    }

    private static String describe(Path file, String what) {
        return file + ": " + what;
    }

    private static int firstDifference(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return i;
            }
        }
        return a.length() == b.length() ? -1 : n;
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void brokenFilesStillProduceACompleteTokenStream() throws IOException {
        // The corpus files Xj3D itself rejects: the lexer must tile them and the parser must
        // return a tree rather than throwing or spinning.
        List<Path> files = new ArrayList<>();
        for (Path file : corpusFiles()) {
            String name = file.getFileName().toString();
            if (name.startsWith("bad_") || name.contains("error") || file.getParent().getFileName()
                    .toString().contains("error")) {
                files.add(file);
            }
        }
        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            String source = read(file);
            ParseResult result = VrmlParser.parse(source);
            if (!result.coverageGaps().isEmpty()) {
                failures.add(file + " " + result.coverageGaps());
            }
            if (!source.equals(result.printCst())) {
                failures.add(file + " print mismatch");
            }
        }
        assertTrue(!files.isEmpty(), "no deliberately broken corpus files found");
        assertEquals(0, failures.size(), String.join("\n", failures));
    }

    @Test
    void garbageAndEdgeCasesNeverThrow() {
        List<String> inputs = List.of("", " ", "\n\n", "#VRML V2.0 utf8", "{", "}", "[]", "\"", "@",
                "DEF", "DEF x", "DEF x Box", "DEF x Box {", "Box {", "Box { }", "PROTO", "PROTO P",
                "PROTO P [", "PROTO P [] ", "PROTO P [] {}", "EXTERNPROTO", "ROUTE", "ROUTE a.b TO",
                "ROUTE a.b TO c.d", "Script {", "field", "eventIn", "IS", "TO", "NULL", "TRUE",
                "[", "a [ ]", "a [1", "a [\"x", "a [ [ ]", "[[[[[[[[]]]]]]]]", "{}{}{}{}{}",
                "\u0000\u0000\u0000", "\uFEFF\uFEFF", "\\", "|", "~", "a\u00a0b", ",", ",,,",
                "#\n#\n#\n", "\"a\n\"b\n\"c\n", "Box { size 1 1 1 } Box { size 2 }",
                "\t\r\n\f", "exposedField SFVec3f v", "USE", "USE noSuchName", "x 1 2 3 ] } [");
        for (String input : inputs) {
            ParseResult result = VrmlParser.parse(input);
            List<String> holes = result.cstHoles();
            assertEquals(0, holes.size(), () -> "[" + input + "] holes: " + holes);
            assertEquals(input, result.printCst(), () -> "for [" + input + "]");
            assertEquals(input, result.printTokens(), () -> "for [" + input + "]");
            for (Token token : result.tokens()) {
                assertTrue(token.start <= token.end, "bad span " + token);
                if (token.type != TokenType.EOF) {
                    assertTrue(token.end <= input.length(), "token past end: " + token);
                }
            }
        }
    }

    @Test
    void corpusIsNotEmpty() {
        assertTrue(corpusFiles().size() > 200, () -> "found " + corpusFiles().size() + " corpus files");
    }
}
