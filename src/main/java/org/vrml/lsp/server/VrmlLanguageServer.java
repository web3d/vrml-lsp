package org.vrml.lsp.server;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.PositionEncodingKind;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.vrml.lsp.Launcher;
import org.vrml.lsp.Log;

/**
 * Wires the protocol layer to the VRML analysis pipeline.
 */
public final class VrmlLanguageServer implements LanguageServer, LanguageClientAware {

    private final DocumentStore store = new DocumentStore();
    /** One instance, because the workspace is where the layout is configured and the editor is where it is used. */
    private final FormattingSettings formatting = new FormattingSettings();
    private final VrmlTextDocumentService textDocuments = new VrmlTextDocumentService(store, formatting);
    private final VrmlWorkspaceService workspace = new VrmlWorkspaceService(formatting);

    private volatile boolean shutdownRequested;

    @Override
    public void connect(LanguageClient client) {
        textDocuments.setClient(client);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        Log.info("initialize: rootUri=" + params.getRootUri()
                + " processId=" + params.getProcessId());
        ClientCapabilities caps = params.getCapabilities();
        if (caps != null && caps.getTextDocument() != null) {
            textDocuments.noteClientCapabilities(caps);
        }
        // Some clients deliver the settings inside the initialize request and never send a
        // didChangeConfiguration afterwards, so both places are read; an unparseable or absent
        // initialisation option simply leaves the defaults in place.
        if (params.getInitializationOptions() != null) {
            formatting.apply(params.getInitializationOptions());
        }
        ServerCapabilities sc = new ServerCapabilities();

        TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
        sync.setOpenClose(true);
        sync.setChange(TextDocumentSyncKind.Incremental);
        sync.setWillSave(false);
        sync.setWillSaveWaitUntil(false);
        sc.setTextDocumentSync(sync);

        // Every item arrives with its detail and documentation already filled in, so there is
        // nothing for a second round trip to resolve - and a capability declared must be honoured.
        sc.setCompletionProvider(new CompletionOptions(false, List.of("\"", ".", "#", "[", "{")));
        sc.setHoverProvider(true);
        sc.setDocumentSymbolProvider(true);
        sc.setDefinitionProvider(true);
        sc.setReferencesProvider(true);
        sc.setDeclarationProvider(true);
        sc.setDocumentFormattingProvider(true);
        sc.setDocumentRangeFormattingProvider(true);
        // Diagnostics are pushed via textDocument/publishDiagnostics; no pull-based
        // diagnosticProvider is declared, so clients must not call workspace/diagnostic.
        sc.setPositionEncoding(PositionEncodingKind.UTF16);

        return CompletableFuture.completedFuture(
                new InitializeResult(sc, new ServerInfo("vrml-lsp", Launcher.VERSION)));
    }

    @Override
    public void initialized(InitializedParams params) {
        Log.info("initialized; document sync = incremental, utf-16 positions");
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        shutdownRequested = true;
        // No more requests arrive after shutdown, so the diagnostics worker has nothing left to
        // feed; stopping it here keeps a long parse out of the exit path.
        textDocuments.shutdownDiagnostics();
        Log.info("shutdown requested");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // Non-zero on a forced exit without shutdown is the convention clients check.
        System.exit(shutdownRequested ? 0 : 1);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocuments;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspace;
    }

    @Override
    public void setTrace(SetTraceParams params) {
        Log.info("setTrace: " + params.getValue());
    }

    public DocumentStore store() {
        return store;
    }

    public boolean isShutdownRequested() {
        return shutdownRequested;
    }
}
