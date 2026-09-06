package org.vrml.lsp.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.diagnostics.Codes;
import org.vrml.lsp.diagnostics.Issue;

/**
 * Production-by-production checks, each naming the ISO rule it covers.
 *
 * <p>The corpus test proves the good path stays intact; this class pins the tree shapes the
 * services will read, so a recovery change in M2 cannot quietly move a node type or drop a name.
 */
class VrmlParserTest {

    private static List<Integer> codes(ParseResult r) {
        List<Integer> out = new ArrayList<>();
        for (Issue i : r.issues()) {
            out.add(i.code());
        }
        return out;
    }

    private static ParseResult assertClean(String src) {
        ParseResult r = VrmlParser.parse(src);
        assertEquals(List.of(), codes(r), () -> "unexpected issues for [" + src + "]");
        assertEquals(List.of(), r.cstHoles(), () -> "CST holes for [" + src + "]");
        assertEquals(src, r.printCst(), () -> "print for [" + src + "]");
        return r;
    }

    private static List<CstNode> findAll(CstNode node, CstKind kind, List<CstNode> out) {
        if (node.kind() == kind) {
            out.add(node);
        }
        for (int i = 0; i < node.childCount(); i++) {
            findAll(node.child(i), kind, out);
        }
        return out;
    }

    private static List<CstNode> of(ParseResult r, CstKind kind) {
        return findAll(r.root(), kind, new ArrayList<>());
    }

    private static CstNode first(ParseResult r, CstKind kind) {
        List<CstNode> found = of(r, kind);
        assertTrue(!found.isEmpty(), "no " + kind + " node");
        return found.get(0);
    }

    @Test
    void rule0And12EmptyNodeIsASceneWithOneStatement() {
        ParseResult r = assertClean("#VRML V2.0 utf8\nSphere {}\n");
        assertEquals(CstKind.SCENE, r.root().kind());
        assertEquals(1, of(r, CstKind.STATEMENT).size());
        assertEquals("Sphere", r.text(first(r, CstKind.NODE_TYPE)));
        assertEquals(1, of(r, CstKind.NODE_BODY).size());
    }

    @Test
    void rule23And24SingleValueCarriesEveryNumberOfAnSfVec3f() {
        ParseResult r = assertClean("Transform { translation 1 2 3 }");
        assertEquals("1 2 3", r.text(first(r, CstKind.SF_VALUE)).trim());
        assertEquals(3, of(r, CstKind.NUMBER).size());
    }

    @Test
    void rule25MultipleValuePicksItsElementKindFromTheFirstItem() {
        ParseResult numbers = assertClean("IndexedFaceSet { coordIndex [ 1, 2, -1 ] }");
        assertEquals(1, of(numbers, CstKind.NUMBER_ARRAY).size());

        ParseResult strings = assertClean("ImageTexture { url [ \"a.png\" \"b.png\" ] }");
        assertEquals(1, of(strings, CstKind.STRING_ARRAY).size());

        ParseResult nodes = assertClean("Group { children [ Shape {} DEF s Sphere {} ] }");
        CstNode list = first(nodes, CstKind.NODE_LIST);
        assertEquals(2, list.childCount(), list.children().toString());
        // Two inside the list plus the outer Group statement.
        assertEquals(3, of(nodes, CstKind.NODE_STATEMENT).size());

        ParseResult empty = assertClean("IndexedFaceSet { coordIndex [ ] }");
        assertEquals(1, of(empty, CstKind.MF_VALUE).size());
        assertEquals(0, of(empty, CstKind.NUMBER_ARRAY).size());

        // Upstream's three alternatives are homogeneous, so a mixed array is one structural
        // error at the first item that cannot belong to the chosen kind - never a cascade.
        ParseResult mixed = VrmlParser.parse("Box { size [1 \"a\"] }\n");
        assertEquals(List.of(Codes.UNEXPECTED_TOKEN), codes(mixed));
        assertEquals("Box { size [1 \"a\"] }\n", mixed.printCst());
        assertTrue(mixed.cstHoles().isEmpty(), mixed.cstHoles().toString());
    }

    @Test
    void rule2DefAndUseShareOneName() {
        ParseResult r = assertClean("DEF box Box {}\nUSE box\n");
        assertEquals(2, of(r, CstKind.NODE_NAME).size());
        assertEquals("box", r.text(of(r, CstKind.NODE_NAME).get(0)));
        assertEquals(1, of(r, CstKind.USE_CLAUSE).size());
    }

    @Test
    void routeStatementKeepsBothEndpointsForDefinitionLookup() {
        ParseResult r = assertClean("DEF a TimeSensor {}\nDEF b PositionInterpolator {}\n"
                + "ROUTE a.fraction_changed TO b.set_fraction\n");
        CstNode route = first(r, CstKind.ROUTE_DECL);
        // ROUTE, name, ., field, TO, name, ., field
        assertEquals(8, route.childCount(), route.children().toString());
        List<CstNode> names = of(r, CstKind.NODE_NAME);
        assertEquals("a", r.text(names.get(2)));
        assertEquals("b", r.text(names.get(3)));
    }

    @Test
    void rule14FieldAssignmentsKeepNameAndValueApart() {
        ParseResult r = assertClean("Material { diffuseColor 1 0 0 emissiveColor 0 0 0 }");
        List<CstNode> assignments = of(r, CstKind.FIELD_ASSIGNMENT);
        assertEquals(2, assignments.size());
        assertEquals("diffuseColor", r.text(assignments.get(0).child(0)));
        assertEquals(CstKind.FIELD_NAME, assignments.get(0).child(0).kind());
    }

    @Test
    void rules4To7ProtoWithInterfaceAndBody() {
        ParseResult r = assertClean("""
                PROTO MyShape [
                  exposedField SFFloat radius 1
                  field MFVec3f points []
                  eventIn SFTime flash
                  eventOut SFColor result
                ] {
                  Info { string "hi" }
                }
                """);
        oneOf(r, CstKind.PROTO_DECL);
        assertEquals("MyShape", r.text(first(r, CstKind.PROTO_NAME)));
        List<CstNode> decls = of(r, CstKind.INTERFACE_DECL);
        assertEquals(4, decls.size());
        // field and exposedField carry a default value; the event declarations do not.
        assertEquals(2, decls.stream().filter(d -> d.childrenOf(CstKind.VALUE).size() > 0).count());
        assertEquals(1, of(r, CstKind.PROTO_BODY).size());
    }

    @Test
    void rules9To11ExternProtoTakesAccessTypeFieldTypeFieldName() {
        ParseResult r = assertClean("EXTERNPROTO simple [\n"
                + "  eventIn SFInt32 setter\n"
                + "  field SFColor constant\n"
                + "]\n[]\n");
        List<CstNode> decls = of(r, CstKind.INTERFACE_DECL);
        assertEquals(2, decls.size());
        for (CstNode decl : decls) {
            assertEquals(3, decl.childCount(), decl.children().toString());
            assertEquals(CstKind.ACCESS_TYPE, decl.child(0).kind());
            assertEquals(CstKind.FIELD_TYPE, decl.child(1).kind());
            assertEquals(CstKind.FIELD_NAME, decl.child(2).kind());
        }
        assertEquals(1, of(r, CstKind.URI_LIST).size());
    }

    @Test
    void rules15To16ScriptBodyMixesDeclarationsAndFields() {
        ParseResult r = assertClean("""
                Script {
                  eventOut SFBool isLoaded
                  eventIn SFTime setTime
                  field MFString url "javascript:\n  function e() { }\n"
                  mustEvaluate TRUE
                }
                """);
        // Declarations are script elements; mustEvaluate is an ordinary field assignment.
        assertEquals(3, of(r, CstKind.SCRIPT_ELEMENT).size());
        assertEquals(1, of(r, CstKind.FIELD_ASSIGNMENT).size());
    }

    @Test
    void rule5ProtoBodyNeedsARootNodeAndRule3RefusesUseThere() {
        // Rule 5: protoStatement* rootNodeStatement statement* - the root node is not optional.
        ParseResult empty = VrmlParser.parse("PROTO P [] { }\n");
        assertTrue(codes(empty).contains(Codes.PROTO_BODY_EMPTY), codes(empty).toString());
        assertEquals("PROTO P [] { }\n", empty.printCst());

        // Rule 3: rootNodeStatement ::= node | DEF nodeName node, so a bare USE cannot build one.
        ParseResult use = VrmlParser.parse("PROTO P [] { USE other }\n");
        assertTrue(codes(use).contains(Codes.STATEMENT_UNFINISHED), codes(use).toString());
        assertEquals(1, of(use, CstKind.USE_CLAUSE).size(), "the USE is still kept in the tree");
        assertEquals("PROTO P [] { USE other }\n", use.printCst());
    }

    @Test
    void rule14IsClauseInsideANodeInstance() {
        ParseResult r = assertClean("PROTO P [ exposedField SFFloat r 1 ] { Sphere {} }\nP { r IS r }\n");
        CstNode is = first(r, CstKind.IS_CLAUSE);
        assertEquals(2, is.childCount(), is.children().toString());
    }

    @Test
    void rule24NullIsAValueNotAnIdentifier() {
        ParseResult r = assertClean("Appearance { texture NULL }");
        assertEquals("NULL", r.text(first(r, CstKind.SF_VALUE)));
    }

    @Test
    void missingToInRouteIsReportedAndTheRestStillParses() {
        String src = "DEF a TimeSensor {}\nDEF b Color {}\nROUTE a.fraction_changed b.input\n"
                + "Shape {}\n";
        ParseResult r = VrmlParser.parse(src);
        assertTrue(r.hasErrors(), codes(r).toString());
        assertTrue(codes(r).contains(Codes.MISSING_KEYWORD), codes(r).toString());
        assertEquals(src, r.printCst());
        // TimeSensor, Color and the trailing Shape: the junk did not swallow the rest.
        assertTrue(of(r, CstKind.NODE_TYPE).size() >= 3, of(r, CstKind.NODE_TYPE).toString());
    }

    @Test
    void unclosedNodePointsAtTheBraceThatNeverClosed() {
        String src = "Box {\n  size 1 1 1\n";
        ParseResult r = VrmlParser.parse(src);
        Issue unclosed = r.issues().stream()
                .filter(i -> i.code() == Codes.UNCLOSED_NODE)
                .findFirst().orElse(null);
        assertNotNull(unclosed, codes(r).toString());
        assertEquals(4, unclosed.start(), "the report belongs on the '{'");
        assertEquals(src, r.printCst());
    }

    @Test
    void unterminatedStringIsReportedWithoutEatingTheDocument() {
        String src = "Inline { url \"missing\nBox {} }\n";
        ParseResult r = VrmlParser.parse(src);
        // The quote never comes back, so the literal ends at the line break and the rest of
        // the file still gets a tree: Box is read as a field name, which is what the grammar
        // says an identifier in a node body is. The last complaint belongs on the stray '}'
        // that this reading leaves over at the end of the file - one honest error, not a sweep.
        assertEquals(List.of(Codes.UNTERMINATED_STRING, Codes.MISSING_VALUE, Codes.MISSING_NAME),
                codes(r), r.issues().toString());
        assertEquals(List.of(), r.cstHoles());
        assertEquals(src, r.printCst());
    }

    @Test
    void oneJunkTokenDoesNotCascadeAcrossTheFile() {
        String src = "Box { size 1 1 1 }\n]]\nShape {}\n";
        ParseResult r = VrmlParser.parse(src);
        assertTrue(r.hasErrors(), codes(r).toString());
        assertTrue(r.issues().size() <= 3, () -> "too many issues: " + r.issues());
        assertTrue(of(r, CstKind.ERROR).size() >= 1, "the stray tokens must be kept in the tree");
        assertTrue(of(r, CstKind.NODE_TYPE).stream().anyMatch(n -> "Shape".equals(r.text(n))),
                "the statement after the junk must still parse");
        assertEquals(src, r.printCst());
    }

    @Test
    void interfaceDeclarationOutsideAProtoOrScriptIsRejected() {
        String src = "Box {\n  field SFInt32 x 1\n}\n";
        ParseResult r = VrmlParser.parse(src);
        assertTrue(codes(r).contains(Codes.DECL_OUTSIDE_PROTO), codes(r).toString());
        assertEquals(src, r.printCst());
    }

    // ---- recovery: one mistake, one report -------------------------------------------

    @Test
    void aStatementThatCannotBecomeAWholeOneIsSweptInOneGo() {
        // The first line of error_handling/import.wrl, minimized. Rule 12 reads IMPORT as a node
        // type, so the rest of the line is fallout; without a sweep each leftover word starts its
        // own statement and one mistake costs five diagnostics.
        String src = "#VRML V2.0 utf8\nIMPORT INLINE.foo AS bar\nShape {}\n";
        ParseResult r = VrmlParser.parse(src);
        assertEquals(List.of(Codes.MISSING_LBRACE), codes(r), r.issues().toString());
        assertTrue(hasNodeType(r, "Shape"),
                "the sweep has to stop at the next statement, not run to the end of the file");
        assertEquals(List.of(), r.cstHoles());
        assertEquals(src, r.printCst());
    }

    @Test
    void falloutResumesAtAKeywordNotAtALineBreak() {
        // VRML has no statement terminator, so "the next line" is not a restart point - a DEF/USE/
        // ROUTE/PROTO keyword or a word followed by '{' is, because only those can open Rule 12.
        String src = "#VRML V2.0 utf8\nBox size 1 1 1\nDEF thing Sphere {}\n";
        ParseResult r = VrmlParser.parse(src);
        assertEquals(1, r.issues().size(), r.issues().toString());
        List<CstNode> statements = of(r, CstKind.NODE_STATEMENT);
        assertEquals(2, statements.size(), statements.toString());
        assertEquals("DEF thing Sphere {}", r.text(statements.get(1)).trim());
        assertEquals(src, r.printCst());
    }

    @Test
    void anIllegalDeclarationSwallowsItsOwnWords() {
        // The mistake is the whole declaration rather than its first keyword: leaving
        // "SFInt32 myIntField" behind lets Rule 14 read them as `field name` + `value` and then
        // invent a second error on the number that follows.
        String src = "Box {\n  exposedField SFInt32 myIntField 20\n}\n";
        ParseResult r = VrmlParser.parse(src);
        assertEquals(List.of(Codes.DECL_OUTSIDE_PROTO), codes(r), r.issues().toString());
        assertEquals(List.of(), of(r, CstKind.FIELD_ASSIGNMENT), "no assignment may be invented");
        // The sweep stops before the '}', so the body still closes where the author meant.
        assertTrue(first(r, CstKind.NODE_BODY).end() < src.indexOf('}'), r.root().toString());
        assertEquals(src, r.printCst());

        // Inside a Script the same sweep leaves the following legal field intact.
        ParseResult script = VrmlParser.parse("Script {\n  exposedField SFInt32 x 20\n"
                + "  url \"javascript:\"\n}\n");
        assertEquals(List.of(Codes.DECL_OUTSIDE_PROTO), codes(script), script.issues().toString());
        assertEquals("url \"javascript:\"",
                script.text(first(script, CstKind.FIELD_ASSIGNMENT)).trim());
        assertEquals(List.of(), script.cstHoles());
    }

    @Test
    void aBrokenElementInsideANodeBodyIsSweptLikeABrokenStatement() {
        // The same cascade the scene level had, one level down: Xj3D's IMPORT statement written
        // inside a node body used to cost one error per word it could not place.
        String src = "Box {\n  size 1 1\nIMPORT INLINE.foo AS bar\n}\nShape {}\n";
        ParseResult r = VrmlParser.parse(src);
        assertEquals(List.of(Codes.MISSING_LBRACE), codes(r), r.issues().toString());
        assertTrue(hasNodeType(r, "Shape"), "the sweep must not run past the node's own '}'");
        assertEquals(List.of(), r.cstHoles());
        assertEquals(src, r.printCst());
    }

    private static boolean hasNodeType(ParseResult r, String name) {
        return of(r, CstKind.NODE_TYPE).stream().anyMatch(n -> name.equals(r.text(n)));
    }

    @Test
    void nodeAtResolvesTheInnermostConstructUnderACursor() {
        String src = "Transform { translation 1 2 3 }";
        ParseResult r = assertClean(src);
        int insideName = src.indexOf("translation") + 3;
        CstNode inner = r.root().nodeAt(insideName);
        assertEquals(CstKind.FIELD_NAME, inner.kind(), inner.toString());
        List<CstNode> path = new ArrayList<>();
        r.root().pathTo(insideName, path);
        assertEquals(CstKind.SCENE, path.get(0).kind());
        assertTrue(path.stream().anyMatch(n -> n.kind() == CstKind.FIELD_ASSIGNMENT), path.toString());
        assertTrue(path.stream().anyMatch(n -> n.kind() == CstKind.NODE_BODY), path.toString());
    }

    @Test
    void anEmptyBodyDoesNotStealItsAncestorsPositions() {
        // `Sphere {}` is the most common construct in the corpus, and its NODE_BODY has no token
        // of its own. Folding that into the parent moved the parent's start to the closing brace,
        // which made every node with an empty body invisible to a cursor lookup.
        String src = "Sphere {}\nBox { size 1 1 1 }\n";
        ParseResult r = assertClean(src);
        CstNode root = r.root();
        assertEquals(0, root.start(), root.toString());
        assertTrue(root.contains(0), "the scene has to cover its first character");
        assertEquals(CstKind.NODE_TYPE, root.nodeAt(2).kind(),
                "a cursor on 'Sphere' belongs on the node type");
        List<CstNode> path = new ArrayList<>();
        root.pathTo(2, path);
        assertEquals(CstKind.SCENE, path.get(0).kind(), path.toString());
        assertTrue(path.stream().anyMatch(n -> n.kind() == CstKind.NODE), path.toString());
    }

    @Test
    void positionsPastTheEndClampInsteadOfFailing() {
        String src = "Box {}";
        ParseResult r = assertClean(src);
        assertEquals(0, r.tokenAt(0).start);
        assertNotNull(r.tokenAt(src.length() + 10));
        assertNotNull(r.root().nodeAt(src.length() + 10));
    }

    private static void oneOf(ParseResult r, CstKind kind) {
        assertEquals(1, of(r, kind).size(), "exactly one " + kind + " expected");
    }
}
