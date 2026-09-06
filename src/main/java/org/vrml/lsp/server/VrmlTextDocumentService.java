package org.vrml.lsp.server;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DeclarationParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.vrml.lsp.Log;
import org.vrml.lsp.diagnostics.DocumentAnalyzer;
import org.vrml.lsp.diagnostics.Issue;
import org.vrml.lsp.diagnostics.Severity;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.services.Completions;
import org.vrml.lsp.services.Context;
import org.vrml.lsp.services.Definitions;
import org.vrml.lsp.services.Formatter;
import org.vrml.lsp.services.Hovers;
import org.vrml.lsp.services.Symbols;
import org.vrml.lsp.spec.Specs;

/**
 * Per-document protocol requests.
 *
 * <p>Document synchronisation is live from the first milestone: it is the only thing
 * the rest of the pipeline can be built on. Every request answers from the newest text rather than a
 * cache, and one whose analysis throws still answers with an empty result plus a log line: a client
 * that gets nothing back cannot otherwise tell "there is nothing there" from "the server failed", and
 * the log is what makes the second case findable.
 */
public final class VrmlTextDocumentService implements TextDocumentService {

    private final DocumentStore store;
    private final FormattingSettings formatting;
    private final DiagnosticPublisher diagnostics;
    private volatile LanguageClient client;
    private volatile boolean clientSupportsSnippets;
    private volatile boolean clientNestsSymbols;

    /** The settings are the workspace service's to receive and this one's to honour. */
    VrmlTextDocumentService(DocumentStore store, FormattingSettings formatting) {
        this.store = store;
        // Shared with the workspace service, which is where the user's settings arrive; two copies
        // would mean a configuration change silently applying to nothing.
        this.formatting = formatting;
        // The publisher only ever reads the client, and only after a document is opened, which
        // is well past construction; passing this::client is how it stays current when
        // connect() happens later.
        this.diagnostics = new DiagnosticPublisher(store, this::client);
    }

    void setClient(LanguageClient client) {
        this.client = client;
    }

    LanguageClient client() {
        return client;
    }

    public DocumentStore store() {
        return store;
    }

    void noteClientCapabilities(ClientCapabilities caps) {
        clientSupportsSnippets = SnippetSupport.from(caps);
        clientNestsSymbols = nestsSymbols(caps);
        Log.info("client completion snippets: " + clientSupportsSnippets + ", nested symbols: "
                + clientNestsSymbols);
    }

    /**
     * Whether the client reads a nested symbol list.
     *
     * <p>Asked once and remembered, because the answer decides the shape of every outline: a client
     * that never said it can nest shows only what it can parse, which is a flat list.
     */
    private static boolean nestsSymbols(ClientCapabilities caps) {
        return caps != null && caps.getTextDocument() != null
                && caps.getTextDocument().getDocumentSymbol() != null
                && Boolean.TRUE.equals(caps.getTextDocument().getDocumentSymbol()
                        .getHierarchicalDocumentSymbolSupport());
    }

    public boolean clientSupportsSnippets() {
        return clientSupportsSnippets;
    }

    /** Stops the analysis worker; called from the server's shutdown, before the JVM exits. */
    void shutdownDiagnostics() {
        diagnostics.shutdown();
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        var doc = params.getTextDocument();
        int version = versionOf(doc, 0);
        store.open(doc.getUri(), version, doc.getText());
        Log.info("didOpen " + doc.getUri() + " v" + version
                + " (" + doc.getText().length() + " chars)");
        diagnostics.request(doc.getUri(), version, doc.getText());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        VrmlDocument doc = store.get(uri);
        if (doc == null) {
            Log.warn("didChange for unopened document " + uri);
            return;
        }
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
        // Events are ordered; an event without a range replaces the whole document.
        for (TextDocumentContentChangeEvent change : changes) {
            doc.applyEdit(change.getRange(), change.getText());
        }
        doc.setVersion(versionOf(params.getTextDocument(), doc.version()));
        diagnostics.request(uri, doc.version(), doc.text());
    }

    /** The version is optional on the wire, so it must not be unboxed blindly. */
    private static int versionOf(VersionedTextDocumentIdentifier id, int fallback) {
        Integer v = id.getVersion();
        return v == null ? fallback : v;
    }

    private static int versionOf(TextDocumentItem item, int fallback) {
        Integer v = item.getVersion();
        return v == null ? fallback : v;
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        // Drop the document first: an analysis in flight then sees nothing and stays quiet,
        // and the empty notification below is the last word the client hears.
        store.close(uri);
        diagnostics.cleared(uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        Log.info("didSave " + params.getTextDocument().getUri());
    }

    /**
     * What may be written at the cursor.
     *
     * <p>The analysis is the document's own, so one parse serves the whole request - the context,
     * the candidate lists and the name lookups all read the tree the squiggles were found in, and the
     * popup cannot disagree with the markers about what the file says. It is also the analysis of
     * exactly the text in the buffer, which is the property that matters mid-keystroke: the cache is
     * keyed by the text object itself, so an edit cannot be answered from a stale tree.
     */
    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        String uri = params.getTextDocument().getUri();
        VrmlDocument doc = document(uri, "completion");
        if (doc == null) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        List<CompletionItem> items;
        try {
            DocumentAnalyzer.Analysis analysis = doc.analyze();
            Context context = Context.of(analysis.parse(), analysis.symbols(),
                    doc.offsetOf(params.getPosition()));
            items = CompletionEncoder.encode(Completions.of(analysis.parse(), analysis.symbols(),
                    Specs.standard(), context, clientSupportsSnippets,
                    CompletionEncoder.siblingScenes(uri)), context, doc);
        } catch (RuntimeException e) {
            // An empty list is the right way to fail: a popup that answers a request with a protocol
            // error is worse than one that stays closed, and the log keeps the case findable.
            Log.warn("completion failed for " + uri + ": " + e);
            items = List.of();
        }
        return CompletableFuture.completedFuture(Either.forLeft(items));
    }

    /**
     * The document a request is about, or null when it is one we never opened.
     *
     * <p>Every position-based request needs it and all of them fail the same way, so the warning that
     * names which request came in lives here rather than in each caller.
     */
    private VrmlDocument document(String uri, String asked) {
        VrmlDocument doc = store.get(uri);
        if (doc == null) {
            Log.warn(asked + " for unopened document " + uri);
        }
        return doc;
    }

    /**
     * What the token under the pointer means.
     *
     * <p>A hover is asked for while the user is already looking at the text in the buffer, which is
     * why it answers from the analysis of that text and not from an older one: the tooltip has to
     * describe the same tree the squiggles were found in. {@link VrmlDocument#analyze()} is the
     * document's own answer about its current content, cached by content identity rather than skipped.
     */
    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String uri = params.getTextDocument().getUri();
        VrmlDocument doc = document(uri, "hover");
        if (doc == null) {
            return CompletableFuture.completedFuture(null);
        }
        Hover hover = null;
        try {
            DocumentAnalyzer.Analysis analysis = doc.analyze();
            hover = HoverEncoder.encode(Hovers.of(analysis.parse(), analysis.symbols(), Specs.standard(),
                    doc.offsetOf(params.getPosition())), doc);
        } catch (RuntimeException e) {
            // Null is a legitimate answer here - most of the text has nothing to say about it - so a
            // failure is indistinguishable from a plain "nothing" except in the log.
            Log.warn("hover failed for " + uri + ": " + e);
        }
        return CompletableFuture.completedFuture(hover);
    }

    /**
     * Where the name under the pointer is declared.
     *
     * <p>An empty list is the honest answer for a built-in node or field: those are declared by the
     * specification, not at a position in the file, and pointing at nothing beats pointing at a
     * made-up place.
     */
    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        List<? extends Location> targets = jump(params.getTextDocument().getUri(),
                params.getPosition(), "definition");
        return CompletableFuture.completedFuture(Either.forLeft(targets));
    }

    /**
     * The same list of ranges {@code definition} gives.
     *
     * <p>VRML has no separation between a declaration and a definition: a DEF, a PROTO interface line
     * or a ROUTE endpoint is declared exactly where it is written. The capability is answered anyway,
     * rather than left undeclared, because a client that asks for the declaration of a symbol does not
     * retry with the definition when the answer is empty.
     */
    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> declaration(
            DeclarationParams params) {
        List<? extends Location> targets = jump(params.getTextDocument().getUri(),
                params.getPosition(), "declaration");
        return CompletableFuture.completedFuture(Either.forLeft(targets));
    }

    /** One parse, then the ranges a jump should move the cursor to. */
    private List<? extends Location> jump(String uri, Position position, String asked) {
        VrmlDocument doc = document(uri, asked);
        if (doc == null) {
            return List.of();
        }
        try {
            DocumentAnalyzer.Analysis analysis = doc.analyze();
            return LocationEncoder.encode(Definitions.definition(analysis.parse(), analysis.symbols(),
                    Specs.standard(), doc.offsetOf(position)), doc);
        } catch (RuntimeException e) {
            Log.warn(asked + " failed for " + uri + ": " + e);
            return List.of();
        }
    }

    /** Everywhere the symbol under the pointer is used, its declaration included. */
    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        String uri = params.getTextDocument().getUri();
        VrmlDocument doc = document(uri, "references");
        if (doc == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<? extends Location> found = List.of();
        try {
            DocumentAnalyzer.Analysis analysis = doc.analyze();
            found = LocationEncoder.encode(Definitions.references(analysis.parse(), analysis.symbols(),
                    Specs.standard(), doc.offsetOf(params.getPosition())), doc);
        } catch (RuntimeException e) {
            Log.warn("references failed for " + uri + ": " + e);
        }
        return CompletableFuture.completedFuture(found);
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
            org.eclipse.lsp4j.DocumentHighlightParams params) {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * The document's outline: one line per node instance, field, prototype, interface line and route.
     *
     * <p>Nested or flat according to what the client said it can read at {@code initialize}, both
     * produced from the same tree.
     */
    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
            DocumentSymbolParams params) {
        String uri = params.getTextDocument().getUri();
        VrmlDocument doc = document(uri, "documentSymbol");
        if (doc == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<Either<SymbolInformation, DocumentSymbol>> outline = List.of();
        try {
            DocumentAnalyzer.Analysis analysis = doc.analyze();
            outline = OutlineEncoder.encode(Symbols.outline(analysis.parse(), analysis.symbols(),
                    Specs.standard()), doc, clientNestsSymbols);
        } catch (RuntimeException e) {
            Log.warn("documentSymbol failed for " + uri + ": " + e);
        }
        return CompletableFuture.completedFuture(outline);
    }

    /**
     * Reflow the whole document's whitespace.
     *
     * <p>An empty list means the file was already laid out the way the formatter would lay it out,
     * which is the usual answer for a corpus file; a null answer means the request was declined, and
     * {@link #reflow} says when and why.
     */
    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return reflow(params.getTextDocument().getUri(), params.getOptions(), null);
    }

    /**
     * Reflow the whitespace inside the given range and nowhere else.
     *
     * <p>Selections are snapped to whole edits rather than clipped: the formatter's edits cover the
     * trivia between two significant tokens, so an edit crossing the selection's end is dropped instead
     * of being cut, and a reflow that stops mid-statement would leave the tail over-indented.
     */
    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
        return reflow(params.getTextDocument().getUri(), params.getOptions(), params.getRange());
    }

    /**
     * One parse, then the whitespace replacements it justifies.
     *
     * <p>A document with a structural error is declined - {@code null}, not an empty list, because the
     * two say opposite things to a client and only one of them is actionable. Indentation is a count of
     * the brace pairs open over a line, so a file that is missing one of them has no correct indentation
     * to be given: reflowing it would shift every line after the error by a level and turn a
     * two-character typo into a whole-file diff, on a file whose author is in the middle of fixing it.
     * A semantic error does not decline, since no unknown field or dangling {@code USE} can move a
     * brace, and the files that most need tidying are often the ones with a mistake in them.
     */
    private CompletableFuture<List<? extends TextEdit>> reflow(String uri, FormattingOptions requested,
            Range range) {
        VrmlDocument doc = document(uri, "formatting");
        if (doc == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        try {
            ParseResult parse = doc.analyze().parse();
            if (parse.hasErrors()) {
                String reason = structuralError(parse, doc);
                Log.warn("formatting declined for " + uri + ": " + reason);
                decline(uri, reason);
                return CompletableFuture.completedFuture(null);
            }
            Formatter.Options options = formatting.forRequest(requested);
            List<Formatter.Edit> edits = range == null
                    ? Formatter.edits(parse, options)
                    : Formatter.edits(parse, options, doc.offsetOf(range.getStart()),
                            doc.offsetOf(range.getEnd()));
            return CompletableFuture.completedFuture(FormattingEncoder.encode(edits, doc));
        } catch (RuntimeException e) {
            Log.warn("formatting failed for " + uri + ": " + e);
            return CompletableFuture.completedFuture(List.of());
        }
    }

    /** The first structural error, as a position the diagnostics already agree with plus its message. */
    private static String structuralError(ParseResult parse, VrmlDocument doc) {
        for (Issue issue : parse.issues()) {
            if (issue.severity() == Severity.ERROR) {
                return doc.index().describe(issue.start()) + ": " + issue.message();
            }
        }
        // hasErrors() said yes and no issue is an error, which cannot happen; say so rather than null.
        return "the document does not parse";
    }

    /**
     * Tell the user why a formatting request did nothing.
     *
     * <p>A declined response carries no text, so the notification is the only channel there is; without
     * it an author watching a {@code Shift}-{@code Alt}-{@code F} do nothing has a formatter bug to look
     * for instead of the missing brace. Best effort: a client that is not connected or not listening
     * must not turn a refused reflow into a failed request.
     */
    private void decline(String uri, String reason) {
        LanguageClient to = client;
        if (to == null) {
            return;
        }
        try {
            to.showMessage(new MessageParams(MessageType.Warning,
                    "vrml-lsp did not format " + uri + ": " + reason
                            + " - fix the syntax first, then format"));
        } catch (RuntimeException e) {
            Log.warn("could not report the declined reflow of " + uri + ": " + e);
        }
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return CompletableFuture.completedFuture(List.of());
    }
}
