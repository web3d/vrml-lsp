package org.vrml.lsp.services;

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
import org.vrml.lsp.diagnostics.Issue;
import org.vrml.lsp.lexer.Token;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.parser.VrmlParser;

/**
 * Every corpus file through the reflow, which is where a formatter earns the right to exist: 263
 * hand-written files, each one a layout somebody chose, several of them deliberately broken.
 *
 * <p>The assertions are the promises the formatter makes rather than any expected output - a
 * layout decided by hand cannot be re-derived by rule. Nothing the grammar read may change, no comma
 * or comment mark may appear or vanish, the edits a client applies must add up to exactly the text
 * this layer produces, and a second pass must find nothing to do. The last one is the property the
 * corpus is the only place to test: files in the corpus are wrapped at wildly different widths and
 * by hand, so an algorithm that peeked at the author's whitespace would fail to repeat itself here
 * long before it failed in review.
 *
 * <p>Files with a structural error are reformatted anyway rather than skipped, since refusing them
 * is the server's policy and not this layer's; what must hold of them is that the bytes are safe.
 *
 * <p>Read as ISO-8859-1 for the same reason {@code CorpusRoundTripTest} is: one character per byte,
 * so the assertions are about bytes and not about decoders.
 */
class FormatterCorpusTest {

    /** Each corpus failure costs one line; anything longer buries the pattern. */
    private static final int MAX_REPORTED = 25;

    /** The one line every valid file starts with, which no reflow may move. */
    private static final String HEADER = "#VRML V2.0 utf8\n";

    private static final Formatter.Options TWO = new Formatter.Options(2, 80);

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

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void everyCorpusFileReflowsWithoutLosingAByte() throws IOException {
        List<Path> files = corpusFiles();
        assertTrue(files.size() > 200, "expected the whole corpus, found " + files.size());

        List<String> failures = new ArrayList<>();
        int refused = 0;
        int changed = 0;
        long slowestNanos = 0;
        String slowest = "";

        for (Path file : files) {
            String source = read(file);
            long t0 = System.nanoTime();
            ParseResult parse = VrmlParser.parse(source);
            String once = Formatter.format(parse, TWO);
            long took = System.nanoTime() - t0;
            if (took > slowestNanos) {
                slowestNanos = took;
                slowest = file.getFileName().toString();
            }
            if (parse.hasErrors()) {
                refused++;
            }
            if (!source.equals(once)) {
                changed++;
            }

            List<String> problems = check(file.getFileName().toString(), source, parse, once);
            if (failures.size() < MAX_REPORTED) {
                for (String problem : problems) {
                    failures.add(problem);
                }
            }
        }

        System.out.println("corpus reflow: " + files.size() + " files, " + changed + " relaid out, "
                + refused + " the server would decline, slowest "
                + TimeUnit.NANOSECONDS.toMillis(slowestNanos) + " ms in " + slowest
                + (failures.isEmpty() ? "" : ", " + failures.size() + " files failed"));

        assertEquals(0, failures.size(), "reflow failures:\n" + String.join("\n", failures));
    }

    /** Everything that must be true of one reflow; empty when all of it is. */
    private static List<String> check(String name, String source, ParseResult parse, String once) {
        List<String> problems = new ArrayList<>();
        if (!significant(source).equals(significant(once))) {
            problems.add(name + ": a character the grammar read changed");
        }
        if (count(source, ',') != count(once, ',')) {
            problems.add(name + ": comma count " + count(source, ',') + " then " + count(once, ','));
        }
        if (count(source, '#') != count(once, '#')) {
            problems.add(name + ": comment count " + count(source, '#') + " then " + count(once, '#'));
        }
        if (!apply(source, Formatter.edits(parse, TWO)).equals(once)) {
            problems.add(name + ": the edits do not add up to the formatted text");
        }
        ParseResult reparsed = VrmlParser.parse(once);
        if (!once.equals(Formatter.format(reparsed, TWO))) {
            problems.add(name + ": formatting the result again changed it at offset "
                    + firstDifference(once, Formatter.format(reparsed, TWO)));
        }
        if (!codes(parse).equals(codes(reparsed))) {
            problems.add(name + ": reflow changed the structural verdict " + codes(parse) + " -> "
                    + codes(reparsed));
        }
        if (source.startsWith(HEADER) && !once.startsWith(HEADER)) {
            problems.add(name + ": the header left the first line");
        }
        return problems;
    }

    /** The significant tokens, concatenated: what a whitespace-only reflow may not touch. */
    private static String significant(String document) {
        StringBuilder words = new StringBuilder();
        for (Token token : VrmlParser.parse(document).significantTokens()) {
            words.append(document, token.start, token.end);
        }
        return words.toString();
    }

    /** The structural errors by code, so a reflow can be shown not to have created or hidden one. */
    private static List<Integer> codes(ParseResult parse) {
        List<Integer> found = new ArrayList<>();
        for (Issue issue : parse.issues()) {
            found.add(issue.code());
        }
        return found;
    }

    private static String apply(String document, List<Formatter.Edit> edits) {
        StringBuilder out = new StringBuilder(document.length());
        int at = 0;
        for (Formatter.Edit edit : edits) {
            out.append(document, at, edit.start()).append(edit.text());
            at = edit.end();
        }
        return out.append(document, at, document.length()).toString();
    }

    private static int count(String text, char wanted) {
        int found = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == wanted) {
                found++;
            }
        }
        return found;
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
}
