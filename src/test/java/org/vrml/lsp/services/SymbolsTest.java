package org.vrml.lsp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.vrml.lsp.diagnostics.DocumentAnalyzer;
import org.vrml.lsp.services.Symbols.Kind;
import org.vrml.lsp.spec.Specs;

/**
 * The outline a file of VRML97 gives a symbol tree: node instances by their {@code DEF} name, the
 * fields they are given, prototypes with their interface, and the routes between them.
 *
 * <p>The whole tree of a small scene is asserted as text rather than one symbol at a time, because
 * what an outline is worth is its <em>shape</em>: a field that floats to the top level, a node that
 * swallows its own children, or a route that vanishes are all shape errors, and each one shows as a
 * line in the wrong place of one readable picture.
 */
class SymbolsTest {

    private static final String HEADER = "#VRML V2.0 utf8\n";

    /** The outline as the picture it is: one indented line per symbol, kind, name, detail. */
    private record Outlined(String text, List<Symbols.Symbol> roots, String source) {

        /** A name is only worth clicking if it is a range of the text, not a phrase about it. */
        void everyNameIsARealRange() {
            check(roots);
        }

        private void check(List<Symbols.Symbol> symbols) {
            for (Symbols.Symbol symbol : symbols) {
                assertTrue(symbol.start() <= symbol.nameStart()
                                && symbol.nameEnd() <= symbol.end(),
                        symbol.name() + " has a name outside its own span");
                // A route's name is the pair of ends it wires, which is no one token in the text;
                // every other symbol's name is a word the file itself wrote.
                if (symbol.kind() != Kind.ROUTE) {
                    assertEquals(symbol.name(),
                            source.substring(symbol.nameStart(), symbol.nameEnd()),
                            symbol.kind() + " " + symbol.name() + " points at the wrong text");
                }
                check(symbol.children());
            }
        }
    }

    private static Outlined outlineOf(String text) {
        DocumentAnalyzer.Analysis analysis = DocumentAnalyzer.analyze(text);
        List<Symbols.Symbol> roots = Symbols.outline(analysis.parse(), analysis.symbols(),
                Specs.standard());
        StringBuilder drawn = new StringBuilder();
        for (Symbols.Symbol root : roots) {
            draw(root, 0, drawn);
        }
        return new Outlined(drawn.toString(), roots, text);
    }

    private static void draw(Symbols.Symbol symbol, int depth, StringBuilder out) {
        out.append("  ".repeat(depth)).append(symbol.kind()).append(' ').append(symbol.name());
        if (!symbol.detail().isEmpty()) {
            out.append(" : ").append(symbol.detail());
        }
        out.append('\n');
        for (Symbols.Symbol child : symbol.children()) {
            draw(child, depth + 1, out);
        }
    }

    /** A {@code DEF} names the node, the type says what it is, and its fields sit under it. */
    @Test
    void aDefinitionHoldsItsOwnFields() {
        assertEquals("NODE spin : Transform\n"
                + "  FIELD rotation : SFRotation\n",
                outlineOf(HEADER + "DEF spin Transform {\n  rotation 0 0 1 0\n}\n").text);
    }

    /** Nesting is the scene's, all the way down, and each field keeps the type the table gives it. */
    @Test
    void aSceneKeepsItsShapeAllTheWayDown() {
        assertEquals("NODE Shape\n"
                + "  FIELD geometry : SFNode\n"
                + "    NODE Box\n"
                + "      FIELD size : SFVec3f\n"
                + "  FIELD appearance : SFNode\n"
                + "    NODE Appearance\n"
                + "      FIELD material : SFNode\n"
                + "        NODE Material\n"
                + "          FIELD diffuseColor : SFColor\n",
                outlineOf(HEADER + "Shape {\n  geometry Box {\n    size 1 1 1\n  }\n"
                        + "  appearance Appearance {\n    material Material {\n"
                        + "      diffuseColor 1 0 0\n    }\n  }\n}\n").text);
    }

    /** A node with no {@code DEF} is still a node, and a {@code USE} is not a second one. */
    @Test
    void anUnnamedNodeIsListedByItsTypeAndAUseIsNotListedAtAll() {
        Outlined outlined = outlineOf(HEADER + "DEF a Box {}\nUSE a\nBox {}\n");
        assertEquals(2, outlined.roots.size(),
                "the `USE` re-reads a node the outline already shows, so it is no second symbol");
        assertEquals("NODE a : Box\n"
                + "NODE Box\n", outlined.text,
                "an unnamed node is listed by the only name the file gives it");
    }

    /** The nodes an {@code MFNode} field holds belong to that field, not to the file. */
    @Test
    void aListOfChildrenHangsOffTheFieldThatHoldsIt() {
        assertEquals("NODE g : Group\n"
                + "  FIELD children : MFNode\n"
                + "    NODE Box\n"
                + "    NODE inner : Transform\n"
                + "      FIELD translation : SFVec3f\n",
                outlineOf(HEADER + "DEF g Group {\n  children [\n    Box {}\n"
                        + "    DEF inner Transform {\n      translation 1 2 3\n    }\n  ]\n}\n").text);
    }

    /** A prototype is its interface plus the node it is written as, and the two do not mix. */
    @Test
    void aPrototypeShowsItsInterfaceAndItsBody() {
        assertEquals("PROTO Lamp\n"
                + "  INTERFACE intensity : exposedField SFFloat\n"
                + "  INTERFACE content : field SFNode\n"
                + "  NODE PointLight\n"
                + "    FIELD intensity : IS intensity\n"
                + "NODE Lamp\n"
                + "  FIELD intensity : SFFloat\n",
                outlineOf(HEADER + "PROTO Lamp [\n  exposedField SFFloat intensity 0.8\n"
                        + "  field SFNode content NULL\n] {\n  PointLight {\n"
                        + "    intensity IS intensity\n  }\n}\nLamp {\n  intensity 0.4\n}\n").text);
    }

    /** An {@code EXTERNPROTO} has the interface and nothing else - its body is in another file. */
    @Test
    void anExternProtoListsOnlyWhatItPromises() {
        assertEquals("EXTERN_PROTO Wheel\n"
                + "  INTERFACE offset : exposedField SFVec3f\n"
                + "  INTERFACE content : field SFNode\n"
                + "  INTERFACE tick : eventIn SFTime\n",
                outlineOf(HEADER + "EXTERNPROTO Wheel [\n  exposedField SFVec3f offset\n"
                        + "  field SFNode content\n  eventIn SFTime tick\n] \"w.wrl\"\n").text);
    }

    /** A {@code Script} declares its own interface in its body, and those are its members. */
    @Test
    void aScriptsOwnDeclarationsAreItsFields() {
        assertEquals("NODE body : Script\n"
                + "  FIELD k : field SFVec3f\n"
                + "  FIELD go : eventIn SFBool\n"
                + "  FIELD done : eventOut SFTime\n"
                + "  FIELD url : MFString\n",
                outlineOf(HEADER + "DEF body Script {\n  field SFVec3f k 0 0 0\n"
                        + "  eventIn SFBool go\n  eventOut SFTime done\n"
                        + "  url \"javascript: print(k.x)\"\n}\n").text);
    }

    /** A route is a line of its own, named by the two ends it wires. */
    @Test
    void aRouteIsListedByWhatItConnects() {
        Outlined outlined = outlineOf(HEADER + "DEF a TimeSensor {}\nDEF b Transform {}\n"
                + "ROUTE a.fraction_changed TO b.set_translation\n");
        assertEquals("NODE a : TimeSensor\n"
                + "NODE b : Transform\n"
                + "ROUTE a.fraction_changed → b.set_translation\n", outlined.text);
        assertEquals(Kind.ROUTE, outlined.roots.get(2).kind(),
                "a route wants the icon of an event, not of a value");
    }

    /** A half-written route still gets a line: the outline is where an author finds the mistake. */
    @Test
    void aRouteTheFileNeverFinishedIsStillListed() {
        Outlined outlined = outlineOf(HEADER + "DEF a TimeSensor {}\nDEF b Transform {}\n"
                + "ROUTE nope TO b.set_translation\n");
        assertEquals("NODE a : TimeSensor\n"
                + "NODE b : Transform\n"
                + "ROUTE ROUTE\n", outlined.text, "the ends cannot be named, so the line says ROUTE");
        assertEquals(Kind.ROUTE, outlined.roots.get(2).kind());
    }

    /** An empty node and an empty file both have an outline of nothing, and neither is an error. */
    @Test
    void aFileWithNothingInItHasNothingToOutline() {
        assertEquals("", outlineOf(HEADER).text);
        assertEquals("", outlineOf(HEADER + "\n\n").text);
        assertEquals("NODE s : SpotLight\n", outlineOf(HEADER + "DEF s SpotLight {\n}\n").text,
                "a node with no fields is still a node");
    }

    /** A broken file has an outline too - the point of the view is to show what is there. */
    @Test
    void aFileWithErrorsStillOutlinesWhatSurvived() {
        Outlined outlined = outlineOf(HEADER + "DEF a Box {\n  size 1 1\n}\n" + "Def b Box {}\n"
                + "ROUTE a TO\n");
        // `Def` is no keyword, so the recovery reads it as the node the author meant and the
        // statement stays in the outline where the author can find it. A route with no ends left to
        // name is still a route line.
        assertEquals("NODE a : Box\n"
                + "  FIELD size : SFVec3f\n"
                + "NODE Def\n"
                + "NODE Box\n"
                + "ROUTE ROUTE\n", outlined.text);
    }

    /** Every name the client is given is a range of the text, or clicking it goes nowhere. */
    @Test
    void everyLinePointsAtTheTextItCameFrom() {
        outlineOf(HEADER + "PROTO Lamp [\n  exposedField SFFloat intensity 0.8\n] {\n"
                + "  PointLight {\n    intensity IS intensity\n    location 0 1 0\n  }\n}\n"
                + "DEF spin Transform {\n  rotation 0 1 0 1.57\n}\n"
                + "Lamp {\n  intensity 0.3\n}\nROUTE spin.rotation_changed TO Lamp.set_intensity\n")
                .everyNameIsARealRange();
        outlineOf(HEADER + "DEF g Group {\n  children [\n    Box {}\n    USE missing\n  ]\n}\n")
                .everyNameIsARealRange();
    }
}
