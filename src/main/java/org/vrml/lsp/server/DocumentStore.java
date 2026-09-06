package org.vrml.lsp.server;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Open documents, keyed by URI.
 *
 * <p>Text is held as a {@code String}: LSP positions are UTF-16 code units, which
 * is exactly what {@code String#length()} and char indices count, so offsets and
 * protocol positions convert without an intermediate representation.
 */
public final class DocumentStore {

    private final ConcurrentHashMap<String, VrmlDocument> docs = new ConcurrentHashMap<>();

    public void open(String uri, int version, String text) {
        docs.put(uri, new VrmlDocument(uri, version, text));
    }

    public void close(String uri) {
        docs.remove(uri);
    }

    /** @return the document, or {@code null} when it was never opened. */
    public VrmlDocument get(String uri) {
        return docs.get(uri);
    }
}
