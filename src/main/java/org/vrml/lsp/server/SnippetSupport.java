package org.vrml.lsp.server;

import org.eclipse.lsp4j.ClientCapabilities;

/**
 * Snippet support has to be negotiated, not assumed: a client without it will show
 * the literal {@code $1} text of a snippet as the completed value.
 */
final class SnippetSupport {

    private SnippetSupport() {
    }

    static boolean from(ClientCapabilities caps) {
        if (caps == null || caps.getTextDocument() == null
                || caps.getTextDocument().getCompletion() == null) {
            return false;
        }
        var completion = caps.getTextDocument().getCompletion();
        if (completion.getCompletionItem() == null) {
            return false;
        }
        return Boolean.TRUE.equals(completion.getCompletionItem().getSnippetSupport());
    }
}
