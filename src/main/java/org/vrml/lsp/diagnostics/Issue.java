package org.vrml.lsp.diagnostics;

/**
 * One reported issue, in character offsets of the document it was found in.
 *
 * <p>Offsets, not lines: documents change between analysis and display, and a
 * line/column pair cannot be rebased after an edit while an offset can.
 */
public record Issue(int code, Severity severity, String message, int start, int end) {

    public static Issue error(int code, String message, int start, int end) {
        return new Issue(code, Severity.ERROR, message, start, end);
    }

    public static Issue warning(int code, String message, int start, int end) {
        return new Issue(code, Severity.WARNING, message, start, end);
    }

    public static Issue hint(int code, String message, int start, int end) {
        return new Issue(code, Severity.HINT, message, start, end);
    }

    public static Issue info(int code, String message, int start, int end) {
        return new Issue(code, Severity.INFORMATION, message, start, end);
    }

    /** Wire format wants 1-based "VRL1002"-style codes. */
    public String codeLabel() {
        return String.format("VRL%04d", code);
    }
}
