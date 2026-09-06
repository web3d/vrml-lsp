package org.vrml.lsp.lexer;

/**
 * One lexeme, spanning {@code [start, end)} in UTF-16 code units.
 *
 * <p>The lexer guarantees that consecutive tokens touch: every character of the
 * source belongs to exactly one token, trivia included. That is what lets the
 * printer rebuild the file byte for byte, and it is asserted in the tests.
 */
public final class Token {

    public final TokenType type;
    public final int start;
    public final int end;
    /** True for a string literal whose closing quote or escape is missing. */
    public final boolean truncated;

    public Token(TokenType type, int start, int end, boolean truncated) {
        this.type = type;
        this.start = start;
        this.end = end;
        this.truncated = truncated;
    }

    public String image(CharSequence source) {
        return source.subSequence(start, end).toString();
    }

    public int length() {
        return end - start;
    }

    public boolean is(TokenType t) {
        return type == t;
    }

    /** True when this token is an identifier with exactly this spelling; keywords never match. */
    public boolean isIdentEqualTo(CharSequence source, String text) {
        return type == TokenType.IDENT && length() == text.length()
                && image(source).equals(text);
    }

    @Override
    public String toString() {
        return type + "[" + start + "," + end + ")";
    }
}
