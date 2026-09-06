package org.vrml.lsp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.diagnostics.DocumentAnalyzer;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.semantic.SymbolTable;
import org.vrml.lsp.services.Context.Kind;

/**
 * Where the cursor stands, for every shape a VRML97 file puts it in.
 *
 * <p>Each case writes the document with a {@code ^} for the caret, which is taken out before the
 * text is parsed - so what is analysed is a real file, not one carrying a marker the lexer would
 * have to make sense of. The offset is where the marker was, which is what a client sends.
 *
 * <p>These are the assertions the completion provider is built on: a wrong kind here is a wrong list
 * there, and every one of them is a position an editor actually asks about.
 */
class ContextTest {

    private static final char CARET = '^';
    private static final String HEADER = "#VRML V2.0 utf8\n";

    /** The context and what it refers to, so a test can name the node behind a field list. */
    private record Placed(Context context, ParseResult parse) {

        /** The node the cursor stands in, empty at the file root. */
        String node() {
            CstNode enclosing = context.node();
            return enclosing == null ? "" : SymbolTable.nodeType(parse, enclosing);
        }
    }

    private static Placed at(String document) {
        int caret = document.indexOf(CARET);
        assertTrue(caret >= 0, "the case has to mark where the cursor is: " + document);
        assertEquals(-1, document.indexOf(CARET, caret + 1), "one cursor per case");
        String text = document.substring(0, caret) + document.substring(caret + 1);
        DocumentAnalyzer.Analysis analysis = DocumentAnalyzer.analyze(text);
        return new Placed(Context.of(analysis.parse(), analysis.symbols(), caret), analysis.parse());
    }

    private static Kind kind(String document) {
        return at(document).context().kind();
    }

    // ---- statement positions -------------------------------------------------------------------

    @Test
    void anEmptyFileIsAskingForANode() {
        assertEquals(Kind.NODE_STATEMENT, kind(String.valueOf(CARET)));
        assertEquals(Kind.NODE_STATEMENT, kind(HEADER + CARET));
    }

    @Test
    void aNodeNameAtTheRootIsStillBeingTypedAsANodeName() {
        Placed placed = at(HEADER + "Bo" + CARET);
        assertEquals(Kind.NODE_STATEMENT, placed.context().kind());
        assertEquals("Bo", placed.context().prefix());
    }

    @Test
    void defWantsANameAndThenANode() {
        assertEquals(Kind.NEW_NAME, kind(HEADER + "DEF " + CARET));
        assertEquals(Kind.NODE_STATEMENT, kind(HEADER + "DEF portal " + CARET));
        Placed placed = at(HEADER + "DEF po" + CARET);
        assertEquals(Kind.NEW_NAME, placed.context().kind());
        assertEquals("po", placed.context().prefix(), "the name is the author's, not ours to guess");
    }

    @Test
    void useWantsANameTheFileAlreadyHas() {
        Placed placed = at(HEADER + "DEF portal Box {}\nUSE po" + CARET);
        assertEquals(Kind.DEFINED_NAME, placed.context().kind());
        assertEquals("po", placed.context().prefix());
        assertEquals(Kind.DEFINED_NAME, kind(HEADER + "USE " + CARET));
    }

    @Test
    void aStatementIsOverOnceAUseHasItsName() {
        assertEquals(Kind.NONE, kind(HEADER + "USE portal " + CARET));
    }

    // ---- inside a node body --------------------------------------------------------------------

    @Test
    void anOpenedBraceIsAskingForAFieldName() {
        Placed placed = at(HEADER + "Box {" + CARET);
        assertEquals(Kind.FIELD_NAME, placed.context().kind());
        assertEquals("Box", placed.node());
        assertEquals(Kind.FIELD_NAME, at(HEADER + "Box { " + CARET).context().kind());
    }

    @Test
    void aWordInAFieldSlotIsAFieldName() {
        Placed placed = at(HEADER + "Box { si" + CARET);
        assertEquals(Kind.FIELD_NAME, placed.context().kind());
        assertEquals("si", placed.context().prefix());
        assertEquals("Box", placed.node());
    }

    @Test
    void aFieldNameWantsItsValueAsSoonAsTheCursorLeavesIt() {
        Placed placed = at(HEADER + "Box { size " + CARET);
        assertEquals(Kind.FIELD_VALUE, placed.context().kind());
        assertEquals("Box", placed.node());
        // The name itself, with the cursor still on it, is still a name being typed.
        assertEquals(Kind.FIELD_NAME, kind(HEADER + "Box { size" + CARET));
    }

    @Test
    void aFinishedValueMakesRoomForTheNextField() {
        assertEquals(Kind.FIELD_NAME, at(HEADER + "Box { size 1 1 1 " + CARET).context().kind());
        assertEquals(Kind.NONE, at(HEADER + "Box { size 1 1 1" + CARET).context().kind(),
                "a number is not something to complete");
    }

    @Test
    void aBodyAfterANestedNodeBelongsToTheNodeThatHoldsIt() {
        Placed againstBrace = at(HEADER + "Shape { appearance Appearance {}" + CARET);
        assertEquals(Kind.FIELD_NAME, againstBrace.context().kind(),
                "pressed against the closing brace, the cursor stands after the assignment, not in it");
        assertEquals("Shape", againstBrace.node());
        Placed afterSpace = at(HEADER + "Shape { appearance Appearance {} " + CARET);
        assertEquals(Kind.FIELD_NAME, afterSpace.context().kind());
        assertEquals("Shape", afterSpace.node(), "the same slot either way the cursor got there");
    }

    @Test
    void aNestedBodyAsksForItsOwnFields() {
        Placed placed = at(HEADER + "Shape { geometry Box {" + CARET);
        assertEquals(Kind.FIELD_NAME, placed.context().kind());
        assertEquals("Box", placed.node());
    }

    @Test
    void aNodeHoldingFieldIsAValueLikeAnyOtherField() {
        Placed placed = at(HEADER + "Shape { geometry " + CARET);
        assertEquals(Kind.FIELD_VALUE, placed.context().kind());
        assertEquals("Shape", placed.node());
    }

    @Test
    void insideABracketedListTheItemSlotIsTheFieldsValue() {
        Placed list = at(HEADER + "Transform { children [ Box {} " + CARET);
        assertEquals(Kind.FIELD_VALUE, list.context().kind());
        assertEquals("Transform", list.node());
        assertEquals(Kind.FIELD_VALUE, kind(HEADER + "Coordinate { point [ 1 2 3, " + CARET));
    }

    @Test
    void aNodeNameWithNoBraceYetIsNotYetSomewhereYouCanComplete() {
        assertEquals(Kind.NONE, kind(HEADER + "Box " + CARET), "what comes next is a '{', not a list");
    }

    @Test
    void aFreshLineAtTheEndOfTheFileAsksForTheNextStatement() {
        Placed placed = at(HEADER + "Box {}\n" + CARET);
        assertEquals(Kind.NODE_STATEMENT, placed.context().kind());
        assertEquals(23, placed.context().replaceStart(), "the insertion point is the cursor itself");
        assertEquals(Kind.NODE_STATEMENT, kind(HEADER + "Box {}\n\n\n" + CARET));
    }

    @Test
    void aClosedNodeMakesRoomForItsSibling() {
        Placed placed = at(HEADER + "Transform { children [ Box {} ] " + CARET);
        assertEquals(Kind.FIELD_NAME, placed.context().kind());
        assertEquals("Transform", placed.node(), "the list is over, the body is not");
    }

    // ---- ROUTE ---------------------------------------------------------------------------------

    @Test
    void aRouteNamesNodesThenTheirEvents() {
        String route = HEADER + "DEF ts TimeSensor {}\nDEF t Transform {}\nROUTE ";
        assertEquals(Kind.DEFINED_NAME, kind(route + CARET));
        Placed named = at(route + "ts" + CARET);
        assertEquals(Kind.DEFINED_NAME, named.context().kind(),
                "a name already written in a ROUTE is still the author's choice of definition");
        assertEquals("ts", named.context().prefix());
        Placed output = at(route + "ts." + CARET);
        assertEquals(Kind.ROUTE_OUTPUT, output.context().kind());
        assertEquals("ts", output.context().routeSubject());
        assertEquals(Kind.ROUTE_OUTPUT, kind(route + "ts.fraction_chan" + CARET));
        Placed input = at(route + "ts.fraction_changed TO " + CARET);
        assertEquals(Kind.DEFINED_NAME, input.context().kind());
        Placed target = at(route + "ts.fraction_changed TO t." + CARET);
        assertEquals(Kind.ROUTE_INPUT, target.context().kind());
        assertEquals("t", target.context().routeSubject());
    }

    @Test
    void aFinishedRouteLeadsBackToTheFile() {
        String route = HEADER + "DEF ts TimeSensor {}\nDEF t Transform {}\n"
                + "ROUTE ts.fraction_changed TO t.set_rotation\n";
        assertEquals(Kind.NODE_STATEMENT, kind(route + CARET));
    }

    // ---- PROTO, Script and URLs ----------------------------------------------------------------

    @Test
    void anInterfaceLineGoesKeywordTypeThenName() {
        assertEquals(Kind.ACCESS_TYPE, kind(HEADER + "PROTO Thing [ " + CARET));
        assertEquals(Kind.FIELD_TYPE, kind(HEADER + "PROTO Thing [ field " + CARET));
        assertEquals(Kind.NEW_NAME, kind(HEADER + "PROTO Thing [ field SFVec3f " + CARET));
        Placed value = at(HEADER + "PROTO Thing [ field SFVec3f offset " + CARET);
        assertEquals(Kind.FIELD_VALUE, value.context().kind());
        assertEquals(Kind.FIELD_TYPE, kind(HEADER + "PROTO Thing [ field SFVe" + CARET));
    }

    @Test
    void aProtoBodyStartsBuildingSomething() {
        assertEquals(Kind.NODE_STATEMENT, kind(HEADER + "PROTO Thing [ exposedField SFVec3f o ] { "
                + CARET), "a PROTO body holds a node statement, and its own scope decides the names");
    }

    @Test
    void isBindsToTheInterfaceOfTheProtoItsBodyBelongsTo() {
        Placed placed = at(HEADER + "PROTO Thing [ field SFVec3f offset ] {\n"
                + "  Transform { translation IS " + CARET + "\n  }\n}\n");
        assertEquals(Kind.INTERFACE_NAME, placed.context().kind());
        assertEquals("Thing", placed.context().defining().name());
        assertEquals("offset", placed.context().defining().field("offset").name());
    }

    @Test
    void aUrlSlotCompletesAgainstTheFilesBesideIt() {
        Placed placed = at(HEADER + "EXTERNPROTO Thing [ field SFVec3f offset ] [ \"" + CARET);
        assertEquals(Kind.URI, placed.context().kind());
    }

    @Test
    void aScriptsOwnInterfaceIsItsBodyToo() {
        Placed script = at(HEADER + "Script {\n  eventIn SFTime " + CARET);
        assertEquals(Kind.NEW_NAME, script.context().kind());
        assertEquals(Kind.FIELD_NAME, kind(HEADER + "Script {\n  " + CARET));
        assertEquals(Kind.FIELD_VALUE, kind(HEADER + "Script {\n  field SFVec3f offset " + CARET),
                "a field declaration in a Script body carries its own default value");
        assertEquals(Kind.FIELD_NAME, kind(HEADER + "Script {\n  eventIn SFTime tick " + CARET),
                "an eventIn is complete on its own, so the next element is next");
    }

    // ---- where nothing belongs -----------------------------------------------------------------

    @Test
    void aCommentIsNoOnesBusiness() {
        assertEquals(Kind.NONE, kind(HEADER + "Box { size 1 1 1 }\n# note " + CARET));
        assertEquals(Kind.NONE, kind(HEADER + "# " + CARET + "\nBox {}"));
    }

    @Test
    void debrisIsNotCompleted() {
        assertEquals(Kind.NONE, kind(HEADER + "IMPORT " + CARET));
    }
}
