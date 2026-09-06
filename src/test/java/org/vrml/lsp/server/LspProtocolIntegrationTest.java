package org.vrml.lsp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DeclarationParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolCapabilities;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PositionEncodingKind;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.vrml.lsp.text.LineIndex;

/**
 * The server behind a real JSON-RPC connection, with the frames on the wire.
 *
 * <p>Everything else in this package calls the service objects directly, which cannot catch a
 * message that never leaves the process: a notification built for a client proxy that was never
 * connected, a capability declared but not honoured, an edit applied to text the analyser never
 * sees. Here both ends talk through {@link LSPLauncher} over in-memory pipes, so a test passes
 * only with what an editor would actually receive.
 *
 * <p>{@code exit} is never sent, because it calls {@link System#exit}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LspProtocolIntegrationTest {

    private static final String URI = "file:///integration.wrl";
    private static final String HEADER = "#VRML V2.0 utf8\n";

    /**
     * The scene the feature requests below point into.
     *
     * <p>One statement to a line on purpose: an answer is a {@link Range}, and a range is only
     * checkable by a reader who can count to the line it names.
     */
    private static final String SCENE = HEADER
            + "DEF sun DirectionalLight {\n"
            + "  intensity 0.9\n"
            + "}\n"
            + "DEF root Transform {\n"
            + "  translation 0 0 0\n"
            + "  children [ USE sun ]\n"
            + "}\n"
            + "ROUTE sun.intensity_changed TO root.set_translation\n";

    /** How the outline labels a {@code ROUTE}, which is a line with no name of its own. */
    private static final String ROUTE_LABEL = "sun.intensity_changed \u2192 root.set_translation";

    /**
     * The same scene as a keyboard produces it, for the requests that are about layout.
     *
     * <p>Everything {@link #SCENE} has, written without the breaks the grammar wants - and one
     * {@code Shape} nested deep enough to show what indentation is being asked for.
     */
    private static final String MESSY = HEADER
            + "DEF sun DirectionalLight{intensity 0.9}\n"
            + "DEF root Transform {  translation 0 0 0\n"
            + "  children [ Shape { geometry Box{size 1 1 1} } ]}\n"
            + "ROUTE sun.intensity_changed TO root.set_translation\n";

    /** What the reflow makes of {@link #MESSY} at two spaces, which is the corpus's own layout. */
    private static final String TIDY = HEADER
            + "DEF sun DirectionalLight {\n  intensity 0.9\n}\n"
            + "DEF root Transform {\n  translation 0 0 0\n  children [\n    Shape {\n"
            + "      geometry Box {\n        size 1 1 1\n      }\n    }\n  ]\n}\n"
            + "ROUTE sun.intensity_changed TO root.set_translation\n";

    /**
     * A value list long enough to have an opinion about the line width.
     *
     * <p>One line at the default eighty, several at anything narrower - which is what makes it the
     * document to test {@code vrml.maxColumn} with, since that setting is in no protocol object and so
     * can only reach the reflow by way of a configuration notification.
     */
    private static final String WIDE = HEADER
            + "DEF path Coordinate{point [ 0 0 0, 1 0 0, 2 0 0, 3 0 0, 4 0 0, 5 0 0 ]}\n";

    /** 64 KiB, so neither end can fill a pipe and block the other with messages this small. */
    private static final int PIPE_BUFFER = 1 << 16;

    /** Collects what the server sends, in arrival order. */
    private static final class ClientSide implements LanguageClient {

        private final BlockingQueue<PublishDiagnosticsParams> published = new LinkedBlockingQueue<>();
        private final BlockingQueue<MessageParams> shown = new LinkedBlockingQueue<>();

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            published.add(params);
        }

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void showMessage(MessageParams params) {
            shown.add(params);
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams params) {
        }

        /** The notification carrying {@code version}, waiting for it if it has not arrived. */
        PublishDiagnosticsParams awaitVersion(int version) throws InterruptedException {
            return await(params -> Integer.valueOf(version).equals(params.getVersion()),
                    "v" + version);
        }

        /** The notification that wipes a document's markers: empty, and no version to obey. */
        PublishDiagnosticsParams awaitCleared() throws InterruptedException {
            return await(params -> params.getDiagnostics().isEmpty() && params.getVersion() == null,
                    "an empty list with no version");
        }

        /**
         * The next thing the server volunteered, which is where a declined request has to explain
         * itself: the response itself carries nothing.
         */
        MessageParams awaitMessage() throws InterruptedException {
            MessageParams params = shown.poll(15, TimeUnit.SECONDS);
            assertNotNull(params, "the server said nothing about why it did nothing");
            return params;
        }

        private PublishDiagnosticsParams await(java.util.function.Predicate<PublishDiagnosticsParams>
                wanted, String what) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            List<PublishDiagnosticsParams> skipped = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                PublishDiagnosticsParams params = published.poll(50, TimeUnit.MILLISECONDS);
                if (params == null) {
                    continue;
                }
                if (wanted.test(params)) {
                    published.addAll(skipped);
                    return params;
                }
                skipped.add(params);
            }
            throw new AssertionError("no publishDiagnostics for " + what + ", saw " + published);
        }
    }

    /** One server, one client, four pipes, all of them closed again. */
    private static final class Connection implements AutoCloseable {

        private final LanguageServer server;
        private final VrmlLanguageServer instance;
        private final ClientSide client = new ClientSide();
        private final List<Closeable> streams = new ArrayList<>();

        Connection() {
            try {
                // Cross-connected so each side reads what the other wrote: the client's requests
                // arrive on the server's input, the server's notifications on the client's.
                PipedInputStream serverIn = new PipedInputStream(PIPE_BUFFER);
                PipedOutputStream clientOut = new PipedOutputStream(serverIn);
                PipedInputStream clientIn = new PipedInputStream(PIPE_BUFFER);
                PipedOutputStream serverOut = new PipedOutputStream(clientIn);
                streams.add(serverIn);
                streams.add(serverOut);
                streams.add(clientIn);
                streams.add(clientOut);

                instance = new VrmlLanguageServer();
                // A Launcher is typed by the proxy it hands out, which is the *other* role.
                Launcher<LanguageClient> serverSide = LSPLauncher.createServerLauncher(instance,
                        serverIn, serverOut);
                Launcher<LanguageServer> clientSide = LSPLauncher.createClientLauncher(client,
                        clientIn, clientOut);
                // Launcher.main's order: the server gets its client proxy before either read loop
                // starts, or the first didOpen has nowhere to send its diagnostics.
                instance.connect(serverSide.getRemoteProxy());
                serverSide.startListening();
                clientSide.startListening();
                server = clientSide.getRemoteProxy();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        LanguageServer server() {
            return server;
        }

        ClientSide client() {
            return client;
        }

        /** What the server actually holds, read from the outside rather than assumed. */
        DocumentStore store() {
            return instance.store();
        }

        /** The handshake every client performs first; capabilities are asserted where needed. */
        InitializeResult initialize() {
            return await(server.initialize(new InitializeParams()));
        }

        /** The handshake with a client's capabilities, which is how a shape gets asked for. */
        InitializeResult initialize(ClientCapabilities capabilities) {
            InitializeParams params = new InitializeParams();
            params.setCapabilities(capabilities);
            return await(server.initialize(params));
        }

        /** What the token at {@code at} has to say, or null when it has nothing to say. */
        Hover hover(Position at) {
            return await(server.getTextDocumentService()
                    .hover(new HoverParams(new TextDocumentIdentifier(URI), at)));
        }

        /** The outline, in whichever shape the handshake asked for. */
        List<Either<SymbolInformation, DocumentSymbol>> symbols() {
            return await(server.getTextDocumentService().documentSymbol(
                    new DocumentSymbolParams(new TextDocumentIdentifier(URI))));
        }

        List<? extends Location> definition(Position at) {
            return awaitLocations(server.getTextDocumentService().definition(
                    new DefinitionParams(new TextDocumentIdentifier(URI), at)));
        }

        List<? extends Location> declaration(Position at) {
            return awaitLocations(server.getTextDocumentService().declaration(
                    new DeclarationParams(new TextDocumentIdentifier(URI), at)));
        }

        List<? extends Location> references(Position at) {
            return await(server.getTextDocumentService().references(new ReferenceParams(
                    new TextDocumentIdentifier(URI), at, new ReferenceContext(true))));
        }

        /** The whole document's worth of whitespace replacements, or null when they were declined. */
        List<? extends TextEdit> formatting(FormattingOptions options) {
            return await(server.getTextDocumentService().formatting(
                    new DocumentFormattingParams(new TextDocumentIdentifier(URI), options)));
        }

        List<? extends TextEdit> rangeFormatting(Range selection, FormattingOptions options) {
            return await(server.getTextDocumentService().rangeFormatting(
                    new DocumentRangeFormattingParams(new TextDocumentIdentifier(URI), options,
                            selection)));
        }

        /** The request's answer, out of the {@code Either} the protocol wraps a location list in. */
        private List<? extends Location> awaitLocations(
                CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
                        request) {
            return await(request).getLeft();
        }

        private <T> T await(CompletableFuture<T> request) {
            try {
                return request.get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        void open(String text, int version) {
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(URI, "vrml97", version, text)));
        }

        /** Opens a document and waits until the analysis of exactly that text has come back. */
        PublishDiagnosticsParams openAndWait(String text, int version) throws InterruptedException {
            open(text, version);
            return client.awaitVersion(version);
        }

        /** Tells the server what the user configured, exactly as a client sends it. */
        void configure(Map<String, ?> settings) {
            server.getWorkspaceService().didChangeConfiguration(
                    new DidChangeConfigurationParams(settings));
        }

        void change(List<TextDocumentContentChangeEvent> changes, int version) {
            server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(URI, version), changes));
        }

        void close(String uri) {
            server.getTextDocumentService()
                    .didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
        }

        /** Stops the analysis worker, which is what shutdown is for on this side. */
        void shutdown() {
            try {
                assertNull(server.shutdown().get(15, TimeUnit.SECONDS),
                        "the spec says shutdown answers null");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (Closeable stream : streams) {
                try {
                    stream.close();
                } catch (IOException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        }
    }

    @Test
    void theHandshakeAdvertisesOnlyWhatThisServerDoes() throws Exception {
        try (Connection connection = new Connection()) {
            InitializeResult result = connection.initialize();
            ServerCapabilities caps = result.getCapabilities();
            assertEquals("vrml-lsp", result.getServerInfo().getName());
            TextDocumentSyncOptions sync = caps.getTextDocumentSync().getRight();
            assertEquals(TextDocumentSyncKind.Incremental, sync.getChange(),
                    "clients pick their edit format from this");
            assertEquals(Boolean.TRUE, sync.getOpenClose());
            assertEquals(PositionEncodingKind.UTF16, caps.getPositionEncoding());
            assertNotNull(caps.getCompletionProvider());
            assertEquals(Boolean.TRUE, caps.getHoverProvider().getLeft());
            assertEquals(Boolean.TRUE, caps.getDocumentSymbolProvider().getLeft());
            assertEquals(Boolean.TRUE, caps.getDefinitionProvider().getLeft());
            assertEquals(Boolean.TRUE, caps.getReferencesProvider().getLeft());
            assertEquals(Boolean.TRUE, caps.getDeclarationProvider().getLeft());
            assertEquals(Boolean.TRUE, caps.getDocumentFormattingProvider().getLeft());
            assertEquals(Boolean.TRUE, caps.getDocumentRangeFormattingProvider().getLeft());
            assertNull(caps.getDiagnosticProvider(),
                    "diagnostics are pushed; declaring the pull provider would invite requests"
                            + " this server cannot answer");
            connection.shutdown();
        }
    }

    @Test
    void aBrokenFileComesBackOnTheWireAtTheRightPlace() throws Exception {
        String broken = HEADER + "Box {\n  size 1 1\nIMPORT INLINE.foo AS bar\n}\nShape {}\n";
        try (Connection connection = new Connection()) {
            connection.initialize();
            connection.open(broken, 1);

            PublishDiagnosticsParams params = connection.client().awaitVersion(1);
            assertEquals(URI, params.getUri());
            List<Diagnostic> diagnostics = params.getDiagnostics();
            assertEquals(1, diagnostics.size(),
                    "an editor that shows five markers for one typo gets them all ignored: "
                            + diagnostics);
            Diagnostic only = diagnostics.get(0);
            assertEquals("VRL1002", only.getCode().getLeft());
            assertEquals("vrml-lsp", only.getSource());
            // Line 3, character 13: the '.' that stops IMPORT from being a node name.
            assertEquals(new Range(new Position(3, 13), new Position(3, 13)), only.getRange());
            connection.shutdown();
        }
    }

    @Test
    void anIncrementalEditIsWhatGetsAnalysed() throws Exception {
        try (Connection connection = new Connection()) {
            connection.initialize();
            connection.open(HEADER + "Box {\n  size 1 1 1\n}\n", 1);
            assertTrue(connection.client().awaitVersion(1).getDiagnostics().isEmpty());

            // Only the inserted text and where it goes, as the advertised capability promises.
            TextDocumentContentChangeEvent insert = new TextDocumentContentChangeEvent(
                    new Range(new Position(2, 12), new Position(2, 12)), "\nIMPORT x.y AS z");
            connection.change(List.of(insert), 2);

            List<Diagnostic> after = connection.client().awaitVersion(2).getDiagnostics();
            assertEquals(1, after.size(), after.toString());
            Diagnostic only = after.get(0);
            assertEquals(3, only.getRange().getStart().getLine(),
                    "the report sits on the inserted line, so the edit was applied and not ignored");
            assertEquals("VRL1002", only.getCode().getLeft());
            connection.shutdown();
        }
    }

    @Test
    void closingAFileTakesItsMarkersAway() throws Exception {
        try (Connection connection = new Connection()) {
            connection.initialize();
            connection.open("no header, no shape\n", 1);
            assertFalse(connection.client().awaitVersion(1).getDiagnostics().isEmpty(),
                    "the file has to have been reported before clearing means anything");

            connection.close(URI);
            PublishDiagnosticsParams cleared = connection.client().awaitCleared();
            assertEquals(URI, cleared.getUri());
            assertTrue(cleared.getDiagnostics().isEmpty());
            assertNull(connection.store().get(URI), "and the text itself is let go");
            connection.shutdown();
        }
    }

    // ---- the reader's requests ---------------------------------------------------------------

    /** A tooltip is markdown and sits on the word, which is the offset conversion made visible. */
    @Test
    void aHoverArrivesAsMarkdownOverTheWordItExplains() throws Exception {
        try (Connection connection = new Connection()) {
            connection.initialize();
            assertTrue(connection.openAndWait(SCENE, 1).getDiagnostics().isEmpty(),
                    "a well-formed scene, so nothing below is an answer about errors");

            Hover hover = connection.hover(new Position(1, 5));
            assertNotNull(hover, "a DEF'd name has something to say about itself");
            assertEquals(new Range(new Position(1, 4), new Position(1, 7)), hover.getRange(),
                    "the tooltip is about `sun`, not about the line it stands on");
            MarkupContent content = hover.getContents().getRight();
            assertEquals("markdown", content.getKind());
            assertTrue(content.getValue().startsWith("**`sun`** \u00b7 `DirectionalLight`,"),
                    content.getValue());
            assertTrue(content.getValue().contains("used by"),
                    "how many times the file reads a name is the part an author cannot see from"
                            + " the line under the cursor: " + content.getValue());
            assertNull(connection.hover(new Position(3, 0)), "a brace has nothing to say");
            connection.shutdown();
        }
    }

    /** A jump is a {@link Location} in this document, at the name and nowhere else on the line. */
    @Test
    void aDefinitionRequestReachesTheLineThatDeclaredTheName() throws Exception {
        try (Connection connection = new Connection()) {
            connection.initialize();
            connection.openAndWait(SCENE, 1);

            // The `USE sun` of line 6 asks for the `DEF sun` of line 1.
            assertEquals(List.of(new Location(URI, new Range(new Position(1, 4),
                    new Position(1, 7)))), connection.definition(new Position(6, 18)));
            // A route says `set_translation` of the field the scene wrote `translation`.
            assertEquals(List.of(new Location(URI, new Range(new Position(5, 2),
                    new Position(5, 13)))), connection.definition(new Position(8, 40)));
            // A built-in is declared in the specification and at no position in any file.
            assertEquals(List.of(), connection.definition(new Position(1, 12)),
                    "no answer beats an invented one");
            connection.shutdown();
        }
    }

    /** Nothing in VRML is declared apart from where it is defined, and the client is not left empty. */
    @Test
    void aDeclarationRequestAnswersWhatADefinitionWould() throws Exception {
        try (Connection connection = new Connection()) {
            connection.initialize();
            connection.openAndWait(SCENE, 1);

            assertEquals(connection.definition(new Position(6, 18)),
                    connection.declaration(new Position(6, 18)));
            connection.shutdown();
        }
    }

    /** The list a rename would need: every reading of the name, in the order the file meets them. */
    @Test
    void aReferenceRequestListsEveryReadingOfTheName() throws Exception {
        try (Connection connection = new Connection()) {
            connection.initialize();
            connection.openAndWait(SCENE, 1);

            assertEquals(List.of(
                    new Location(URI, new Range(new Position(1, 4), new Position(1, 7))),
                    new Location(URI, new Range(new Position(6, 17), new Position(6, 20))),
                    new Location(URI, new Range(new Position(8, 6), new Position(8, 9)))),
                    connection.references(new Position(1, 5)));
            connection.shutdown();
        }
    }

    /** A client that said it can nest gets the tree the scene is. */
    @Test
    void anOutlineNestsForAClientThatReadsNesting() throws Exception {
        try (Connection connection = new Connection()) {
            connection.initialize(nestingSymbols());
            connection.openAndWait(SCENE, 1);

            List<Either<SymbolInformation, DocumentSymbol>> answer = connection.symbols();
            assertTrue(answer.stream().allMatch(Either::isRight),
                    "this client asked for a tree and got: " + answer);
            List<DocumentSymbol> roots = answer.stream().map(Either::getRight).toList();
            assertEquals(List.of("sun", "root", ROUTE_LABEL), names(roots));

            DocumentSymbol sun = roots.get(0);
            assertEquals("DirectionalLight", sun.getDetail());
            assertEquals(SymbolKind.Object, sun.getKind());
            assertEquals(new Range(new Position(1, 4), new Position(1, 7)), sun.getSelectionRange(),
                    "the name, which is what the client highlights");
            assertEquals(List.of("intensity"), names(sun.getChildren()));
            assertEquals("SFFloat", sun.getChildren().get(0).getDetail());
            assertEquals(SymbolKind.Field, sun.getChildren().get(0).getKind());

            DocumentSymbol root = roots.get(1);
            assertEquals(List.of("translation", "children"), names(root.getChildren()));
            assertEquals(new Range(new Position(4, 0), new Position(7, 1)), root.getRange(),
                    "the whole statement, which is what an editor folds away");
            assertEquals(SymbolKind.Event, roots.get(2).getKind());
            connection.shutdown();
        }
    }

    /** A client that never mentioned nesting gets the flat list it can actually parse. */
    @Test
    void anOutlineIsFlatForAClientThatNeverSaidItCouldNest() throws Exception {
        try (Connection connection = new Connection()) {
            connection.initialize();
            connection.openAndWait(SCENE, 1);

            List<Either<SymbolInformation, DocumentSymbol>> answer = connection.symbols();
            assertTrue(answer.stream().allMatch(Either::isLeft),
                    "the handshake said nothing about nesting, so a tree is not offerable: "
                            + answer);
            List<SymbolInformation> flat = answer.stream().map(Either::getLeft).toList();
            assertEquals(List.of("sun", "intensity", "root", "translation", "children",
                    ROUTE_LABEL), flat.stream().map(SymbolInformation::getName).toList());
            assertEquals("sun: DirectionalLight", flat.get(1).getContainerName(),
                    "a flat list still has to say where a field belongs");
            assertEquals(new Range(new Position(2, 2), new Position(2, 11)),
                    flat.get(1).getLocation().getRange(), "the name, not the whole assignment");
            connection.shutdown();
        }
    }

    /** The client's way of saying it can read a nested symbol list. */
    private static ClientCapabilities nestingSymbols() {
        DocumentSymbolCapabilities symbol = new DocumentSymbolCapabilities();
        symbol.setHierarchicalDocumentSymbolSupport(true);
        TextDocumentClientCapabilities text = new TextDocumentClientCapabilities();
        text.setDocumentSymbol(symbol);
        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setTextDocument(text);
        return capabilities;
    }

    // ---- formatting ---------------------------------------------------------

    /**
     * The reflow the handshake advertises, answered in ranges a client can actually apply.
     *
     * <p>What gets checked is the text the edits produce and not the edits, because a range one line
     * off is a formatter eating part of a document and only applying the list shows it up. The second
     * half of the case is the other promise: a document that is already laid out has nothing to apply.
     */
    @Test
    void formattingReflowsTheDocumentItWasAskedAbout() throws Exception {
        try (Connection connection = new Connection()) {
            connection.initialize();
            connection.open(MESSY, 1);

            // insertSpaces=false buys nothing here. The widths come from whoever asked, the character
            // stays the server's: a column cannot be counted out of a tab every editor sizes apart.
            String reformatted = apply(MESSY, connection.formatting(new FormattingOptions(2, false)));
            assertEquals(TIDY, reformatted);

            connection.change(List.of(new TextDocumentContentChangeEvent(reformatted)), 2);
            connection.client().awaitVersion(2);
            assertEquals(List.of(), connection.formatting(new FormattingOptions(2, false)),
                    "formatting an already tidy document has to be nothing at all");
            connection.shutdown();
        }
    }

    @Test
    void theIndentWidthComesFromTheRequest() throws Exception {
        try (Connection connection = new Connection()) {
            connection.initialize();
            connection.open(MESSY, 1);
            assertEquals(twiceAsDeep(TIDY),
                    apply(MESSY, connection.formatting(new FormattingOptions(4, true))));
            connection.shutdown();
        }
    }

    /**
     * The layout choice no request carries, arriving by notification with an untyped payload.
     *
     * <p>{@code workspace/didChangeConfiguration} is the message easiest to accept in a signature and
     * drop in an implementation, and it crosses the wire as JSON rather than as the map that was sent.
     * The width has no other route into a reflow; the indent has two sources, and which one wins is a
     * rule worth having written down somewhere executable.
     */
    @Test
    void aConfigurationChangeReachesTheFormatter() throws Exception {
        String atFour = """
                #VRML V2.0 utf8
                DEF path Coordinate {
                    point [
                        0 0 0, 1 0 0, 2
                        0 0, 3 0 0, 4 0
                        0, 5 0 0
                    ]
                }
                """;
        String atTwo = """
                #VRML V2.0 utf8
                DEF path Coordinate {
                  point [
                    0 0 0, 1 0 0, 2 0 0,
                    3 0 0, 4 0 0, 5 0 0
                  ]
                }
                """;
        try (Connection connection = new Connection()) {
            connection.initialize();
            connection.open(WIDE, 1);

            String untold = apply(WIDE, connection.formatting(new FormattingOptions(2, true)));
            assertTrue(untold.contains("  point [ 0 0 0, 1 0 0, 2 0 0, 3 0 0"),
                    "nothing wraps before anybody has asked for a width: " + untold);

            connection.configure(Map.of("vrml", Map.of("indent", 4, "maxColumn", 24)));
            assertEquals(atFour, apply(WIDE, connection.formatting(new FormattingOptions())),
                    "a request that names no tab size has to honour both settings");
            // The request describes the editor being typed in, so its tab size outranks the setting;
            // the width, which no request can name, still comes from the setting.
            assertEquals(atTwo, apply(WIDE, connection.formatting(new FormattingOptions(2, true))),
                    "a global indent must not override the document's own tab size");

            // A typo is not a number: leave last month's layout alone rather than guess a new one.
            connection.configure(Map.of("vrml", Map.of("maxColumn", "twenty-four")));
            assertEquals(atTwo, apply(WIDE, connection.formatting(new FormattingOptions(2, true))),
                    "an unreadable setting should change nothing, not fall back to the default");
            connection.shutdown();
        }
    }

    /** The selection is honoured as a bound, not as a suggestion. */
    @Test
    void aSelectionReflowsItsOwnLinesAndNoOthers() throws Exception {
        try (Connection connection = new Connection()) {
            connection.initialize();
            connection.open(MESSY, 1);
            // One line of the mess, which is where a whole node is hiding inside another.
            List<? extends TextEdit> edits = connection.rangeFormatting(
                    new Range(new Position(3, 0), new Position(4, 0)),
                    new FormattingOptions(2, true));
            assertFalse(edits.isEmpty(), "the selection had room to be tidied: " + edits);

            String applied = apply(MESSY, edits);
            String outside = MESSY.substring(0, MESSY.indexOf("  children ["));
            assertTrue(applied.startsWith(outside),
                    "a line outside the selection was rewritten: " + applied);
            assertEquals(TIDY.substring(TIDY.indexOf("  children [")),
                    applied.substring(applied.indexOf("  children [")),
                    "what was selected did not come out the way the whole document would");
            connection.shutdown();
        }
    }

    /**
     * A document whose braces do not add up is refused rather than reindented.
     *
     * <p>Null, not an empty list, because the two answers mean opposite things - and the notification
     * has to name a position, since "it would not format my file" with nowhere to look reads as a bug
     * in the formatter instead of the missing brace it is.
     */
    @Test
    void aDocumentThatDoesNotParseIsNotReformatted() throws Exception {
        String broken = HEADER + "Box {\n  size 1 1\nIMPORT INLINE.foo AS bar\n}\nShape {}\n";
        try (Connection connection = new Connection()) {
            connection.initialize();
            connection.open(broken, 1);
            assertNull(connection.formatting(new FormattingOptions(2, true)),
                    "an empty list would claim the file is already tidy");

            MessageParams said = connection.client().awaitMessage();
            assertEquals(MessageType.Warning, said.getType());
            assertTrue(said.getMessage().contains("4:14"), said.getMessage());
            assertTrue(said.getMessage().contains("expected '{'"), said.getMessage());
            connection.shutdown();
        }
    }

    /** What a client does with the answer: apply every range to the document the ranges were about. */
    private static String apply(String text, List<? extends TextEdit> edits) {
        LineIndex index = new LineIndex(text);
        List<TextEdit> fromTheBack = new ArrayList<>(edits);
        fromTheBack.sort(Comparator.comparingInt((TextEdit edit) ->
                index.offsetOf(edit.getRange().getStart().getLine(),
                        edit.getRange().getStart().getCharacter())).reversed());
        StringBuilder out = new StringBuilder(text);
        for (TextEdit edit : fromTheBack) {
            int from = index.offsetOf(edit.getRange().getStart().getLine(),
                    edit.getRange().getStart().getCharacter());
            int to = index.offsetOf(edit.getRange().getEnd().getLine(),
                    edit.getRange().getEnd().getCharacter());
            out.replace(from, to, edit.getNewText());
        }
        return out.toString();
    }

    /** The same layout with every step twice as deep, which is what a {@code tabSize} of four means. */
    private static String twiceAsDeep(String tidy) {
        StringBuilder out = new StringBuilder();
        for (String line : tidy.split("\n", -1)) {
            String content = line.stripLeading();
            out.append(" ".repeat((line.length() - content.length()) * 2)).append(content)
                    .append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    private static List<String> names(List<DocumentSymbol> symbols) {
        return symbols.stream().map(DocumentSymbol::getName).toList();
    }
}
