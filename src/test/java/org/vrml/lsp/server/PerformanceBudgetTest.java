package org.vrml.lsp.server;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.vrml.lsp.diagnostics.DocumentAnalyzer;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.parser.VrmlParser;

/**
 * The budgets the design was written against, measured rather than asserted in prose.
 *
 * <p>Three numbers decide whether the server is usable: what a keystroke costs the thread that
 * answers the protocol, what a question about the text just typed costs, and how much memory is
 * held to answer it. All three are checked here, on a hand-written corpus scene and on a generated
 * five megabyte one.
 *
 * <p>So is the measurement that shaped the rest of the milestone. A 6.7 MB scene costs about a
 * hundred milliseconds to parse and about as much again for the semantic pass over the finished
 * tree, and any edit moves every offset after it - so re-parsing only the statements an edit
 * touched would still have left a keystroke on a file that size at several times the edit budget,
 * with the expensive pass untouched. What pays instead is that a document is analysed once per text
 * rather than once per request, which is why the five megabyte completion is met from the analysis
 * the diagnostics worker has already computed and not from a fresh parse.
 *
 * <p>That arrangement has one hazard, and one test here exists for it alone: analysis belongs to a
 * worker thread, so it must never hold the document it is reading while the protocol thread wants
 * to write it. Measured, an edit that shares the document's lock with an analysis of a 5 MB scene
 * waits a fifth of a second for it - well inside a p95 over a burst, and long enough to feel.
 *
 * <p>Timings wander on a machine with other things running, so every budget is taken at p95 over a
 * burst after a warm-up rather than from a single sample, and the stress numbers are printed as well
 * as checked.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class PerformanceBudgetTest {

    /** What the plan allows a single-character edit; anything more and typing feels like typing. */
    private static final long EDIT_BUDGET_MS = 15;

    /** What the plan allows a completion, from the request to the answer. */
    private static final long COMPLETION_BUDGET_MS = 30;

    /** What the plan allows a first parse of a 5 MB file. */
    private static final long FIRST_PARSE_BUDGET_MS = 250;

    /** What the plan allows this process to hold. */
    private static final long HEAP_BUDGET_MB = 300;

    private static final String HEADER = "#VRML V2.0 utf8\n";
    private static final int MEGABYTE = 1 << 20;

    /** How many keystrokes to time; enough that one pause cannot decide a p95. */
    private static final int BURST = 200;

    /** One measured call: how long it took and how much there was to answer with. */
    private record Timing(long nanos, int items) {
    }

    /** Types into an open document the way a client does: a range, a character, a new version. */
    private static final class Typing {

        private final VrmlTextDocumentService service;
        private final VrmlDocument doc;
        private final String uri;
        private int version = 1;

        Typing(VrmlTextDocumentService service, Opened opened) {
            this.service = service;
            this.doc = opened.doc();
            this.uri = opened.uri();
        }

        /**
         * Inserts a character at the start of a random line, which is typing at the safest spot.
         *
         * <p>The clock starts before anything touches the document, including the line lookup: a
         * client sends a line number and the server has to turn it into an offset, and that is as
         * much a part of the keystroke as the splice that follows it. Measuring only the splice
         * would time the one step that cannot block.
         */
        Timing oneCharacter(Random random) {
            long t0 = System.nanoTime();
            int line = random.nextInt(doc.lineCount());
            DidChangeTextDocumentParams params = new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(uri, ++version),
                    List.of(new TextDocumentContentChangeEvent(
                            new Range(new Position(line, 0), new Position(line, 0)), "0")));
            service.didChange(params);
            return new Timing(System.nanoTime() - t0, 1);
        }
    }

    private static final class Timings {

        private final List<Long> millis = new ArrayList<>();
        private int withCandidates;

        void add(Timing timing) {
            millis.add(TimeUnit.NANOSECONDS.toMillis(timing.nanos()));
            if (timing.items() > 0) {
                withCandidates++;
            }
        }

        long p95() {
            List<Long> sorted = new ArrayList<>(millis);
            Collections.sort(sorted);
            return sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.95)));
        }

        long max() {
            return Collections.max(millis);
        }

        /** Whether the burst ever produced candidates, so a cheap answer cannot be an empty one. */
        boolean measuredSomething() {
            return withCandidates > 0;
        }
    }

    /** A document, its store and the URI a request can ask for it by. */
    private record Opened(String uri, DocumentStore store, VrmlDocument doc) {
    }

    /**
     * Closes {@code opened} and stops the analysis worker, as shutting the server down would.
     *
     * <p>Worth doing in the tests that type, because typing asks the diagnostics worker to analyse
     * the document and that thread stays alive holding the store it was given: an open 5 MB scene
     * left behind by one test is heap every later test in the same JVM then pays for, and an
     * analysis still running at the end of one is a fifth of a second of memory that has not been
     * noticed as free yet.
     */
    private static void close(VrmlTextDocumentService service, Opened opened) {
        service.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(opened.uri())));
        service.shutdownDiagnostics();
    }

    /**
     * Opens {@code text} the way a client would.
     *
     * <p>The scene is written into the test's own directory because a completion lists the open
     * document's sibling {@code .wrl} files for {@code EXTERNPROTO} suggestions, and timing that
     * against a URI with no directory behind it would be measuring the filesystem instead.
     */
    private static Opened open(Path dir, String text) throws IOException {
        Path scene = dir.resolve("perf.wrl");
        Files.writeString(scene, text);
        String uri = scene.toUri().toString();
        DocumentStore store = new DocumentStore();
        store.open(uri, 1, text);
        return new Opened(uri, store, store.get(uri));
    }

    /** A scene the size the plan sets its budget by; generated, so the bytes are not the point. */
    private static String syntheticScene(int megabytes) {
        StringBuilder out = new StringBuilder(megabytes * MEGABYTE);
        out.append(HEADER);
        for (int i = 0; out.length() < megabytes * MEGABYTE; i++) {
            out.append("DEF t").append(i).append(" Transform {\n")
                    .append("  translation ").append(i).append(" 0 0\n")
                    .append("  children [ Shape { geometry Box { size 1 1 1 } appearance ")
                    .append("Appearance { material Material { diffuseColor 1 0 0 } } } ]\n")
                    .append("}\n");
        }
        return out.toString();
    }

    /** The largest hand-written scene in the corpus, which is what an author actually opens. */
    private static String realScene() throws IOException {
        Path file = Path.of(System.getProperty("parsetest.dir"), "exporter", "NancyPrototypes.wrl");
        assertTrue(Files.isReadable(file), "expected the corpus's large scene at " + file);
        return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    }

    @Test
    void aFiveMegabyteFileIsParsedWithinAQuarterOfASecond() {
        // Warmed on a tenth of the size first: what is budgeted is parsing a big file, not the
        // interpreter noticing that the loop exists.
        VrmlParser.parse(syntheticScene(1));
        String scene = syntheticScene(5);

        long t0 = System.nanoTime();
        ParseResult parse = VrmlParser.parse(scene);
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertTrue(parse.tokens().size() > 1_000_000,
                "the scene should be big enough for the number to mean anything: "
                        + parse.tokens().size() + " tokens");
        System.out.println("budget: first parse of " + scene.length() + " chars took " + took
                + " ms for " + parse.tokens().size() + " tokens");
        assertTrue(took < FIRST_PARSE_BUDGET_MS, "a first parse of a 5 MB scene took " + took
                + " ms, over the " + FIRST_PARSE_BUDGET_MS + " ms budget");
    }

    /**
     * What the plan budgets: a one-character {@code didChange}, end to end.
     *
     * <p>Taken through the service rather than by calling the document directly, because that is
     * the call the client makes and because the edit also asks the diagnostics worker to start
     * analysing - which is the load typing actually competes with. The worst keystrokes in a burst
     * are the JVM collecting the text it replaced, which is why the budget is a percentile.
     */
    @Test
    void aKeystrokeCostsTheProtocolThreadLessThanTheEditBudget(@TempDir Path dir)
            throws IOException {
        for (String scene : List.of(realScene(), syntheticScene(5))) {
            Opened opened = open(dir, scene);
            VrmlTextDocumentService service = new VrmlTextDocumentService(opened.store(),
                    new FormattingSettings());
            Typing typing = new Typing(service, opened);
            Random random = new Random(11);
            // One keystroke first, so the classes the splice loads are not counted as typing.
            typing.oneCharacter(random);

            Timings edits = new Timings();
            for (int i = 0; i < BURST; i++) {
                edits.add(typing.oneCharacter(random));
            }
            System.out.println("budget: " + scene.length() + " char document, "
                    + opened.doc().lineCount() + " lines, keystroke p95 " + edits.p95()
                    + " ms, max " + edits.max() + " ms");
            close(service, opened);
            assertTrue(edits.p95() < EDIT_BUDGET_MS, "a keystroke cost " + edits.p95()
                    + " ms at p95 on a " + scene.length() + " character document");
        }
    }

    /**
     * The one thing an edit must never do is wait for somebody else's analysis.
     *
     * <p>The analysis runs on the diagnostics worker's thread, and it only stays off the critical
     * path if it does not hold the document while it runs. A 5 MB scene takes a few hundred
     * milliseconds to analyse and about one to edit, so what is asserted is the ratio: the budget on
     * its own would still pass while the average keystroke queued behind the worker. And the edit is
     * made the way a client makes it, because a keystroke waits on the first look-up it does, not
     * only on the splice - timing the splice alone measures nothing.
     */
    @Test
    void anEditDoesNotWaitForAnAnalysisInTheBackground(@TempDir Path dir) throws Exception {
        Opened opened = open(dir, syntheticScene(5));
        VrmlDocument doc = opened.doc();
        VrmlTextDocumentService service = new VrmlTextDocumentService(opened.store(),
                new FormattingSettings());
        Typing typing = new Typing(service, opened);
        doc.analyze();
        // An edit after that is a cache miss, so the thread below really does have work to do.
        typing.oneCharacter(new Random(2));

        long t0 = System.nanoTime();
        CountDownLatch entered = new CountDownLatch(1);
        Thread computing = new Thread(() -> {
            entered.countDown();
            doc.analyze();
        }, "analysing-in-the-background");
        computing.setDaemon(true);
        computing.start();
        long editNanos;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the background analysis never started");
            // A short settle, so the edit lands inside an analysis rather than in front of it: the
            // call takes a few hundred milliseconds here and thread start takes about none.
            Thread.sleep(100);
            editNanos = typing.oneCharacter(new Random(1)).nanos();
        } finally {
            computing.join();
        }
        long editMs = TimeUnit.NANOSECONDS.toMillis(editNanos);
        long analysisMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        close(service, opened);
        System.out.println("budget: an edit made during an analysis costs " + editMs
                + " ms, against an analysis that costs " + analysisMs + " ms");
        assertTrue(analysisMs > 50, "nothing was being analysed in the background: the thread was"
                + " done in " + analysisMs + " ms, so this says nothing about waiting");
        // No edit budget here: the machine has a core busy analysing 5 MB, so the copy costs what
        // copying costs under load - fifteen milliseconds rather than the one it costs on an idle
        // one. What is claimed is the ratio, and it is the ratio that says whether the edit ran
        // alongside the analysis or after it: 16 ms against 360 measured here, and 213 against 314
        // measured with the analysis holding the document.
        assertTrue(editMs * 2 < analysisMs, "an edit made while " + analysisMs
                + " ms of analysis was running took " + editMs + " ms, which is a queue, not a copy");
    }

    /** Edits the document without going through the service, so no analysis is asked for. */
    private static long typeOneCharacter(VrmlDocument doc, Random random) {
        int line = random.nextInt(doc.lineCount());
        long t0 = System.nanoTime();
        doc.applyEdit(new Range(new Position(line, 0), new Position(line, 0)), "0");
        return System.nanoTime() - t0;
    }

    /**
     * A popup asked for in a scene the size of a real project, both ways round: the first request
     * after an edit pays for the analysis, the ones after it read that answer.
     */
    @Test
    void aCompletionOnALargeSceneIsWithinBudget(@TempDir Path dir) throws IOException {
        Opened opened = open(dir, realScene());
        VrmlTextDocumentService service = new VrmlTextDocumentService(opened.store(),
                new FormattingSettings());
        Random random = new Random(5);
        Timings cold = new Timings();
        Timings warm = new Timings();
        requestCompletion(service, opened, random); // warm-up, before the numbers are taken

        for (int i = 0; i < 40; i++) {
            boolean editedFirst = i % 2 == 1;
            if (editedFirst) {
                typeOneCharacter(opened.doc(), random);
            }
            Timing timing = requestCompletion(service, opened, random);
            if (editedFirst) {
                cold.add(timing);
            } else {
                warm.add(timing);
            }
        }
        System.out.println("budget: completion on a " + opened.doc().length() + " char scene costs "
                + cold.p95() + " ms at p95 right after an edit and " + warm.p95()
                + " ms at p95 with nothing changed, max " + Math.max(cold.max(), warm.max())
                + " ms");
        assertTrue(warm.measuredSomething() && cold.measuredSomething(),
                "no request in either burst had candidates to time, so nothing was measured");
        assertTrue(warm.p95() < COMPLETION_BUDGET_MS, "a completion on unchanged text cost "
                + warm.p95() + " ms at p95, over the " + COMPLETION_BUDGET_MS + " ms budget");
        assertTrue(cold.p95() < COMPLETION_BUDGET_MS, "a completion that had to analyse a "
                + opened.doc().length() + " character scene first cost " + cold.p95()
                + " ms at p95, over the " + COMPLETION_BUDGET_MS + " ms budget");
    }

    /**
     * The five megabyte case, which is what the per-text analysis is for.
     *
     * <p>Re-analysing a document that size cannot be brought inside the edit budget by any choice of
     * re-parse granularity that keeps the semantic pass honest - a DEF, the USE that reads it and
     * the two ends of a ROUTE belong to different statements - so the promise checked here is the one
     * that can be kept: once something has analysed the text, a request about it costs only what the
     * request itself costs.
     */
    @Test
    void aCompletionOnAFiveMegabyteSceneIsAnsweredFromTheAnalysis(@TempDir Path dir)
            throws IOException {
        Opened opened = open(dir, syntheticScene(5));
        VrmlTextDocumentService service = new VrmlTextDocumentService(opened.store(),
                new FormattingSettings());
        Random random = new Random(3);

        Timing cold = requestCompletion(service, opened, random);

        // What the diagnostics worker does between the keystroke and the popup.
        opened.doc().analyze();
        Timings warm = new Timings();
        for (int i = 0; i < 20; i++) {
            warm.add(requestCompletion(service, opened, random));
        }
        System.out.println("budget: completion on a " + opened.doc().length()
                + " char scene costs " + TimeUnit.NANOSECONDS.toMillis(cold.nanos())
                + " ms before anything has analysed it and " + warm.p95() + " ms at p95 after, max "
                + warm.max() + " ms");
        assertTrue(warm.measuredSomething(), "the burst asked only where nothing is to complete");
        assertTrue(warm.p95() < COMPLETION_BUDGET_MS, "a completion on text that had been analysed"
                + " cost " + warm.p95() + " ms at p95 on a 5 MB scene");
    }

    /** Asks for candidates in a node body and reports how long the answer took. */
    private static Timing requestCompletion(VrmlTextDocumentService service, Opened opened,
            Random random) {
        // A line one quarter into the scene and eighteen characters in: inside a `DEF x Transform {`,
        // which is where a field name is asked for and the answer is long enough to encode.
        int statements = Math.max(1, opened.doc().lineCount() / 4);
        CompletionParams params = new CompletionParams(new TextDocumentIdentifier(opened.uri()),
                new Position(1 + 4 * random.nextInt(statements), 14));
        long t0 = System.nanoTime();
        Either<List<CompletionItem>, CompletionList> answer = service.completion(params).join();
        List<CompletionItem> items = answer.getLeft();
        assertTrue(items != null, "a completion answered with nothing at all");
        return new Timing(System.nanoTime() - t0, items.size());
    }

    /** The cache's contract, which is the one thing a cached analysis can get wrong. */
    @Test
    void aDocumentIsAnalysedOncePerTextAndAgainAfterAnEdit(@TempDir Path dir) throws IOException {
        Opened opened = open(dir, HEADER + "Box {\n  size 2 2 2\n}\n");
        VrmlDocument doc = opened.doc();

        DocumentAnalyzer.Analysis before = doc.analyze();
        assertSame(before, doc.analyze(), "the same text was analysed twice");
        assertSame(doc.text(), before.parse().source(),
                "the analysis is not of the text the document holds");

        doc.applyEdit(new Range(new Position(1, 3), new Position(1, 3)), "  ");
        DocumentAnalyzer.Analysis after = doc.analyze();
        assertNotSame(before, after, "an edit did not invalidate the analysis");
        assertSame(after, doc.analyze(), "the new text was analysed twice");
        assertSame(doc.text(), after.parse().source(),
                "the answer about the edited text is about something else");
    }

    /**
     * What one big open document costs to keep analysed.
     *
     * <p>A delta rather than an absolute: the forked JVM runs every test here in one process, so a
     * figure that included what earlier tests left reachable would say nothing about this document.
     * The token stream and the tree are the memory, and the cache holds them for as long as the text
     * is what the client is looking at; this is the number that says whether holding them is
     * affordable, and the keystroke test above is what says it is worth it.
     */
    @Test
    void memoryHeldForABigOpenSceneStaysWithinBudget(@TempDir Path dir) throws IOException {
        long before = usedHeapMb();
        Opened opened = open(dir, syntheticScene(5));
        opened.doc().analyze();

        long held = usedHeapMb() - before;
        System.out.println("budget: a 5 MB scene with its analysis holds " + held + " MB on top of"
                + " the " + before + " MB already in use, against a "
                + Runtime.getRuntime().maxMemory() / MEGABYTE + " MB maximum");
        assertTrue(held > 10, "nothing was retained, so the measurement is noise: " + held + " MB");
        assertTrue(held < HEAP_BUDGET_MB, "the document held " + held + " MB, over the "
                + HEAP_BUDGET_MB + " MB budget");
    }

    /** Bytes the live objects occupy, with the unreachable ones collected away first. */
    private static long usedHeapMb() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return (runtime.totalMemory() - runtime.freeMemory()) / MEGABYTE;
    }
}
