package org.vrml.lsp.lexer;

/**
 * Token classes of the VRML97 (Relaxed) lexer.
 *
 * <p>The set mirrors the TOKEN sections of
 * {@code Xj3D/xj3d/src/javacc/vrml/VRML97RelaxedParser.jj} exactly, because the
 * keyword/identifier boundary is observable behaviour: {@code IS} is reserved while
 * {@code ISX} is an ordinary identifier, and a name containing {@code .} is two
 * tokens. Anything that drifts from this set silently changes what files parse.
 */
public enum TokenType {

    /** Runs of space, tab, CR, LF, form feed and comma - comma is whitespace in VRML. */
    WHITESPACE(true),
    /** A {@code #} comment, including its terminating line break. */
    COMMENT(true),

    IDENT(false),
    NUMBER(false),
    STRING(false),

    LBRACE(false),
    RBRACE(false),
    LBRACKET(false),
    RBRACKET(false),
    DOT(false),

    KW_DEF(false),
    KW_USE(false),
    KW_NULL(false),
    KW_PROTO(false),
    KW_EXTERNPROTO(false),
    KW_ROUTE(false),
    KW_TO(false),
    KW_IS(false),
    KW_EVENT_IN(false),
    KW_EVENT_OUT(false),
    KW_FIELD(false),
    KW_EXPOSED_FIELD(false),
    KW_SCRIPT(false),
    KW_TRUE(false),
    KW_FALSE(false),

    /** End of file marker; always the last token, with an empty span. */
    EOF(false),
    /** A character that matches no token; consumed one char at a time so scanning always advances. */
    BAD(false);

    private final boolean trivia;

    TokenType(boolean trivia) {
        this.trivia = trivia;
    }

    /** Trivia never reaches the parser; it only exists in the token stream, for round-tripping. */
    public boolean isTrivia() {
        return trivia;
    }

    public boolean isKeyword() {
        return ordinal() >= KW_DEF.ordinal() && ordinal() <= KW_FALSE.ordinal();
    }
}
