package org.vrml.lsp.lexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Lexical rules, one test per declaration in {@code VRML97RelaxedParser.jj} lines 1400-1531.
 *
 * <p>These behaviours are observable from a file, so they are pinned here instead of being
 * left to the corpus test to catch indirectly.
 */
class VrmlLexerTest {

    /** Significant tokens as {@code TYPE=image}, trivia left out for readability. */
    private static List<String> sig(String src) {
        List<String> out = new ArrayList<>();
        for (Token t : VrmlLexer.tokenize(src)) {
            if (!t.type.isTrivia() && t.type != TokenType.EOF) {
                out.add(t.type + "=" + t.image(src));
            }
        }
        return out;
    }

    /** The lexer's contract: tokens touch, and together they are exactly the input. */
    private static void assertTiles(String src) {
        StringBuilder rebuilt = new StringBuilder();
        int expected = 0;
        for (Token t : VrmlLexer.tokenize(src)) {
            assertEquals(expected, t.start, "gap before " + t + " in [" + src + "]");
            assertTrue(t.end >= t.start, "token ends before it starts: " + t);
            expected = t.end;
            if (t.type != TokenType.EOF) {
                rebuilt.append(src, t.start, t.end);
            }
        }
        assertEquals(src, rebuilt.toString(), "token stream does not reproduce [" + src + "]");
    }

    @Test
    void commaIsWhitespaceNotAValueSeparator() {
        // JavaCC's SKIP production contains ',', so MF values may be comma separated.
        assertEquals(List.of("NUMBER=1", "NUMBER=2"), sig("1,2"));
        List<Token> all = VrmlLexer.tokenize("1,2");
        assertEquals(TokenType.WHITESPACE, all.get(1).type);
        assertEquals(",", all.get(1).image("1,2"));
        assertTiles("1,2");
    }

    @Test
    void whitespaceRunIsOneToken() {
        assertEquals(List.of("IDENT=a", "IDENT=b"), sig("a  \t\r\n\f b"));
    }

    @Test
    void commentRunsToTheLineBreakIncludingIt() {
        // Brackets inside a comment must not reach the parser.
        assertEquals(List.of("IDENT=a", "IDENT=b"), sig("a # c}{[\n b"));
        assertTiles("#VRML V2.0 utf8\nBox {}");
    }

    @Test
    void numberAcceptsSignLeadingDotAndExponentGarbage() {
        // The upstream regex is deliberately loose; conversion errors are the value parser's job.
        for (String literal : List.of("1", "-1", "+1.5", ".5", "-.5", "1e-5", "1.2.3", "0x1f", "1abc")) {
            assertEquals(List.of("NUMBER=" + literal), sig(literal), "for " + literal);
        }
    }

    @Test
    void dotStaysADotAndBareSignIsBadWhenNoDigitBacksIt() {
        assertEquals(List.of("DOT=.", "IDENT=x"), sig(".x"));
        assertEquals(List.of("BAD=+", "NUMBER=.5"), sig("+ .5"));
    }

    @Test
    void dotAfterDigitsIsAbsorbedByTheNumber() {
        assertEquals(List.of("NUMBER=1.5", "IDENT=a"), sig("1.5 a"));
    }

    @Test
    void identifiersAcceptPunctuationOutsideVrmlsOwnSet() {
        // "!^\%&|~" is a valid ID per the .jj comment; here is the same claim token by token.
        assertEquals(List.of("IDENT=!$%&()*-/z"), sig("!$%&()*-/z"));
        assertTiles("!$%&()*-/z");
    }

    @Test
    void identifierMayContainPlusMinusPipeTildeButNotStartWithThem() {
        assertEquals(List.of("IDENT=a+b-c|d~e"), sig("a+b-c|d~e"));
        assertEquals(List.of("BAD=|", "IDENT=a"), sig("|a"));
    }

    @Test
    void squareBracketsAndQuotesAndCommasEndAnIdentifier() {
        assertEquals(List.of("IDENT=a", "LBRACKET=[", "IDENT=b", "RBRACKET=]"), sig("a[b]"));
        assertEquals(List.of("IDENT=a", "STRING=\"b\""), sig("a\"b\""));
        assertEquals(List.of("IDENT=a", "IDENT=b"), sig("a,b"));
    }

    @Test
    void keywordsBeatIdentifiersOnlyOnAnExactWholeLexeme() {
        assertEquals(List.of("KW_DEF=DEF", "IDENT=DEFX", "KW_IS=IS", "IDENT=ISX"), sig("DEF DEFX IS ISX"));
        assertEquals(List.of("IDENT=DEFAULT"), sig("DEFAULT"));
        assertEquals(List.of("KW_SCRIPT=Script", "IDENT=script"), sig("Script script"));
        assertEquals(List.of("KW_EXPOSED_FIELD=exposedField", "IDENT=exposedfield"),
                sig("exposedField exposedfield"));
    }

    @Test
    void stringKeepsJavaStyleAndOctalEscapes() {
        assertEquals(List.of("STRING=\"a\\\"b\""), sig("\"a\\\"b\""));
        assertEquals(List.of("STRING=\"\\101x\""), sig("\"\\101x\""));
        assertTiles("url \"x\\n\"");
    }

    @Test
    void stringLiteralMaySpanLines() {
        // Upstream's body is (~["\"","\\"])*, which matches line breaks, and Script sources in
        // the corpus are written as "javascript: …" across dozens of lines.
        String src = "url [ \"javascript:\n  function x() { return 1 }\n\" ]";
        assertTiles(src);
        assertEquals(List.of("IDENT=url", "LBRACKET=[", "STRING=" + src.substring(6, src.length() - 2),
                "RBRACKET=]"), sig(src));
    }

    @Test
    void unterminatedStringStopsAtTheLineBreakAndIsFlagged() {
        String src = "url \"oops\nBox {}";
        List<Token> tokens = VrmlLexer.tokenize(src);
        Token flagged = null;
        for (Token t : tokens) {
            if (t.truncated) {
                flagged = t;
            }
        }
        assertTrue(flagged != null, "expected a truncated token");
        assertEquals(TokenType.STRING, flagged.type);
        // One bad quote must not blind the server to every statement after it.
        assertEquals(List.of("IDENT=url", "STRING=\"oops", "IDENT=Box", "LBRACE={", "RBRACE=}"), sig(src));
        assertTiles(src);
    }

    @Test
    void badEscapeEndsTheStringRatherThanEatingTheFile() {
        String src = "\"a\\q\"\nBox {}";
        assertTiles(src);
        assertTrue(sig(src).get(0).startsWith("STRING="), sig(src).toString());
    }

    @Test
    void unknownCharactersBecomeOneBadTokenEachSoScanningAlwaysAdvances() {
        assertEquals(List.of("BAD='", "BAD=\\", "BAD=|", "BAD=~"), sig("'\\|~"));
        assertTiles("'\\|~");
    }

    @Test
    void emptyAndBlankInputsStillProduceAStream() {
        for (String src : List.of("", " ", "\n", "#", "\"", "\u0000")) {
            List<Token> tokens = VrmlLexer.tokenize(src);
            assertEquals(TokenType.EOF, tokens.get(tokens.size() - 1).type, "for [" + src + "]");
            assertTiles(src);
        }
    }

    @Test
    void highPlaneCharactersAreIdentifierMaterialUpToUfaff() {
        assertEquals(List.of("IDENT=\u00e9x"), sig("\u00e9x"));
        // The JavaCC table stops at \u0080-\ufaff, so a BOM is not a name character;
        // it is forgiven at offset 0, where it is an encoding marker, and nowhere else.
        assertEquals(List.of(), sig("\uFEFF"));
        assertEquals(List.of("IDENT=a", "BAD=\uFEFF"), sig("a\uFEFF"));
        assertTiles("\u00e9x\uFEFFa\uFEFF");
    }

    @Test
    void corpusShapedHeaderLexesAsTriviaThenNodes() {
        String src = "#VRML V2.0 utf8\n# generated\ndef Background { skyColor [0 0 0, 1 1 1] }";
        assertTiles(src);
        assertEquals(List.of("IDENT=def", "IDENT=Background", "LBRACE={", "IDENT=skyColor", "LBRACKET=[",
                "NUMBER=0", "NUMBER=0", "NUMBER=0", "NUMBER=1", "NUMBER=1", "NUMBER=1", "RBRACKET=]",
                "RBRACE=}"), sig(src));
    }
}
