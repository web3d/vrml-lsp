package org.vrml.lsp.text;

/**
 * Offset to line/character mapping for one document snapshot.
 *
 * <p>Lines and characters are 0-based and offsets are UTF-16 code units, i.e. the same
 * convention the protocol uses, so the server layer converts without arithmetic. A
 * {@code \n}, {@code \r\n} or lone {@code \r} counts as one break, which is what the
 * mainstream clients count.
 *
 * <p>Deliberately free of protocol types: the command line tools and the tests need the
 * same mapping, and neither should have to pull in LSP4J.
 */
public final class LineIndex {

    private final CharSequence text;
    /** Start offset of each line; index 0 is always present. */
    private final int[] lineStarts;

    public LineIndex(CharSequence text) {
        this.text = text;
        this.lineStarts = build(text);
    }

    private static int[] build(CharSequence text) {
        int[] tmp = new int[Math.max(16, text.length() / 24 + 2)];
        int count = 0;
        tmp[count++] = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // "\r\n" breaks on the \n so the pair counts once; a lone \r breaks here.
            boolean breakHere = c == '\n' || (c == '\r' && (i + 1 >= text.length()
                    || text.charAt(i + 1) != '\n'));
            if (!breakHere) {
                continue;
            }
            int next = i + 1;
            if (count == tmp.length) {
                int[] bigger = new int[tmp.length * 2];
                System.arraycopy(tmp, 0, bigger, 0, count);
                tmp = bigger;
            }
            tmp[count++] = next;
            i = next - 1;
        }
        int[] out = new int[count];
        System.arraycopy(tmp, 0, out, 0, count);
        return out;
    }

    public CharSequence text() {
        return text;
    }

    public int length() {
        return text.length();
    }

    public int lineCount() {
        return lineStarts.length;
    }

    public int lineStart(int line) {
        return lineStarts[Math.max(0, Math.min(line, lineStarts.length - 1))];
    }

    /** Offset just past the last character of the line's content, before its terminator. */
    public int lineContentEnd(int line) {
        int start = lineStart(line);
        int next = line + 1 < lineStarts.length ? lineStarts[line + 1] : text.length();
        int end = next;
        while (end > start && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return end;
    }

    /** Index of the line containing {@code offset}; offsets past EOF map to the last line. */
    public int lineOf(int offset) {
        int off = Math.max(0, Math.min(offset, text.length()));
        int lo = 0;
        int hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= off) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    public int characterOf(int offset) {
        return Math.max(0, Math.min(offset, text.length())) - lineStart(lineOf(offset));
    }

    /**
     * Offset for a line/character pair, clamped into the document.
     *
     * <p>Clamping is not a nicety: a client that still believes the document has 500 columns
     * after the line was shortened must resolve to the line end rather than into the next
     * line, or every edit it requests lands one character off.
     */
    public int offsetOf(int line, int character) {
        return Math.min(lineStart(line) + Math.max(0, character), lineContentEnd(line));
    }

    /** Human readable 1-based position, for command line output and logs. */
    public String describe(int offset) {
        int line = lineOf(offset);
        return (line + 1) + ":" + (characterOf(offset) + 1);
    }
}
