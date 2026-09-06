package org.vrml.lsp.server;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.vrml.lsp.diagnostics.DocumentAnalyzer;
import org.vrml.lsp.text.LineIndex;

/**
 * A single open document.
 *
 * <p>Text is authoritative here and positions are translated through {@link LineIndex};
 * everything else in the server works in character offsets. Concurrency follows LSP4J's
 * promise that notifications arrive in order, but readers may be on another thread once
 * analysis is async, so the state is guarded rather than assumed single-threaded.
 */
public final class VrmlDocument {

    private final String uri;
    private String text;
    private int version;
    /** Rebuilt whenever the text changes; null until first asked for. */
    private LineIndex index;
    /** Guards the pair below; writes never take it, so typing cannot queue behind an analysis. */
    private final Object analysisLock = new Object();
    /** The analysis of {@code cachedText}; see {@link #analyze()}, which says why both are volatile. */
    private volatile DocumentAnalyzer.Analysis analysis;
    private volatile String cachedText;

    VrmlDocument(String uri, int version, String text) {
        this.uri = uri;
        this.version = version;
        setText(text);
    }

    public String uri() {
        return uri;
    }

    public synchronized String text() {
        return text;
    }

    public synchronized int version() {
        return version;
    }

    void setVersion(int version) {
        this.version = version;
    }

    /** @return true when the content actually changed (clients may send no-op range edits). */
    public synchronized boolean applyEdit(Range range, String replacement) {
        String next;
        if (range == null) {
            next = replacement;
        } else {
            int start = offsetOf(range.getStart());
            int end = offsetOf(range.getEnd());
            if (start > end) {
                int t = start;
                start = end;
                end = t;
            }
            // One builder rather than `text.substring(0, start) + replacement + text.substring(end)`:
            // a modern substring copies, so the expression form makes four passes over a document
            // that a keystroke only needs two, on the one path that runs on every character typed.
            next = new StringBuilder(Math.max(16, text.length() + replacement.length()))
                    .append(text, 0, start).append(replacement).append(text, end, text.length())
                    .toString();
        }
        return setText(next);
    }

    private boolean setText(String next) {
        if (next.equals(text)) {
            return false;
        }
        text = next;
        index = null;
        // Dropped rather than kept-as-stale: an edit can arrive with nobody looking at the old
        // tree, and a 6 MB scene's worth of tokens is not worth pinning against the next keystroke.
        // The key is cleared first, so a thread that reads the pair cannot find the text of one
        // analysis attached to the tree of another.
        cachedText = null;
        analysis = null;
        return true;
    }

    /**
     * The analysis of exactly this text, computed once per edit instead of once per request.
     *
     * <p>Every feature the server offers wants the same thing - what does the text in the buffer
     * mean - and a 126 KB scene costs about as much to parse as a hover, an outline and a popup
     * asked for in the same second can afford between them. So the parse happens here, once, and
     * whoever arrives first pays for it: usually the diagnostics worker, which makes the request
     * that follows it free.
     *
     * <p>The key is the identity of the {@code String}, not its content. Comparing content would
     * mean scanning the document to avoid scanning the document, and every write here installs a
     * fresh {@code String}, so the same object can only mean the same text. It also cannot be wrong
     * in the direction that matters: an identical-looking edit produces a different object, so an
     * answer is never given about text that has been edited since.
     *
     * <p>The document lock is deliberately not held across the analysis. A 5 MB scene costs a
     * fifth of a second or more to analyse, and the thread that analyses is the diagnostics
     * worker's, so an edit that shares the document's lock with that work waits for all of it -
     * which is the one thing typing must not do. Hence a lock of its own, taken only by readers.
     * The pair is volatile because it is written outside that lock's reach as well: the key goes in
     * last and out first, so a thread that recognises the key also sees the tree stored with it,
     * and an edit that invalidates the key in between leaves an in-flight answer merely unused
     * rather than wrong.
     */
    public DocumentAnalyzer.Analysis analyze() {
        String target = text();
        DocumentAnalyzer.Analysis cached = cachedFor(target);
        if (cached != null) {
            return cached;
        }
        synchronized (analysisLock) {
            // Whoever waited may find that the one it waited behind has finished this very text.
            DocumentAnalyzer.Analysis done = cachedFor(target);
            if (done == null) {
                done = DocumentAnalyzer.analyze(target);
                analysis = done;
                cachedText = target;
            }
            return done;
        }
    }

    /**
     * The cached analysis of exactly {@code target}, or null when nothing answers it.
     *
     * <p>Reads the key before the tree, which is the other half of the writing order in
     * {@link #analyze()}: recognising the key is what licenses believing the tree that follows it.
     * Read the other way round, a concurrent store could be seen as this text with an older
     * document's tree, and the whole point of the identity key would be gone.
     */
    private DocumentAnalyzer.Analysis cachedFor(String target) {
        return cachedText != target ? null : analysis;
    }

    /** Line/offset mapping of the current content, rebuilt after a change. */
    public synchronized LineIndex index() {
        if (index == null) {
            index = new LineIndex(text);
        }
        return index;
    }

    /** Character offset for a protocol position, clamped into the document. */
    public int offsetOf(Position pos) {
        return index().offsetOf(pos.getLine(), pos.getCharacter());
    }

    /** Protocol position for a character offset. */
    public Position positionOf(int offset) {
        LineIndex idx = index();
        return new Position(idx.lineOf(offset), idx.characterOf(offset));
    }

    /** Half-open protocol range for a character offset span. */
    public Range rangeOf(int start, int end) {
        LineIndex idx = index();
        return new Range(new Position(idx.lineOf(start), idx.characterOf(start)),
                new Position(idx.lineOf(end), idx.characterOf(end)));
    }

    public synchronized int lineCount() {
        return index().lineCount();
    }

    public synchronized int length() {
        return text.length();
    }
}
