package org.vrml.lsp.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The offset/line/column contract every diagnostic range and every cursor query depends on.
 *
 * <p>Conventions come from the protocol, not from taste: lines and characters are 0-based,
 * characters count UTF-16 code units, and {@code \n}, {@code \r\n} and a lone {@code \r} each
 * end a line. Out-of-range input is clamped rather than rejected because clients do send stale
 * positions while typing.
 */
class LineIndexTest {

    @Test
    void eachLineEndingStyleEndsExactlyOneLine() {
        assertEquals(3, new LineIndex("a\nb\nc").lineCount());
        assertEquals(3, new LineIndex("a\r\nb\r\nc").lineCount());
        assertEquals(3, new LineIndex("a\rb\rc").lineCount());
        // A trailing break opens an empty last line: the cursor really can sit there.
        assertEquals(2, new LineIndex("a\n").lineCount());
        assertEquals(1, new LineIndex("").lineCount());
    }

    @Test
    void lineContentEndStopsBeforeTheTerminator() {
        LineIndex crlf = new LineIndex("ab\r\ncd");
        assertEquals(2, crlf.lineContentEnd(0), "the \\r\\n does not belong to line 0");
        assertEquals(6, crlf.lineContentEnd(1), "the last line ends at the text end");

        LineIndex lf = new LineIndex("ab\ncd");
        assertEquals(2, lf.lineContentEnd(0));
        assertEquals(5, lf.lineContentEnd(1));
    }

    @Test
    void positionsRoundTripThroughOffsets() {
        String src = "#VRML V2.0 utf8\n\nBox {\n  size 1 1 1\n}";
        LineIndex idx = new LineIndex(src);
        int at = src.indexOf("size");
        assertEquals(3, idx.lineOf(at));
        assertEquals(2, idx.characterOf(at));
        assertEquals(at, idx.offsetOf(3, 2));
        assertEquals("4:3", idx.describe(at), "logs and CLI output are 1-based");
    }

    @Test
    void columnsCountUtf16CodeUnitsLikeTheProtocolDoes() {
        // One astral character is two UTF-16 code units, so the next ASCII character sits at
        // column 3, not column 2. Getting this wrong shifts every position after the emoji.
        String src = "x\uD83D\uDE00y";
        LineIndex idx = new LineIndex("abc\n" + src);
        int y = src.indexOf('y');
        assertEquals(3, idx.characterOf(y + 4), "two code units before y on that line");
        assertEquals(4 + y, idx.offsetOf(1, 3));
    }

    @Test
    void stalePositionsClampIntoTheLineInsteadOfSlidingIntoTheNextOne() {
        LineIndex idx = new LineIndex("ab\r\ncd");
        // The client thinks line 0 is 500 columns wide; the edit must land after the 'b'.
        assertEquals(2, idx.offsetOf(0, 500));
        assertEquals(2, idx.offsetOf(0, 3), "even inside the \\r\\n the clamp is the content end");
        assertEquals(0, idx.offsetOf(0, -5));
        // Negative and oversized lines resolve to the first and last line.
        assertEquals(0, idx.offsetOf(-1, 0));
        assertEquals(4, idx.offsetOf(99, 0), "a line past the end resolves onto the last one");
        assertEquals(1, idx.lineOf(9999));
        assertEquals(2, idx.characterOf(9999));
    }
}
