package org.vrml.lsp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Diagnostics reaching a client: what is published, when, and at which position.
 *
 * <p>The parser's own verdicts are pinned elsewhere; this is about the delivery contract - a
 * notification per document state, none for a state the document has already left, and an empty
 * list when a buffer closes so stale markers cannot survive.
 */
@Timeout(value = 10, unit = java.util.concurrent.TimeUnit.SECONDS)
class DiagnosticPublicationTest {

    private static final String URI = "file:///t.wrl";
    private static final String HEADER = "#VRML V2.0 utf8\n";

    /** Records every notification, in the order the publisher sent them. */
    private static final class Recorder implements LanguageClient {

        private final List<PublishDiagnosticsParams> published = new CopyOnWriteArrayList<>();

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            published.add(params);
        }

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void showMessage(MessageParams params) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams params) {
        }

        /**
         * Waits until a notification satisfies {@code wanted}.
         *
         * <p>Polling rather than a latch because the publisher coalesces: the number of
         * notifications for a given number of edits is deliberately not fixed, only the last one
         * is.
         */
        PublishDiagnosticsParams await(Predicate<PublishDiagnosticsParams> wanted) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                for (PublishDiagnosticsParams params : published) {
                    if (wanted.test(params)) {
                        return params;
                    }
                }
                Thread.onSpinWait();
            }
            throw new AssertionError("no notification matched; saw " + published.size()
                    + " total: " + published);
        }

        List<PublishDiagnosticsParams> all() {
            return published;
        }
    }

    private static VrmlTextDocumentService service(Recorder client, String text, int version) {
        // The settings are nobody's business here, but the service has exactly one way to be built.
        VrmlTextDocumentService service = new VrmlTextDocumentService(new DocumentStore(),
                new FormattingSettings());
        service.setClient(client);
        service.didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(URI, "vrml97", version, text)));
        return service;
    }

    /** The notification that names this version, which is the one the client must obey. */
    private static PublishDiagnosticsParams awaitVersion(Recorder client, int version) {
        return client.await(p -> p.getVersion() != null && p.getVersion() == version);
    }

    @Test
    void aFileWithoutAHeaderIsReportedOnLineOne() {
        Recorder client = new Recorder();
        service(client, "Box {\n}\n", 1);

        PublishDiagnosticsParams params = awaitVersion(client, 1);
        List<Diagnostic> diagnostics = params.getDiagnostics();
        assertEquals(1, diagnostics.size(), "a missing header is one problem, not one per token");
        Diagnostic only = diagnostics.get(0);
        assertEquals("VRL1016", code(only), "no comment at all is MISSING_HEADER, not MALFORMED");
        assertEquals(0, only.getRange().getStart().getLine());
        assertEquals(0, only.getRange().getEnd().getLine(), "the marker stays on line 1");
        assertEquals("vrml-lsp", only.getSource());
        assertEquals(URI, params.getUri());
    }

    @Test
    void aWellFormedFilePublishesAnEmptyList() {
        Recorder client = new Recorder();
        service(client, HEADER + "Box {\n  size 2 2 2\n}\n", 1);

        assertTrue(awaitVersion(client, 1).getDiagnostics().isEmpty(),
                "a clean file must send an explicit empty list, which is what clears old markers");
    }

    @Test
    void editingADocumentReplacesItsDiagnostics() {
        Recorder client = new Recorder();
        VrmlTextDocumentService service = service(client, HEADER + "Box {\n  size 2 2 2\n", 1);
        client.await(p -> !p.getDiagnostics().isEmpty());

        service.didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(URI, 2),
                List.of(new TextDocumentContentChangeEvent(HEADER + "Box {\n  size 2 2 2\n}\n"))));

        PublishDiagnosticsParams fixed = awaitVersion(client, 2);
        assertTrue(fixed.getDiagnostics().isEmpty(),
                "the '}' that closes the node also closes the complaint: "
                        + fixed.getDiagnostics());
    }

    @Test
    void closingADocumentClearsItsMarkers() {
        Recorder client = new Recorder();
        VrmlTextDocumentService service = service(client, "no header at all\n", 1);
        client.await(p -> !p.getDiagnostics().isEmpty());

        service.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(URI)));

        assertTrue(client.await(p -> URI.equals(p.getUri()) && p.getDiagnostics().isEmpty()
                && p.getVersion() == null).getDiagnostics().isEmpty());
        assertTrue(service.store().get(URI) == null, "and the text itself is let go");
    }

    @Test
    void oneBrokenStatementCostsOneDiagnostic() {
        Recorder client = new Recorder();
        service(client, HEADER + "\nDEF INLINE Inline {}\n\nIMPORT INLINE.foo AS bar\n", 1);

        List<Diagnostic> diagnostics = awaitVersion(client, 1).getDiagnostics();
        assertEquals(1, diagnostics.size(),
                "IMPORT is one mistake; its five tokens used to be five errors: " + diagnostics);
        Diagnostic first = diagnostics.get(0);
        assertEquals(4, first.getRange().getStart().getLine(), "point at the IMPORT line");
        assertEquals("VRL1002", code(first));
    }

    @Test
    void notificationsAreOrderedByPositionSoClientsDoNotShuffleThem() {
        Recorder client = new Recorder();
        service(client, HEADER + "\nBox {\n  size 2 2 2\nGroup {\n", 1);

        List<Diagnostic> diagnostics = awaitVersion(client, 1).getDiagnostics();
        assertTrue(diagnostics.size() >= 2, "an unclosed Box and a bad Group line, got "
                + diagnostics);
        for (int n = 1; n < diagnostics.size(); n++) {
            Diagnostic before = diagnostics.get(n - 1);
            Diagnostic after = diagnostics.get(n);
            int order = Integer.compare(before.getRange().getStart().getLine(),
                    after.getRange().getStart().getLine());
            assertTrue(order <= 0, "out of order: " + diagnostics);
        }
    }

    @Test
    void aVersionTheDocumentHasOutgrownIsNeverPublished() {
        Recorder client = new Recorder();
        DocumentStore store = new DocumentStore();
        // The store is on v9; a result computed for v3 is history and must not overwrite it.
        store.open(URI, 9, HEADER + "Box {}\n");
        DiagnosticPublisher publisher = new DiagnosticPublisher(store, () -> client);

        publisher.request(URI, 3, "Box {\n");
        publisher.shutdown();

        assertEquals(List.of(), client.all(), "a stale request is dropped, not sent and retracted");
    }

    @Test
    void aClosedDocumentIsNeverPublishedAgain() {
        Recorder client = new Recorder();
        DocumentStore store = new DocumentStore();
        store.open(URI, 1, "Box {\n");
        DiagnosticPublisher publisher = new DiagnosticPublisher(store, () -> client);

        store.close(URI);
        publisher.request(URI, 1, "Box {\n");
        publisher.shutdown();

        assertEquals(List.of(), client.all());
    }

    private static String code(Diagnostic diagnostic) {
        Either<String, Integer> code = diagnostic.getCode();
        return code == null ? null : code.getLeft();
    }
}
