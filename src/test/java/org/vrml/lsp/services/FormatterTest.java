package org.vrml.lsp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.vrml.lsp.lexer.Token;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.parser.VrmlParser;

/**
 * The reflow's rules, one case each, and the invariants every reflow has to satisfy.
 *
 * <p>Each case states its expected output in full, because whitespace is the subject: "it looks
 * tidier" is not checkable, and a layout that drifts is a whole-project diff the user did not ask
 * for. The two-space step and the 80-column line are the corpus's own style, so the expectations
 * below are what the files under {@code parsetest} already look like written out by hand.
 *
 * <p>Four things are asserted of every case, not only the one under test: the significant bytes are
 * unchanged, the commas and comment marks survive, the edits add up to the formatted text, and a
 * second pass changes nothing. Idempotence in particular is what makes the result safe to apply to
 * a buffer the user is still typing in, so it is checked rather than demonstrated.
 */
class FormatterTest {

    private static final String HEADER = "#VRML V2.0 utf8\n";
    private static final Formatter.Options TWO = new Formatter.Options(2, 80);

    // ---- where lines start --------------------------------------------------

    @Test
    void aTightNodeOpensTheCorpusLayout() {
        assertEquals(HEADER + "DEF a Transform {\n  translation 0 0 0\n}\n",
                reflow(HEADER + "DEF a Transform{translation 0 0 0}\n"));
    }

    @Test
    void oneFieldPerLineInsideANodeBody() {
        assertEquals(HEADER + "Shape {\n  geometry Box {\n    size 1 1 1\n  }\n"
                        + "  appearance Appearance {}\n}\n",
                reflow(HEADER + "Shape { geometry Box { size 1 1 1 } appearance Appearance {\n}"
                        + " }\n"));
    }

    @Test
    void aListOfNodesBreaksByRuleNotByWidth() {
        // The list fits in 74 columns, and is still one node per line: statements, not numbers.
        assertEquals(HEADER + "Transform {\n  children [\n    Shape {\n      geometry Box {}\n    }\n"
                        + "    USE other\n  ]\n}\n",
                reflow(HEADER + "Transform { children [ Shape { geometry Box {} } USE other ] }\n"));
    }

    @Test
    void aPrototypeWritesItsInterfaceOneEntryPerLineAndItsBodyUnderneath() {
        assertEquals(HEADER + "PROTO Lamp [\n  field SFFloat intensity 1\n]\n{\n  PointLight {\n"
                        + "    intensity IS intensity\n  }\n}\n",
                reflow(HEADER + "PROTO Lamp [ field SFFloat intensity 1 ] {\nPointLight {"
                        + " intensity IS intensity }\n}\n"));
    }

    @Test
    void anImportedPrototypePutsItsSourcesOnTheirOwnLine() {
        assertEquals(HEADER + "EXTERNPROTO Foo [\n  field SFVec3f size\n]\n[ \"a\", \"b\" ]\n",
                reflow(HEADER + "EXTERNPROTO Foo [ field SFVec3f size ] [ \"a\",\"b\" ]\n"));
    }

    // ---- width --------------------------------------------------------------

    @Test
    void aWideValueListFoldsAtItsElements() {
        String unit = "1.2345678 2.3456789 3.4567890";
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            list.append(unit).append(i < 11 ? ", " : "");
        }
        String once = reflow(HEADER + "Coordinate {\n point [\n" + list + " ]\n}\n");
        assertTrue(once.startsWith(HEADER + "Coordinate {\n  point [\n    1.2345678"), once);
        assertTrue(once.endsWith("    3.4567890\n  ]\n}\n"), once);
        for (String line : once.split("\n", -1)) {
            assertTrue(line.length() <= 80, "a folded line ran over: <" + line + ">");
        }
    }

    @Test
    void aLineThatCannotBeFoldedIsLeftAlone() {
        String url = "\"http://example.com/" + "x".repeat(70) + ".png\"";
        // Nothing may be cut out of a string, so the list still breaks - and that one line stays long.
        assertEquals(HEADER + "ImageTexture {\n  url [\n    " + url + "\n  ]\n}\n",
                reflow(HEADER + "ImageTexture {\n url [ " + url + " ]\n}\n"));
    }

    @Test
    void theIndentIsWhatTheClientAskedFor() {
        assertEquals(HEADER + "DEF a Transform {\n    translation 0 0 0\n}\n",
                reflow(HEADER + "DEF a Transform {\n  translation 0 0 0\n}\n",
                        new Formatter.Options(4, 80)));
    }

    @Test
    void settingsOutOfRangeAreClampedRatherThanObeyed() {
        assertEquals(new Formatter.Options(0, 20), Formatter.Options.clamp(new Formatter.Options(-3, 5)));
        assertEquals(new Formatter.Options(16, 400),
                Formatter.Options.clamp(new Formatter.Options(99, 9999)));
        assertEquals(TWO, Formatter.Options.clamp(TWO), "a sane setting should not move");
    }

    // ---- what is never touched ----------------------------------------------

    @Test
    void anEmptyListKeepsItsBracketsTogether() {
        assertEquals(HEADER + "Transform {\n  children []\n  translation 0 0 0\n}\n",
                reflow(HEADER + "Transform { children [ ] translation 0 0 0 }\n"));
    }

    @Test
    void aValueListIsSpacedInsideItsBrackets() {
        assertEquals(HEADER + "ImageTexture {\n  url [ \"a.png\", \"b.png\" ]\n}\n",
                reflow(HEADER + "ImageTexture { url [\"a.png\",\"b.png\"] }\n"));
    }

    @Test
    void commasStayWhereTheGrammarIgnoresThem() {
        assertEquals(HEADER + "Coordinate {\n  point [ 0 0 0, 1 0 0, 0 1 0 ]\n}\n",
                reflow(HEADER + "Coordinate { point [ 0 0 0,\n 1 0 0 ,\n 0 1 0 ] }\n"));
    }

    @Test
    void theDotsOfARouteAreNotWidened() {
        assertEquals(HEADER + "ROUTE timer.fraction TO morph.set_fraction\n",
                reflow(HEADER + "ROUTE timer . fraction TO morph . set_fraction\n"));
    }

    @Test
    void namesAndStringsAreCopiedAndNotInterpreted() {
        assertEquals(HEADER + "DEF weird_1-named Box {\n  size 1 1 1\n}\nUSE weird_1-named\n",
                reflow(HEADER + "DEF weird_1-named Box { size 1 1 1 }\nUSE weird_1-named\n"));
    }

    @Test
    void aScriptsBodyIsCopiedOutOfItsString() {
        assertEquals(HEADER + "Script {\n  url \"javascript:\nfunction f(){ print(1); }\"\n}\n",
                reflow(HEADER + "Script {\n\turl \"javascript:\nfunction f(){ print(1); }\"\n}\n"));
    }

    @Test
    void commentsStayOnTheSideOfTheTokenTheyWereOn() {
        // The first comment followed `{`, so it stays there; the second began a line, so it keeps one.
        assertEquals(HEADER + "Transform { # what\n  # else\n  translation 0 0 0\n} # tail\n",
                reflow(HEADER + "Transform { # what\n # else\n  translation 0 0 0 } # tail\n"));
    }

    @Test
    void blankLinesBetweenStatementsSurviveAsOne() {
        assertEquals(HEADER + "\nDEF a Box {}\n\nDEF b Box {}\n",
                reflow(HEADER + "\n\nDEF a Box {\n\n\n}\n\n\n\nDEF b Box {}\n"));
    }

    @Test
    void tabsAndRunsOfSpacesCollapseToOneStep() {
        assertEquals(HEADER + "Shape {\n  geometry Box {}\n}\n",
                reflow(HEADER + "\t\tShape\t{\n\t\tgeometry\tBox\t{\n\t\t}\n\t\t}\n"));
    }

    @Test
    void theDocumentEndsWithExactlyOneNewline() {
        assertEquals(HEADER + "DEF a Transform {}\n", reflow(HEADER + "DEF a Transform {}"));
    }

    @Test
    void aWindowsDocumentKeepsItsLineEnding() {
        assertEquals(HEADER.replace("\n", "\r\n") + "DEF a Transform {\r\n  translation 0 0 0\r\n}\r\n",
                reflow("#VRML V2.0 utf8\r\nDEF a Transform{\r\n  translation 0 0 0\r\n}\r\n"));
    }

    @Test
    void oneStrayWindowsBreakDoesNotConvertTheFile() {
        // The ending is the one the document mostly uses; a lone CRLF is a paste, and adopting it as
        // house style would rewrite every line of a file whose author asked for two spaces to be fixed.
        assertEquals(HEADER + "DEF a Box {}\n\nDEF b Box {}\n",
                reflow(HEADER + "DEF a Box{}\r\n\nDEF b Box{}\n"));
    }

    @Test
    void aMostlyWindowsDocumentDoesNotGetConvertedBecauseOfItsHeader() {
        // The other direction of the same judgement, and the tidying is unaffected by either.
        assertEquals("#VRML V2.0 utf8\r\nDEF a Box {\r\n  size 1 1 1\r\n}\r\n",
                reflow("#VRML V2.0 utf8\nDEF a Box {\r\n  size  1 1 1\r\n}\r\n"));
    }

    @Test
    void aTidyDocumentProducesNoEditsAtAll() {
        String tidy = HEADER + "DEF a Transform {\n  translation 0 0 0\n}\n";
        assertEquals(List.of(), Formatter.edits(VrmlParser.parse(tidy), TWO));
        assertEquals(tidy, reflow(tidy));
    }

    @Test
    void aSelectionReflowsOnlyWhatItCovers() {
        String document = HEADER + "DEF a Transform{ translation 0 0 0 }\nDEF b Box{\n}\n";
        ParseResult parse = VrmlParser.parse(document);
        int from = document.indexOf("DEF b");
        List<Formatter.Edit> selected = Formatter.edits(parse, TWO, from, document.length());
        assertEquals(2, selected.size(), "only the two spaces inside the selection should move: "
                + selected);
        for (Formatter.Edit edit : selected) {
            assertTrue(edit.start() >= from && edit.end() <= document.length(),
                    "an edit reached outside the selection: " + edit);
        }
        assertEquals(HEADER + "DEF a Transform{ translation 0 0 0 }\nDEF b Box {}\n",
                apply(document, selected), "a line outside the selection was rewritten");
        assertTrue(Formatter.edits(parse, TWO).size() > selected.size(),
                "the same selection should be fewer edits than the whole document");
    }

    // ---- the safety net -----------------------------------------------------

    @Test
    void aDocumentWithAStructuralErrorStillLosesNothing() {
        String broken = HEADER + "Transform {\n  translation 0 0 0\n";
        // Whether to reformat a broken file is the server's call; this layer's promise is that the
        // bytes are safe either way, which is what makes the refusal a policy and not a necessity.
        ParseResult parse = VrmlParser.parse(broken);
        assertTrue(parse.hasErrors(), "the case is meant to be a file the parser complains about");
        assertEquals(broken, reflow(broken, TWO, false));
    }

    /** Reflows a document the grammar accepts, checking every invariant along the way. */
    private static String reflow(String document) {
        return reflow(document, TWO);
    }

    private static String reflow(String document, Formatter.Options options) {
        return reflow(document, options, true);
    }

    private static String reflow(String document, Formatter.Options options, boolean sound) {
        ParseResult parse = VrmlParser.parse(document);
        if (sound) {
            assertFalse(parse.hasErrors(),
                    "the case is a file the formatter would refuse: " + parse.issues());
        }
        String once = Formatter.format(parse, options);
        assertEquals(apply(document, Formatter.edits(parse, options)), once,
                "the edits do not add up to the formatted text");
        assertEquals(once, Formatter.format(VrmlParser.parse(once), options),
                "formatting the result again changed it:\n" + once);
        assertEquals(significant(document), significant(once), "a character the grammar read changed");
        assertEquals(count(document, ','), count(once, ','), "a comma appeared or vanished");
        assertEquals(count(document, '#'), count(once, '#'), "a comment appeared or vanished");
        return once;
    }

    /** The document the edits produce, applied in order: what a client that honours them will show. */
    private static String apply(String document, List<Formatter.Edit> edits) {
        StringBuilder out = new StringBuilder(document.length());
        int at = 0;
        for (Formatter.Edit edit : edits) {
            assertTrue(edit.start() >= at, "edits must be ordered and disjoint, got " + edit);
            out.append(document, at, edit.start()).append(edit.text());
            at = edit.end();
        }
        return out.append(document, at, document.length()).toString();
    }

    /** Every character the grammar looked at, with the whitespace between them thrown away. */
    private static String significant(String document) {
        StringBuilder words = new StringBuilder();
        ParseResult parse = VrmlParser.parse(document);
        for (Token token : parse.significantTokens()) {
            words.append(document, token.start, token.end);
        }
        return words.toString();
    }

    private static int count(String text, char wanted) {
        int found = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == wanted) {
                found++;
            }
        }
        return found;
    }
}
