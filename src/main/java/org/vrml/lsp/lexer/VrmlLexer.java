package org.vrml.lsp.lexer;

import java.util.ArrayList;
import java.util.List;

/**
 * VRML97 lexer, hand-written from the TOKEN declarations of
 * {@code VRML97RelaxedParser.jj} (lines 1400-1531).
 *
 * <p>Properties that the rest of the server relies on:
 * <ul>
 *   <li>never fails - a character that matches no token becomes one {@link TokenType#BAD}
 *       token and scanning advances by one char;</li>
 *   <li>tokens tile the input: {@code tokens[i].end == tokens[i+1].start}, so the token
 *       stream can be printed back byte for byte;</li>
 *   <li>longest match wins, with keywords beating identifiers at equal length, matching
 *       JavaCC's own resolution order.</li>
 * </ul>
 *
 * <p>Number scanning is deliberately permissive (the upstream comment says it is faster to
 * let the string-to-number conversion detect bad cases). Keeping that looseness means a typo
 * like {@code 1e5x} lexes as one NUMBER and is reported as one value error rather than as a
 * cascade of structural errors.
 */
public final class VrmlLexer {

    private final CharSequence text;
    private int pos;

    public VrmlLexer(CharSequence text) {
        this.text = text;
    }

    public static List<Token> tokenize(CharSequence text) {
        return new VrmlLexer(text).scan();
    }

    private List<Token> scan() {
        List<Token> out = new ArrayList<>(Math.max(16, text.length() / 6));
        while (pos < text.length()) {
            int before = pos;
            Token t = next();
            if (t != null) {
                out.add(t);
            }
            if (pos == before) {
                throw new IllegalStateException("lexer did not advance at offset " + pos);
            }
        }
        out.add(new Token(TokenType.EOF, pos, pos, false));
        return out;
    }

    private Token next() {
        char c = text.charAt(pos);

        if (isWhitespace(c)) {
            int start = pos;
            while (pos < text.length() && isWhitespace(text.charAt(pos))) {
                pos++;
            }
            return new Token(TokenType.WHITESPACE, start, pos, false);
        }
        // A byte order mark is not in JavaCC's ID_FIRST range, so it would lex as garbage in
        // the middle of a name - and six of the corpus files start with one. Only at offset 0
        // does it mean "encoding marker", which is exactly where it must be forgiven.
        if (c == '\uFEFF' && pos == 0) {
            return new Token(TokenType.WHITESPACE, pos, ++pos, false);
        }
        if (c == '#') {
            return comment();
        }
        if (c == '"') {
            return string();
        }
        if (c == '{') {
            return punct(TokenType.LBRACE);
        }
        if (c == '}') {
            return punct(TokenType.RBRACE);
        }
        if (c == '[') {
            return punct(TokenType.LBRACKET);
        }
        if (c == ']') {
            return punct(TokenType.RBRACKET);
        }

        // A number may start with sign and/or dot; only take it when a digit backs it up,
        // otherwise '.' has to stay a DOT (ROUTE uses it) and '+'/'-' fall through to BAD.
        int numberEnd = scanNumberEnd(pos);
        if (numberEnd > pos) {
            Token t = new Token(TokenType.NUMBER, pos, numberEnd, false);
            pos = numberEnd;
            return t;
        }
        if (c == '.') {
            return punct(TokenType.DOT);
        }
        if (isIdentFirst(c)) {
            return identifier();
        }
        return new Token(TokenType.BAD, pos, ++pos, false);
    }

    private Token punct(TokenType type) {
        int start = pos;
        pos++;
        return new Token(type, start, pos, false);
    }

    /** {@code #} runs to the end of the line; the terminator belongs to the comment. */
    private Token comment() {
        int start = pos;
        while (pos < text.length() && !isLineBreak(text.charAt(pos))) {
            pos++;
        }
        if (pos < text.length()) {
            if (text.charAt(pos) == '\r' && pos + 1 < text.length() && text.charAt(pos + 1) == '\n') {
                pos++;
            }
            pos++;
        }
        return new Token(TokenType.COMMENT, start, pos, false);
    }

    /**
     * String literal, following the Java-style escape set the grammar uses.
     *
     * <p>Line breaks are legal inside the literal: upstream spells the body
     * {@code (~["\"","\\"])* }, and every {@code Script url "javascript: ..."} in the corpus
     * relies on it. Strict VRML97 would demand {@code \n} escapes, so this is one of the
     * "Relaxed" dialect's widenings and is listed in GRAMMAR-MAPPING.md.
     *
     * <p>What is <em>not</em> upstream behaviour is the fallback: when no closing quote exists
     * ahead, or an escape is malformed, the token stops at the line break and is flagged.
     * Letting it run to EOF - which is what a generated scanner does - would let one missing
     * quote hide every statement below it, and an editor must stay useful mid-typing.
     */
    private Token string() {
        int start = pos;
        pos++; // opening quote
        if (lastQuoteIndex() <= start) {
            return unterminated(start);
        }
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '"') {
                pos++;
                return new Token(TokenType.STRING, start, pos, false);
            }
            if (c == '\\') {
                int after = pos + 1;
                int escapeLen = escapeLength(after);
                if (escapeLen <= 0) {
                    return unterminated(start);
                }
                pos = after + escapeLen;
                continue;
            }
            pos++;
        }
        return unterminated(start);
    }

    /** Close the literal at the end of its line and record that the quote never came back. */
    private Token unterminated(int start) {
        pos = start + 1;
        while (pos < text.length() && !isLineBreak(text.charAt(pos))) {
            pos++;
        }
        return new Token(TokenType.STRING, start, pos, true);
    }

    private int lastQuoteCache = -2;

    /** Index of the final quote in the document, so a hopeless scan costs one pass, not one per token. */
    private int lastQuoteIndex() {
        if (lastQuoteCache == -2) {
            int at = text.length();
            while (at-- > 0 && text.charAt(at) != '"') {
                // empty body - scanning backwards for the last quote
            }
            lastQuoteCache = at;
        }
        return lastQuoteCache;
    }

    /** @return how many characters follow the backslash in a valid escape, or -1. */
    private int escapeLength(int at) {
        if (at >= text.length()) {
            return -1;
        }
        char c = text.charAt(at);
        switch (c) {
            case 'n': case 't': case 'b': case 'r': case 'f':
            case '\\': case '\'': case '"':
                return 1;
            default:
                break;
        }
        if (c < '0' || c > '7') {
            return -1;
        }
        // Octal: up to three digits, capped at \377 the way the grammar spells it out.
        int count = 1;
        if (c <= '3' && at + 2 < text.length() && isOctal(text.charAt(at + 1)) && isOctal(text.charAt(at + 2))) {
            count = 3;
        } else if (at + 1 < text.length() && isOctal(text.charAt(at + 1))) {
            count = 2;
        }
        return count;
    }

    /**
     * Identifier or keyword. The keyword table is applied to the whole lexeme, so
     * {@code DEFAULT} stays an identifier while {@code DEF} on its own is the keyword.
     */
    private Token identifier() {
        int start = pos;
        pos++;
        while (pos < text.length() && isIdentRest(text.charAt(pos))) {
            pos++;
        }
        return new Token(keywordFor(text, start, pos), start, pos, false);
    }

    private static TokenType keywordFor(CharSequence s, int start, int end) {
        int len = end - start;
        for (int i = 0; i < KEYWORDS.length; i++) {
            String kw = KEYWORDS[i][0];
            if (kw.length() == len && matches(s, start, kw)) {
                return KEYWORD_TYPES[i];
            }
        }
        return TokenType.IDENT;
    }

    private static boolean matches(CharSequence s, int start, String word) {
        for (int i = 0; i < word.length(); i++) {
            if (s.charAt(start + i) != word.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Declaration order of the keyword TOKEN sections; only used for the exact-match table. */
    private static final String[][] KEYWORDS = {
        {"DEF"}, {"USE"}, {"NULL"}, {"PROTO"}, {"eventIn"}, {"eventOut"}, {"field"},
        {"exposedField"}, {"EXTERNPROTO"}, {"ROUTE"}, {"TO"}, {"IS"},
        {"Script"}, {"TRUE"}, {"FALSE"},
    };

    private static final TokenType[] KEYWORD_TYPES = {
        TokenType.KW_DEF, TokenType.KW_USE, TokenType.KW_NULL, TokenType.KW_PROTO,
        TokenType.KW_EVENT_IN, TokenType.KW_EVENT_OUT, TokenType.KW_FIELD,
        TokenType.KW_EXPOSED_FIELD, TokenType.KW_EXTERNPROTO, TokenType.KW_ROUTE,
        TokenType.KW_TO, TokenType.KW_IS, TokenType.KW_SCRIPT, TokenType.KW_TRUE,
        TokenType.KW_FALSE,
    };

    /**
     * End offset of a NUMBER starting at {@code at}, or {@code at} when there is none.
     *
     * <p>Grammar: {@code (["-","+"])? (".")? ["0"-"9"] (["0"-"9","a"-"f","A"-"F","x",".","+","-"])* }
     */
    private int scanNumberEnd(int at) {
        int i = at;
        if (i < text.length() && (text.charAt(i) == '-' || text.charAt(i) == '+')) {
            i++;
        }
        if (i < text.length() && text.charAt(i) == '.') {
            i++;
        }
        if (i >= text.length() || text.charAt(i) < '0' || text.charAt(i) > '9') {
            return at;
        }
        i++;
        while (i < text.length() && isNumberRest(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isNumberRest(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || c == 'x' || c == '.' || c == '+' || c == '-';
    }

    /** Whitespace per {@code <*> SKIP}, which famously includes the comma. */
    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == ',';
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r';
    }

    private static boolean isOctal(char c) {
        return c >= '0' && c <= '7';
    }

    /** {@code <#ID_FIRST>}: printable punctuation minus what VRML uses itself, letters, and high planes. */
    private static boolean isIdentFirst(char c) {
        return c == '\u0021'
                || (c >= '\u0024' && c <= '\u0026')
                || (c >= '\u0028' && c <= '\u002a')
                || c == '\u002f'
                || (c >= '\u003a' && c <= '\u005a')
                || (c >= '\u005e' && c <= '\u007a')
                || (c >= '\u0080' && c <= '\ufaff');
    }

    /** {@code <#ID_REST>}: ID_FIRST plus {@code + - | ~}. */
    private static boolean isIdentRest(char c) {
        return c == '\u0021'
                || (c >= '\u0024' && c <= '\u0026')
                || (c >= '\u0028' && c <= '\u002b')
                || c == '\u002d'
                || (c >= '\u002f' && c <= '\u005a')
                || (c >= '\u005e' && c <= '\u007a')
                || c == '\u007c'
                || c == '\u007e'
                || (c >= '\u0080' && c <= '\ufaff');
    }
}
