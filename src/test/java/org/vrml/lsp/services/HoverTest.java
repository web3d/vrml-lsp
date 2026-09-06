package org.vrml.lsp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.vrml.lsp.diagnostics.DocumentAnalyzer;
import org.vrml.lsp.spec.Specs;

/**
 * What a hover says about the token the pointer stands on.
 *
 * <p>Each case is one judgement about the language - what a node name is worth next to a field name,
 * which end of a {@code ROUTE} a name belongs to, what an interface line declares that no table row
 * does - phrased as the sentence the user reads rather than as a tree walk, so that a wording which
 * drifts shows up as one failing case naming itself.
 *
 * <p>The caret is written one character <em>into</em> the word the pointer is on. Two words with
 * nothing between them share the position at their boundary, and a pointer there asks about the left
 * one; a test that means to read a particular word should not leave that to chance.
 */
class HoverTest {

    private static final char CARET = '^';
    private static final String HEADER = "#VRML V2.0 utf8\n";

    /** One pointer's answer: the text, and the word it is about. */
    private record Said(String markdown, String word) {

        /** Not every token has anything to say about itself, and silence is an answer. */
        static final Said NOTHING = new Said("", "");

        /** The first line, which is the claim the rest of the tooltip is built on. */
        String title() {
            int newline = markdown.indexOf('\n');
            return newline < 0 ? markdown : markdown.substring(0, newline);
        }

        void says(String... phrases) {
            for (String phrase : phrases) {
                assertTrue(markdown.contains(phrase),
                        "nothing like \"" + phrase + "\" was said; the tooltip was\n" + markdown);
            }
        }

        void neverSays(String phrase) {
            assertTrue(!markdown.contains(phrase), "\"" + phrase + "\" should not be said about\n"
                    + markdown);
        }

        /** The span the client highlights, which is the word and not the sentence around it. */
        void underlines(String expected) {
            assertEquals(expected, word, "the wrong stretch of text is being explained");
        }
    }

    private static Said say(String document) {
        int caret = document.indexOf(CARET);
        assertTrue(caret >= 0, "the case has to mark where the pointer is: " + document);
        assertEquals(-1, document.indexOf(CARET, caret + 1), "one pointer per case");
        String text = document.substring(0, caret) + document.substring(caret + 1);
        DocumentAnalyzer.Analysis analysis = DocumentAnalyzer.analyze(text);
        Hovers.Hovered hovered = Hovers.of(analysis.parse(), analysis.symbols(), Specs.standard(),
                caret);
        return hovered == null ? Said.NOTHING
                : new Said(hovered.markdown(),
                        text.substring(hovered.start(), hovered.end()));
    }

    /** A scene with the two ends of a route in it, which several cases point the caret into. */
    private static String routed(String withCaretIn) {
        return HEADER + "DEF io PositionInterpolator {\n}\nDEF mover Transform {\n}\n"
                + "ROUTE " + withCaretIn + "\n";
    }

    // ---- nodes -------------------------------------------------------------------------------

    /** A node type is worth the component it belongs to, the prose and the names its body takes. */
    @Test
    void aNodeTypeNamesItsComponentAndWhatMayBeWrittenInIt() {
        Said said = say(HEADER + "B^ox {\n  size 1 1 1\n}\n");
        assertEquals("**`Box`** · Geometry, level 1", said.title());
        said.underlines("Box");
        said.says("Box is a geometry node specifying a rectangular cuboid.",
                "may be given a value for: `size` `solid`", "specification:");
    }

    /** The one node whose type is a keyword rather than a name still reads as a node. */
    @Test
    void scriptIsAVRML97NodeEvenThoughItsTypeIsAKeyword() {
        say(HEADER + "S^cript {\n  url \"\"\n}\n").says("**`Script`** · Scripting, level 1",
                "may be given a value for: `url` `mustEvaluate` `directOutput`");
    }

    /** An unknown type has no row to quote, and a hover does not invent one. */
    @Test
    void aNameTheTableDoesNotKnowSaysNothingUntilAProtoExplainsIt() {
        assertEquals(Said.NOTHING, say(HEADER + "W^idget {\n}\n"),
                "nothing in this file or the table says what a Widget is");
        say(HEADER + "PROTO Widget [] {\n  Box {}\n}\nW^idget {\n}\n")
                .says("**`Widget`** · PROTO used here");
    }

    // ---- members -----------------------------------------------------------------------------

    /** A field says its type, its access, and what it holds when the file writes nothing. */
    @Test
    void aFieldNameIsItsWholeRowFromTheTable() {
        Said said = say(HEADER + "Box {\n  si^ze 1 1 1\n}\n");
        assertEquals("**`SFVec3f size (field)`** · `Box`", said.title());
        said.underlines("size");
        said.says("default: `2 2 2`");
        // A plain field is written once and cannot be routed to, so promising a `set_` name for it
        // would be the wrong judgement about the language.
        said.neverSays("routed as");
    }

    /** An exposedField is the one name a route can both drive and read, through two events. */
    @Test
    void anExposedFieldSaysTheTwoEventsItIsRoutedThrough() {
        say(HEADER + "Switch {\n  wh^ichChoice 0\n}\n")
                .says("**`SFInt32 whichChoice (exposedField)`** · `Switch`",
                        "default: `-1`",
                        "routed as: `set_whichChoice` in, `whichChoice_changed` out");
    }

    /** The value a field is given explains itself through the field, since that is its type. */
    @Test
    void hoveringAValueHoversTheFieldItIsTheValueOf() {
        say(HEADER + "Box {\n  size 1 1 ^1\n}\n")
                .says("**`SFVec3f size (field)`** · `Box`, the value this field is given",
                        "default: `2 2 2`");
    }

    /** A name no node accepts is marked in the editor already; the tooltip says the same thing. */
    @Test
    void aFieldNameThatIsNoFieldOfItsNodeSaysSoRatherThanNothing() {
        say(HEADER + "Box {\n  co^lor 1 0 0\n}\n").says("**`color`** · of `Box`",
                "`Box` has no field named this, so nothing is written by this line.");
    }

    /** An alias is the same field under another spelling, and the row shown names both. */
    @Test
    void aFieldsOtherSpellingIsDescribedAsTheFieldItIs() {
        say(HEADER + "Switch {\n  ch^oice 0\n}\n")
                .says("**`MFNode children (exposedField)`** · `Switch`",
                        "written as: `choice`, which is the name this field also answers to");
    }

    // ---- names and routes --------------------------------------------------------------------

    /** A {@code DEF} name is the node it holds, and what the file goes on to do with it. */
    @Test
    void aDefinitionSaysWhatItHoldsAndWhoReadsIt() {
        Said said = say(HEADER + "DEF s^un PointLight {\n}\nUSE sun\n");
        assertEquals("**`sun`** · `PointLight`, DEF'd here", said.title());
        said.underlines("sun");
        said.says("used by: 1 `USE`");
        said.neverSays("0 ");
    }

    /** A name nothing mentions is the one count worth spelling out, since it means "free to rename". */
    @Test
    void aNameNothingMentionsIsWorthSayingOutLoud() {
        Said said = say(HEADER + "DEF u^nused PointLight {\n}\n");
        said.says("used by: nothing in this file");
        said.neverSays("0 `USE`");
    }

    /** A {@code USE} answers with the node it re-reads, not with itself. */
    @Test
    void aUseReadsAsTheDefinitionItPointsAt() {
        Said said = say(HEADER + "DEF sun PointLight {\n}\nUSE su^n\n");
        assertEquals("**`sun`** · `PointLight`, used here", said.title());
        said.underlines("sun");
    }

    /** A {@code USE} of a name nothing defined is the error the editor already marked. */
    @Test
    void aUseOfAnUndefinedNameSaysSo() {
        say(HEADER + "USE n^othing\n").says("**`nothing`** · USE",
                "Nothing `DEF`s this name, so the browser has no node to put here.");
    }

    /** Each end of a route says which end it is and which node it is the end of. */
    @Test
    void theTwoEndsOfARouteSayWhichEndTheyAre() {
        say(routed("i^o.value_changed TO mover.set_translation"))
                .says("**`io`** · ROUTE source of `io`", "the node is: `PositionInterpolator`");
        say(routed("io.value^_changed TO mover.set_translation"))
                .says("**`value_changed`** · ROUTE source of `io`", "the node is: `PositionInterpolator`",
                        "carries: SFVec3f (eventOut)");
        say(routed("io.value_changed TO ^mover.set_translation"))
                .says("**`mover`** · ROUTE sink of `mover`", "the node is: `Transform`");
        say(routed("io.value_changed TO mover.set_translat^ion"))
                .says("**`set_translation`** · ROUTE sink of `mover`", "the node is: `Transform`");
    }

    /** The sink's name is no name the table carries, so its answer comes from the field under it. */
    @Test
    void aDerivedRouteNameIsExplainedByTheFieldItWasMadeFrom() {
        say(routed("io.value_changed TO mover.set_translat^ion"))
                .says("carries: SFVec3f (exposedField)", "Position (x, y, z in meters)");
    }

    /** A route wired to a node that was never defined goes nowhere, and says that instead. */
    @Test
    void aRouteEndOnAnUndefinedNodeGoesNowhere() {
        say(HEADER + "ROUTE g^ho.isActive TO mover.set_translation\n")
                .says("**`gho`** · ROUTE source of `gho`",
                        "No node named `gho` is `DEF`'d in this scope, so the route goes nowhere.");
    }

    // ---- prototypes --------------------------------------------------------------------------

    /** An {@code EXTERNPROTO} is a promise about another file, which is the one thing to say. */
    @Test
    void anExternProtoSaysWhereItsBehaviourComesFrom() {
        Said said = say(HEADER + "EXTERNPROTO W^heel [ exposedField SFVec3f offset ] \"w.wrl\"\n");
        assertEquals("**`Wheel`** · EXTERNPROTO declared here", said.title());
        said.underlines("Wheel");
        said.says("Its implementation is one of the files listed after the interface",
                "exposedField SFVec3f `offset`");
    }

    /** An interface line is a member of the type being declared, not of any node in the table. */
    @Test
    void anInterfaceLineIsTheMemberItDeclares() {
        String proto = HEADER + "PROTO Lamp [\n  exposedField SFFloat i^ntensity 0.8\n] {\n}\n";
        Said said = say(proto);
        assertEquals("**`SFFloat intensity (exposedField)`** · `Lamp`, declared here", said.title());
        said.says("default: `0.8`", "routed as: `set_intensity` in, `intensity_changed` out");
    }

    /** A {@code PROTO} with no interface cannot be given anything, which is worth saying. */
    @Test
    void anEmptyInterfaceSaysTheInstanceTakesNothing() {
        say(HEADER + "PROTO W^idget [] {\n  Box {}\n}\n")
                .says("PROTO declared here", "It declares no interface, so nothing may be written"
                        + " inside an instance of it.");
    }

    /** The right side of an {@code IS} names the enclosing prototype's member, not the node's. */
    @Test
    void anIsBindingPointsAtTheInterfaceItConnectsTo() {
        String proto = HEADER + "PROTO Lamp [\n  exposedField SFFloat intensity 0.8\n] {\n"
                + "  PointLight {\n    intensity IS i^ntensity\n  }\n}\n";
        say(proto).says("**`SFFloat intensity (exposedField)`** · `Lamp`, bound by `IS` to"
                + " `intensity` in the body");
    }

    /** The left side of an {@code IS} is still the built-in node's own field. */
    @Test
    void theNodeSideOfAnIsStaysTheNodesOwnField() {
        String proto = HEADER + "PROTO Lamp [\n  exposedField SFFloat intensity 0.8\n] {\n"
                + "  PointLight {\n    i^ntensity IS intensity\n  }\n}\n";
        say(proto).says("**`SFFloat intensity (exposedField)`** · `PointLight`",
                "Brightness of direct emission from the light.");
    }

    /** A node inside a {@code PROTO} body is still the node it names, table and all. */
    @Test
    void aNodeInsideAProtoBodyIsStillTheNodeItNames() {
        String proto = HEADER + "PROTO Lamp [\n  exposedField SFFloat intensity 0.8\n] {\n"
                + "  P^ointLight {\n  }\n}\n";
        say(proto).says("**`PointLight`** · Lighting, level 1");
    }

    // ---- types and silence -------------------------------------------------------------------

    /** A field type says how many numbers one of its values takes, which is what trips authors. */
    @Test
    void aFieldTypeSaysHowManyNumbersItTakes() {
        Said single = say(HEADER + "PROTO P [ field SF^Vec3f v 0 0 0 ] { }\n");
        assertEquals("**`SFVec3f`** · numbers", single.title());
        single.says("One value is 3 numbers.", "A field of this type holds exactly one.",
                "its multiple: `MFVec3f`");
        say(HEADER + "PROTO P [ field M^FColor c 0 0 0 ] { }\n")
                .says("A field of this type holds any number of them, between `[ ]`.",
                        "its single value: `SFColor`");
    }

    /** The four access words differ in what a route and a body may do, which is the question. */
    @Test
    void anAccessKeywordSaysWhatItLetsAnyoneDo() {
        say(HEADER + "PROTO P [ expo^sedField SFTime t 0 ] { }\n")
                .says("readable and writable: a PROTO exposes a node's exposedField through an"
                        + " exposedField of the same type.");
        say(HEADER + "PROTO P [ fi^eld SFTime t 0 ] { }\n")
                .says("initializeOnly: written once when the file is read, and never again.");
        say(HEADER + "PROTO P [ ev^entIn SFTime t ] { }\n")
                .says("what a ROUTE may send into this interface.");
        say(HEADER + "PROTO P [ eve^ntOut SFTime t ] { }\n")
                .says("what this interface sends out, and what a ROUTE may read.");
    }

    /**
     * Positions with nothing to explain stay silent: a brace, a keyword that only opens a statement,
     * whitespace, and the punctuation between two names.
     */
    @Test
    void aPointerOnAnythingWithoutANameSaysNothing() {
        for (String document : List.of(HEADER + "Box ^{ size 1 1 1 }\n", HEADER + "Box {} ^\n",
                HEADER + "Box {\n^  size 1 1 1\n}\n", HEADER + "D^EF sun PointLight {}\n",
                HEADER + "U^SE sun\n", HEADER + "RO^UTE a.b TO c.d\n",
                HEADER + "PROTO Widget [] ^{\n}\n")) {
            assertEquals(Said.NOTHING, say(document), "nothing to say about\n" + document);
        }
    }

    /** A definition's own keyword is not a name, and the name is not the keyword. */
    @Test
    void aNameIsOnlyItselfAtTheOnePositionThatStandsForIt() {
        say(HEADER + "DEF s^un PointLight {}\n").underlines("sun");
        say(HEADER + "DEF sun P^ointLight {}\n").underlines("PointLight");
        // A cursor typed just past the last letter of a name still means that name, which is why a
        // hover and a completion at the same keystroke cannot disagree about what is being written.
        say(HEADER + "DEF sun^ PointLight {}\n").underlines("sun");
    }
}
