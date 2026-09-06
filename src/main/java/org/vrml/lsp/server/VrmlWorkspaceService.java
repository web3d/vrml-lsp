package org.vrml.lsp.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.vrml.lsp.Log;

/**
 * Workspace-level notifications.
 *
 * <p>Watched-file events matter for PROTO reuse: an imported {@code .wrl} changing on
 * disk invalidates cached EXTERNPROTO interfaces. Nothing is cached yet, so the event
 * is only logged; the hook stays visible here so the wiring is not lost later.
 */
public final class VrmlWorkspaceService implements WorkspaceService {

    /** Shared with the text-document service, which is the consumer of what the user configures. */
    private final FormattingSettings formatting;

    VrmlWorkspaceService(FormattingSettings formatting) {
        this.formatting = formatting;
    }

    /**
     * Take note of the settings, which is all this can do with them.
     *
     * <p>Nothing is re-published on a change: diagnostics come from the document as it stands when a
     * request arrives, so the next keystroke or hover picks the new layout up without prompting, and
     * re-analysing every open file because a number in a settings panel moved would cost more than it
     * is worth.
     */
    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        formatting.apply(params.getSettings());
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        if (params.getChanges() == null || params.getChanges().isEmpty()) {
            return;
        }
        Log.info("didChangeWatchedFiles: " + params.getChanges().size()
                + " change(s), first=" + params.getChanges().get(0).getUri());
        // Cache invalidation hooks in here once cross-file PROTO indexing exists.
    }
}
