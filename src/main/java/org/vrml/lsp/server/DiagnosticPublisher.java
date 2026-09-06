package org.vrml.lsp.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.vrml.lsp.Log;
import org.vrml.lsp.diagnostics.DocumentAnalyzer;
import org.vrml.lsp.diagnostics.Issue;
import org.vrml.lsp.text.LineIndex;

/**
 * Turns document content into {@code textDocument/publishDiagnostics} notifications.
 *
 * <p>Off the protocol thread, because parsing is not free - the performance budget allows a 5 MB
 * file a quarter of a second - and the thread that reads JSON-RPC must stay free to answer the
 * next hover or completion request instead of sitting in a parse.
 *
 * <p>Requests coalesce: only the newest version of a document is worth analysing, so a burst of
 * keystrokes overwrites itself in {@link #pending} and the single worker drains whatever is there.
 * A result is dropped when the document has moved on in the meantime, which is what keeps an old
 * version's error list from flashing back over a newer one.
 */
final class DiagnosticPublisher {

    /** Value of {@code Diagnostic.source}; clients use it to tell servers apart. */
    static final String SOURCE = "vrml-lsp";

    /** One document as it looked when the request came in. */
    private record Request(int version, String text) {
    }

    private final DocumentStore store;
    private final Supplier<LanguageClient> client;
    private final Map<String, Request> pending = new ConcurrentHashMap<>();
    private final ExecutorService worker;
    /** True while {@link #drain()} is queued or running, so at most one task is ever in flight. */
    private final AtomicBoolean running = new AtomicBoolean();

    DiagnosticPublisher(DocumentStore store, Supplier<LanguageClient> client) {
        this.store = store;
        this.client = client;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "vrml-diagnostics");
            // Daemon: an editor that forgets to shut us down must still be able to exit.
            t.setDaemon(true);
            return t;
        });
    }

    /** Ask for diagnostics of {@code text}, which must be the document's content right now. */
    void request(String uri, int version, String text) {
        pending.put(uri, new Request(version, text));
        if (running.compareAndSet(false, true)) {
            worker.execute(this::drain);
        }
    }

    /**
     * Say that a document has nothing to show any more.
     *
     * <p>Clients keep the last notification they were sent, so closing a buffer without an empty
     * list leaves squiggles hanging over whatever file the tab shows next.
     */
    void cleared(String uri) {
        pending.remove(uri);
        send(uri, List.of(), null);
    }

    /** Stop the worker; in-flight parses are pure computation, so nothing needs cancelling. */
    void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(1, TimeUnit.SECONDS)) {
                Log.warn("diagnostics worker did not stop within a second");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        try {
            while (!pending.isEmpty()) {
                Map<String, Request> batch = new HashMap<>(pending);
                pending.keySet().removeAll(batch.keySet());
                for (Map.Entry<String, Request> entry : batch.entrySet()) {
                    analyseAndPublish(entry.getKey(), entry.getValue());
                }
            }
        } finally {
            running.set(false);
            // A request that arrived while the last batch was being analysed has to re-arm the
            // worker; without this, its diagnostics would wait for the next edit.
            if (!pending.isEmpty() && running.compareAndSet(false, true)) {
                worker.execute(this::drain);
            }
        }
    }

    private void analyseAndPublish(String uri, Request request) {
        VrmlDocument doc = store.get(uri);
        if (doc == null || doc.version() != request.version()) {
            return;
        }
        List<Diagnostic> diagnostics;
        try {
            // The document's own analysis rather than a private parse: a hover or an outline that
            // came in while this was running is then served from this text instead of parsing it a
            // second time, in whichever order the two arrived.
            DocumentAnalyzer.Analysis analysis = doc.analyze();
            if (analysis.parse().source() != request.text()) {
                // By identity, which is free: the text moved on while this was computed, so the
                // numbers below would describe a version the client has already edited past. The
                // edit that moved it asked for its own analysis, and that one will be sent.
                return;
            }
            diagnostics = toDiagnostics(analysis.issues(), analysis.parse().source());
        } catch (RuntimeException e) {
            // An analysis bug must not kill the worker thread: the next edit would then never be
            // reported at all. Log loudly and keep the previous markers on screen.
            Log.warn("analysis failed for " + uri + ": " + e);
            Log.warn(stack(e));
            return;
        }
        send(uri, diagnostics, request);
    }

    private void send(String uri, List<Diagnostic> diagnostics, Request request) {
        LanguageClient lc = client.get();
        if (lc == null) {
            return;
        }
        PublishDiagnosticsParams params = new PublishDiagnosticsParams(uri, diagnostics);
        if (request != null && request.version() > 0) {
            params.setVersion(request.version());
        }
        try {
            lc.publishDiagnostics(params);
        } catch (RuntimeException e) {
            Log.warn("publishDiagnostics for " + uri + " could not be sent: " + e);
        }
    }

    /**
     * Map issues onto protocol ranges.
     *
     * <p>Positions are computed from the snapshot the issues were found in, never from the live
     * document: the text may have been edited while this ran, and ranges that mix the two are
     * worse than a notification that arrives a moment late.
     */
    static List<Diagnostic> toDiagnostics(List<Issue> issues, CharSequence text) {
        LineIndex index = new LineIndex(text);
        List<Diagnostic> out = new ArrayList<>(issues.size());
        for (Issue issue : issues) {
            Diagnostic d = new Diagnostic();
            d.setRange(new Range(position(index, issue.start()), position(index, issue.end())));
            d.setSeverity(issue.severity().toLsp());
            d.setCode(Either.forLeft(issue.codeLabel()));
            d.setSource(SOURCE);
            d.setMessage(issue.message());
            out.add(d);
        }
        return out;
    }

    private static Position position(LineIndex index, int offset) {
        return new Position(index.lineOf(offset), index.characterOf(offset));
    }

    private static String stack(Throwable e) {
        StringBuilder sb = new StringBuilder(e.toString());
        for (StackTraceElement frame : e.getStackTrace()) {
            if (!frame.getClassName().startsWith("org.vrml.")) {
                continue;
            }
            sb.append("\n\tat ").append(frame);
            if (sb.length() > 2000) {
                break;
            }
        }
        return sb.toString();
    }
}
