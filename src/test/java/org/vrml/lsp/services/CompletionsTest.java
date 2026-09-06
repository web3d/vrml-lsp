package org.vrml.lsp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.vrml.lsp.diagnostics.DocumentAnalyzer;
import org.vrml.lsp.semantic.Semantics;
import org.vrml.lsp.services.Context.Kind;
import org.vrml.lsp.spec.Specs;

/**
 * What may be written where the cursor stands, on the positions a VRML97 file actually puts one.
 *
 * <p>{@link ContextTest} pins where the caret is; this pins what follows from that. Each case is one
 * judgement about the language - which names a node takes, which value a field wants, which end of a
 * node a {@code ROUTE} may drive - so that a change in a list says which judgement moved rather than
 * than that some count changed.
 *
 * <p>The field lists are quoted exactly, and they are the table's: the names a node accepts are
 * Xj3D's own declarations, which for a grouping node include the fields X3D added and Xj3D shares
 * between the two languages. {@code SpecTest} pins where the table comes from; repeating its
 * VRML97-only subset here would let the two drift apart without either saying so.
 *
 * <p>Order is asserted wherever it is the point. A completion list is read top-down, so "first" is a
 * claim about what an author most often means, and {@code sortText} in the encoder is what carries
 * that claim to the client.
 */
class CompletionsTest {

    private static final char CARET = '^';
    private static final String HEADER = "#VRML V2.0 utf8\n";

    /** The files "next to" the document, which only a caller can know; see {@code CompletionEncoder}. */
    private static final List<String> NEARBY = List.of("chair.wrl.gz", "protos.wrl");

    /** One caret's answer, with just enough vocabulary to say what is in it and in what order. */
    private record Asked(Context context, List<Completions.Candidate> candidates) {

        List<String> labels() {
            return candidates.stream().map(Completions.Candidate::label).toList();
        }

        /** The candidate named {@code label}, which has to be there to be looked at. */
        Completions.Candidate find(String label) {
            return candidates.stream().filter(candidate -> candidate.label().equals(label))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "nothing named " + label + " was offered; the list was " + labels()));
        }

        String insert(String label) {
            return find(label).insertText();
        }

        int at(String label) {
            return labels().indexOf(label);
        }

        /** The list exactly, in order - for the positions where the order is the whole judgement. */
        void isExactly(String... expected) {
            assertEquals(List.of(expected), labels(), "wrong list for\n" + shown());
        }

        void offers(String... names) {
            for (String name : names) {
                assertTrue(at(name) >= 0, "no " + name + " among " + labels() + "\n" + shown());
            }
        }

        void offersNothing(String... names) {
            for (String name : names) {
                assertTrue(at(name) < 0, name + " should not be offered for\n" + shown());
            }
        }

        /** Whether {@code first} is ahead of {@code second}, which is a claim about what is meant. */
        void offersBefore(String first, String second) {
            int atFirst = at(first);
            int atSecond = at(second);
            assertTrue(atFirst >= 0 && atSecond >= 0, "both names have to be offered: " + labels());
            assertTrue(atFirst < atSecond, first + " should come before " + second + " in\n"
                    + labels());
        }

        private String shown() {
            return "<document with the caret at " + context.replaceStart() + ">";
        }
    }

    private static Asked ask(String document) {
        return ask(document, true);
    }

    /**
     * The candidates for one caret, in snippet mode unless the client never agreed to it.
     *
     * <p>The {@code ^} is taken out before the text is parsed, so what is analysed is a file and not
     * a file carrying a character the lexer would have to make sense of.
     */
    private static Asked ask(String document, boolean snippets) {
        int caret = document.indexOf(CARET);
        assertTrue(caret >= 0, "the case has to mark where the cursor is: " + document);
        assertEquals(-1, document.indexOf(CARET, caret + 1), "one cursor per case");
        String text = document.substring(0, caret) + document.substring(caret + 1);
        DocumentAnalyzer.Analysis analysis = DocumentAnalyzer.analyze(text);
        Context ctx = Context.of(analysis.parse(), analysis.symbols(), caret);
        return new Asked(ctx, Completions.of(analysis.parse(), analysis.symbols(), Specs.standard(),
                ctx, snippets, NEARBY));
    }

    private static List<String> labels(String document) {
        return ask(document).labels();
    }

    // ---- node names --------------------------------------------------------------------------

    /**
     * Twelve places a node statement may begin, none of which may come back empty-handed: the root
     * of a file, before and after other statements, after {@code DEF}, at either end of a comment,
     * on a line of its own, and inside a {@code PROTO} body.
     */
    @Test
    void everyStatementPositionOffersTheWholeNodeTable() {
        for (String document : List.of(
                String.valueOf(CARET),
                HEADER + CARET,
                HEADER + "Box {}\n" + CARET,
                HEADER + "WorldInfo { title \"t\" }\n# a line of its own\n" + CARET + "\n",
                HEADER + "DEF portal " + CARET,
                HEADER + "DEF portal Anchor { description \"d\" }\n" + CARET,
                HEADER + "Inline { url \"a.wrl\" }\n" + CARET,
                HEADER + "Shape { geometry Box { size 1 1 1 } }\n" + CARET,
                HEADER + "Transform { rotation 0 0 1 0 }\n\n" + CARET + "\n",
                HEADER + "Background { }\n" + CARET,
                "PROTO Widget [] { " + CARET,
                "PROTO Widget [ field SFNode content ] {\n\t" + CARET + "\n}\n")) {
            Asked asked = ask(document);
            assertEquals(Kind.NODE_STATEMENT, asked.context().kind(), document.replace('\n', ' '));
            asked.offers("Box", "Shape", "Transform", "TimeSensor", "Script", "WorldInfo",
                    "IndexedFaceSet", "MovieTexture", "FontStyle", "Appearance");
        }
    }

    @Test
    void aStatementOffersTheNameAloneBecauseADefMayStillBeComing() {
        Asked asked = ask(HEADER + "Bo" + CARET);
        assertEquals("Bo", asked.context().prefix(), "the author's own keystrokes, not ours");
        assertEquals("Box", asked.insert("Box"), "braces at a statement position would eat a DEF");
        assertTrue(!asked.find("Box").snippet());
        assertEquals(Completions.Kind.NODE, asked.find("Box").kind());
    }

    /** The eleven nodes a grouping node cannot hold are still names the file may be writing. */
    @Test
    void theNodesThatCannotBeChildrenComeAfterTheOnesThatCan() {
        Asked asked = ask(HEADER + CARET);
        asked.offersBefore("Group", "Appearance");
        asked.offersBefore("Shape", "Material");
        asked.offersBefore("Transform", "Coordinate");
        asked.offersBefore("LOD", "FontStyle");
        asked.offersBefore("Billboard", "TextureTransform");
        asked.offersBefore("WorldInfo", "Normal");
        int displayable = Semantics.sceneChildren(Specs.standard()).size();
        List<String> first = ask(HEADER + CARET).labels().subList(0, displayable);
        for (String name : first) {
            assertTrue(Semantics.sceneChildren(Specs.standard()).contains(name),
                    name + " is not something a group may hold, so it should not lead");
        }
        assertEquals(56, asked.candidates().size(), "every node in the table is said once");
    }

    @Test
    void aPrototypeJoinsTheListWhereItIsVisible() {
        Asked asked = ask(HEADER + "PROTO Widget [] { Box {} }\n" + CARET);
        asked.offers("Widget");
        assertEquals("PROTO", asked.find("Widget").detail());
        assertTrue(asked.find("Widget").documentation().contains("A PROTO declared in this file"));
        asked.offersBefore("Group", "Widget");
        asked.offersBefore("Widget", "Appearance");
        assertEquals("Widget", asked.insert("Widget"), "still no braces at a statement position");

        Asked external = ask(HEADER + "EXTERNPROTO Wheel [ field MFString url ] [ \"wheel.wrl\" ]\n"
                + CARET);
        assertEquals("EXTERNPROTO", external.find("Wheel").detail());
        assertTrue(external.find("Wheel").documentation().contains("An EXTERNPROTO"),
                "the doc has to say where the implementation is");

        assertTrue(!ask(HEADER + CARET).labels().contains("Widget"),
                "a file that declares nothing has no instances to offer");
    }

    // ---- field names -------------------------------------------------------------------------

    /**
     * The body of fourteen nodes, each listed exactly. These are the names VRL2002 accepts and the
     * ones an author is choosing between, so they are the same list read from both sides.
     */
    @Test
    void aNodeBodyOffersItsOwnFieldNamesAndNothingElse() {
        assertEquals(List.of("appearance", "geometry", "bboxCenter", "bboxSize", "bboxDisplay",
                "visible", "castShadow"), labels(HEADER + "Shape { " + CARET));
        assertEquals(List.of("bottomRadius", "height", "bottom", "side", "solid"),
                labels(HEADER + "Cone { " + CARET));
        assertEquals(List.of("info", "title"), labels(HEADER + "WorldInfo { " + CARET));
        assertEquals(List.of("children", "bboxCenter", "bboxSize", "bboxDisplay", "whichChoice"),
                labels(HEADER + "Switch { " + CARET));
        assertEquals(List.of("description", "loop", "pitch", "startTime", "pauseTime", "resumeTime",
                "stopTime", "url"), labels(HEADER + "AudioClip { " + CARET));
        assertEquals(List.of("enabled", "autoOffset", "offset", "description"),
                labels(HEADER + "SphereSensor { " + CARET));
        assertEquals(List.of("loop", "startTime", "stopTime", "pauseTime", "resumeTime",
                "cycleInterval", "enabled"), labels(HEADER + "TimeSensor { " + CARET));
        assertEquals(List.of("beginCap", "ccw", "convex", "creaseAngle", "crossSection", "endCap",
                "orientation", "scale", "solid", "spine"), labels(HEADER + "Extrusion { " + CARET));
        assertEquals(List.of("height", "color", "normal", "texCoord", "ccw", "colorPerVertex",
                "creaseAngle", "normalPerVertex", "solid", "xDimension", "zDimension", "xSpacing",
                "zSpacing"), labels(HEADER + "ElevationGrid { " + CARET));
        assertEquals(List.of("children", "bboxCenter", "bboxSize", "bboxDisplay", "center", "range",
                "forceTransitions"), labels(HEADER + "LOD { " + CARET));
        assertEquals(List.of("children", "bboxCenter", "bboxSize", "bboxDisplay",
                "axisOfRotation"), labels(HEADER + "Billboard { " + CARET));
        assertEquals(List.of("fieldOfView", "jump", "retainUserOffsets", "viewAll", "orientation",
                "position", "centerOfRotation", "description", "farDistance", "nearDistance"),
                labels(HEADER + "Viewpoint { " + CARET));
        assertEquals(List.of("url", "bboxCenter", "bboxSize", "bboxDisplay", "visible", "load"),
                labels(HEADER + "Inline { " + CARET));
        assertEquals(List.of("ambientIntensity", "diffuseColor", "emissiveColor", "shininess",
                "specularColor", "transparency", "normalScale", "occlusionStrength"),
                labels(HEADER + "Appearance { material Material { " + CARET));
    }

    /**
     * The names a body is given a value for, which is what an {@code exposedField} and a {@code
     * field} are: the events that come with one are VRL2019's business, and the ones a node only
     * sends are nobody's to write.
     */
    @Test
    void eventsAreNotFieldNames() {
        Asked transform = ask(HEADER + "Transform { " + CARET);
        transform.offersNothing("addChildren", "removeChildren");
        Asked sensor = ask(HEADER + "TimeSensor { " + CARET);
        sensor.offersNothing("time", "isActive", "isPaused", "cycleTime", "fraction_changed");
        Asked collision = ask(HEADER + "Collision { " + CARET);
        collision.offersNothing("addChildren", "removeChildren");
        Asked lights = ask(HEADER + "SpotLight { " + CARET);
        lights.offersNothing("set_enable");
        Asked iws = ask(HEADER + "IndexedFaceSet { " + CARET);
        iws.offersNothing("set_coordIndex", "color_changed", "normal_changed");
    }

    @Test
    void anAlreadySetFieldGoesLastAndSaysSo() {
        Asked asked = ask(HEADER + "Transform { center 0 0 0\n" + CARET);
        asked.isExactly("children", "bboxCenter", "bboxSize", "bboxDisplay", "visible", "rotation",
                "scale", "scaleOrientation", "translation", "center");
        assertTrue(asked.find("center").detail().contains("already set here"),
                asked.find("center").detail());
        assertTrue(!asked.find("rotation").detail().contains("already set here"));

        Asked box = ask(HEADER + "Box { size 1 1 1\n" + CARET);
        box.isExactly("solid", "size");
        Asked inlined = ask(HEADER + "Inline { url \"a.wrl\" " + CARET);
        inlined.isExactly("bboxCenter", "bboxSize", "bboxDisplay", "visible", "load", "url");
    }

    /** An alias writes the field it names, so the field is set whichever spelling the file used. */
    @Test
    void anAliasCountsAsItsFieldAndIsNotOfferedAsANewName() {
        Asked asked = ask(HEADER + "Switch { choice 2\n" + CARET);
        asked.isExactly("bboxCenter", "bboxSize", "bboxDisplay", "whichChoice", "children");
        asked.offersNothing("choice");
        assertTrue(asked.find("children").detail().contains("already set here"),
                asked.find("children").detail());
    }

    /**
     * A {@code Script}'s own declarations are names the body has already written a value for, which
     * puts them in the field list rather than out of it - except the events, which only a {@code
     * ROUTE} reaches and a body cannot write a value for at all.
     *
     * <p>The defaults are part of the case, not decoration: Rule 6 spells a {@code field} as type,
     * name and value while an {@code eventIn} takes no value, and Rule 16 gives a {@code Script}
     * body those same two shapes - so a {@code field} whose value was never written is VRL1009
     * before it is anything else.
     */
    @Test
    void aScriptBodyCompetesWithDeclarationsSoTheirKeywordsComeToo() {
        Asked asked = ask(HEADER + "Script { " + CARET);
        asked.isExactly("url", "mustEvaluate", "directOutput", "field", "exposedField", "eventIn",
                "eventOut");
        for (String access : List.of("field", "exposedField", "eventIn", "eventOut")) {
            assertEquals("access type", asked.find(access).detail());
        }
        Asked declared = ask(HEADER + "Script {\n field SFVec3f k 0 0 0\n eventIn SFBool go\n "
                + CARET);
        declared.isExactly("url", "mustEvaluate", "directOutput", "k", "field", "exposedField",
                "eventIn", "eventOut");
        assertTrue(declared.find("k").detail().contains("already set here"),
                declared.find("k").detail());
        assertTrue(!declared.find("url").detail().contains("already set here"));
        declared.offersNothing("go");
        assertEquals(Completions.Kind.KEYWORD, declared.find("field").kind());
    }

    @Test
    void anInstanceOfAPrototypeOffersTheInterfaceItWasDeclaredWith() {
        Asked asked = ask("PROTO W [ exposedField SFVec3f offset 0 0 0, field SFNode content NULL,"
                + " eventIn SFTime tick ] { Box {} }\nDEF inst W { " + CARET);
        asked.isExactly("offset", "content");
        assertEquals("SFVec3f offset (exposedField)", asked.find("offset").detail());
        assertEquals("SFNode content (field)", asked.find("content").detail());
    }

    // ---- field values ------------------------------------------------------------------------

    /**
     * One point per type the grammar has, so that a template drifting for any of them is caught
     * here: the label is what a reader chooses, the inserted text is what lands in the file.
     */
    @Test
    void aValuePositionOffersItsTypesOwnShape() {
        Asked rotation = ask(HEADER + "Transform { rotation " + CARET);
        rotation.isExactly("0.0 0.0 1.0 0.0");
        assertEquals("${1:0.0} ${2:0.0} ${3:1.0} ${4:0.0}", rotation.insert("0.0 0.0 1.0 0.0"));

        Asked color = ask(HEADER + "Material { diffuseColor " + CARET);
        color.isExactly("0.0 0.0 0.0");
        Asked textureRotation = ask(HEADER + "TextureTransform { rotation " + CARET);
        textureRotation.isExactly("0.0");
        Asked seconds = ask(HEADER + "TimeSensor { startTime " + CARET);
        seconds.isExactly("0.0");
        assertEquals("${1:0.0}", seconds.insert("0.0"));
        Asked extent = ask(HEADER + "Text { maxExtent " + CARET);
        extent.isExactly("0.0");
        Asked image = ask(HEADER + "PixelTexture { image " + CARET);
        image.isExactly("1 1 3 0x000000");
        assertEquals("${1:1} ${2:1} ${3:3} 0x${4:000000}", image.insert("1 1 3 0x000000"));
        Asked title = ask(HEADER + "WorldInfo { title " + CARET);
        title.isExactly("\"\"");
        assertEquals("\"$1\"", title.insert("\"\""));
        Asked vec2 = ask(HEADER + "TextureTransform { translation " + CARET);
        vec2.isExactly("0.0 0.0");
    }

    /** A multiple value needs its brackets, and only the provider knows whether they are there. */
    @Test
    void aMultipleValueIsGivenBracketsUntilTheFileWritesItsOwn() {
        Asked point = ask(HEADER + "Coordinate { point " + CARET);
        point.isExactly("[ 0.0 0.0 0.0 ]");
        assertEquals("[ ${1:0.0} ${2:0.0} ${3:0.0},\n$0 ]", point.insert("[ 0.0 0.0 0.0 ]"));
        assertTrue(point.find("[ 0.0 0.0 0.0 ]").snippet());

        Asked inside = ask(HEADER + "Coordinate { point [ 1 2 3, " + CARET);
        inside.isExactly("0.0 0.0 0.0");
        assertEquals("${1:0.0} ${2:0.0} ${3:0.0},\n$0", inside.insert("0.0 0.0 0.0"));

        Asked indices = ask(HEADER + "IndexedFaceSet { coordIndex " + CARET);
        indices.isExactly("[ 0 ]");
        assertEquals("[ ${1:0},\n$0 ]", indices.insert("[ 0 ]"));
        Asked floats = ask(HEADER + "LOD { range [ 1 2, " + CARET);
        floats.isExactly("0.0");
        Asked strings = ask(HEADER + "Text { string " + CARET);
        strings.isExactly("[ \"\" ]");
        Asked mfColor = ask(HEADER + "Color { color " + CARET);
        mfColor.isExactly("[ 0.0 0.0 0.0 ]");
        Asked texCoord = ask(HEADER + "TextureCoordinate { point " + CARET);
        texCoord.isExactly("[ 0.0 0.0 ]");
        Asked keys = ask(HEADER + "CoordinateInterpolator { key " + CARET);
        keys.isExactly("[ 0.0 ]");
        Asked scriptUrl = ask(HEADER + "Script { url " + CARET);
        scriptUrl.isExactly("[ \"\" ]");
        Asked cross = ask(HEADER + "Extrusion { crossSection " + CARET);
        cross.isExactly("[ 0.0 0.0 ]");
    }

    /**
     * The two words a boolean takes, spelled the way the lexer reads them. The table's template for
     * {@code SFBool} is a choice of lowercase names, which X3D's XML grammar accepts and this one
     * reads as two identifiers - so a completion built from it would be reported for the next
     * keystroke.
     */
    @Test
    void aBooleanFieldIsAskedForTheTwoWordsTheLanguageHas() {
        ask(HEADER + "TimeSensor { loop " + CARET).isExactly("TRUE", "FALSE");
        ask(HEADER + "DirectionalLight { on " + CARET).isExactly("TRUE", "FALSE");
        ask(HEADER + "LOD { forceTransitions " + CARET).isExactly("TRUE", "FALSE");
        ask(HEADER + "Cone { bottom " + CARET).isExactly("TRUE", "FALSE");
        ask(HEADER + "Material { shininess " + CARET).offersNothing("TRUE");
        Asked one = ask(HEADER + "TimeSensor { loop " + CARET);
        assertEquals("SFBool value", one.find("TRUE").detail());
        assertTrue(!one.find("TRUE").snippet());
        assertEquals(Completions.Kind.VALUE, one.find("TRUE").kind());
    }

    /** The words a field has, quoted as its type says, come before the shape it would take. */
    @Test
    void anEnumeratedFieldIsOfferedItsWords() {
        Asked asked = ask(HEADER + "FontStyle { style " + CARET);
        asked.isExactly("PLAIN", "BOLD", "ITALIC", "BOLDITALIC", "\"\"");
        assertEquals("\"BOLD\"", asked.insert("BOLD"));
        assertEquals("SFString value", asked.find("ITALIC").detail());
        Asked family = ask(HEADER + "FontStyle { family " + CARET);
        family.isExactly("[ \"\" ]");
    }

    /** A value the file has already committed to is a value, not a question. */
    @Test
    void nothingIsOfferedWhereNothingIsMissing() {
        ask(HEADER + "Shape { nonsense " + CARET).isExactly();
        ask(HEADER + "Material { alphaTestEnabled " + CARET).isExactly();
        ask(HEADER + "MovieTexture { duration " + CARET).isExactly();
        ask(HEADER + "Box { size 1 1 1 } # trailing" + CARET).isExactly();
        Asked glued = ask(HEADER + "0." + CARET);
        assertEquals(Kind.NONE, glued.context().kind(), "a number is not being completed");
    }

    /** Without snippets the templates are rendered to the text they would leave behind. */
    @Test
    void aClientThatNeverAgreedToSnippetsIsGivenPlainText() {
        Asked point = ask(HEADER + "Coordinate { point " + CARET, false);
        point.isExactly("[ 0.0 0.0 0.0 ]");
        assertEquals("[ 0.0 0.0 0.0 ]", point.insert("[ 0.0 0.0 0.0 ]"));
        assertTrue(!point.find("[ 0.0 0.0 0.0 ]").snippet(), "no tab stops it cannot use");

        Asked geometry = ask(HEADER + "Shape { geometry " + CARET, false);
        assertEquals("Box {}", geometry.insert("Box"));
        assertTrue(!geometry.find("Box").snippet());

        Asked title = ask(HEADER + "WorldInfo { title " + CARET, false);
        assertEquals("\"\"", title.insert("\"\""));
    }

    // ---- nodes as values ---------------------------------------------------------------------

    @Test
    void aNodeFieldIsAskedForTheNodesItTakes() {
        Asked appearance = ask(HEADER + "Shape { appearance " + CARET);
        appearance.isExactly("Appearance", "NULL");
        assertEquals("Appearance {$0}", appearance.insert("Appearance"));
        assertTrue(appearance.find("Appearance").snippet());
        assertEquals(Completions.Kind.NODE, appearance.find("Appearance").kind());

        Asked geometry = ask(HEADER + "Shape { geometry " + CARET);
        geometry.isExactly("Box", "Cone", "Cylinder", "ElevationGrid", "Extrusion", "IndexedFaceSet",
                "IndexedLineSet", "LineSet", "PointSet", "Sphere", "Text", "NULL");
        geometry.offersNothing("Material", "Appearance", "Group", "Transform");

        Asked texture = ask(HEADER + "Appearance { texture " + CARET);
        texture.isExactly("ImageTexture", "MovieTexture", "PixelTexture", "NULL");
        Asked material = ask(HEADER + "Appearance { material " + CARET);
        material.isExactly("Material", "NULL");
        Asked coord = ask(HEADER + "IndexedFaceSet { coord " + CARET);
        coord.isExactly("Coordinate", "NULL");
        Asked fontStyle = ask(HEADER + "Text { fontStyle " + CARET);
        fontStyle.isExactly("FontStyle", "NULL");
        Asked source = ask(HEADER + "Sound { source " + CARET);
        source.isExactly("AudioClip", "MovieTexture", "NULL");
    }

    @Test
    void aGroupWantsAnyNodeThatCanHoldAPlaceInTheScene() {
        Asked asked = ask(HEADER + "Transform { children " + CARET);
        asked.offers("Group", "Billboard", "Shape", "Box", "IndexedFaceSet", "Script", "Switch",
                "TimeSensor", "Collision", "Inline");
        asked.offersNothing("Appearance", "Material", "Coordinate", "Normal", "FontStyle",
                "TextureCoordinate", "TextureTransform");
        assertEquals("NULL", asked.labels().get(asked.candidates().size() - 1));
        assertEquals("MFNode", asked.find("NULL").detail());
        assertTrue(asked.find("NULL").documentation().contains("no node"));
        Asked alias = ask(HEADER + "Switch { choice " + CARET);
        assertEquals(asked.candidates().size(), alias.candidates().size(),
                "the alias names the same field and takes the same nodes");
    }

    /**
     * A {@code PROTO} instance is a node whatever the field's own constraint says: the constraint is
     * spelled with an X3D abstract type, so nothing in the table can tell whether the file's
     * prototype fills the slot - and a scene built out of PROTOs would be offered a list it cannot
     * use.
     */
    @Test
    void aPrototypeFillsAnyNodeSlotWhateverTheTableConstrainsItTo() {
        String proto = HEADER + "PROTO Widget [] { Box {} }\n";
        Asked constrained = ask(proto + "Shape { geometry " + CARET);
        constrained.offers("Widget", "Box");
        assertEquals("Widget {$0}", constrained.insert("Widget"));
        Asked grouped = ask(proto + "Transform { children " + CARET);
        grouped.offers("Widget", "Group");
        Asked unconstrained = ask("PROTO W [ field SFNode content ]\n" + HEADER + "DEF i W { content "
                + CARET);
        unconstrained.offers("W", "Box", "Appearance", "NULL");
        assertEquals(58, unconstrained.candidates().size(),
                "every node once, the instance itself once, and NULL");
        Asked external = ask(HEADER + "EXTERNPROTO Wheel [ field MFString url ] [ \"wheel.wrl\" ]\n"
                + "Shape { geometry " + CARET);
        external.offers("Wheel");
        assertEquals("EXTERNPROTO", external.find("Wheel").detail());
    }

    // ---- routes and names --------------------------------------------------------------------

    @Test
    void aRoutesSourceEndIsWhatTheNodeSendsOut() {
        Asked asked = ask(HEADER + "DEF t Transform { }\nROUTE t." + CARET);
        asked.isExactly("children", "center", "rotation", "scale", "scaleOrientation", "translation",
                "children_changed", "center_changed", "rotation_changed", "scale_changed",
                "scaleOrientation_changed", "translation_changed");
        asked.offersNothing("set_translation", "bboxCenter", "addChildren");
        assertEquals("SFVec3f translation_changed (eventOut)",
                asked.find("translation_changed").detail());

        Asked anchor = ask(HEADER + "DEF a Anchor { }\nROUTE a." + CARET);
        anchor.offers("url", "description_changed", "parameter_changed", "children_changed");
        anchor.offersNothing("set_url");
        Asked sensor = ask(HEADER + "DEF ts TimeSensor { }\nROUTE ts." + CARET);
        sensor.offers("time", "cycleTime", "elapsedTime", "fraction_changed", "isActive",
                "isPaused");
        // An exposedField is readable as well, so the names a body writes are outputs too; only the
        // set_ spelling belongs to the other end.
        sensor.offers("loop", "cycleInterval", "enabled");
        sensor.offersNothing("set_startTime", "set_loop", "set_enabled");
        // Nothing at all: Box's two names are both fields, so it neither sends nor receives.
        ask(HEADER + "DEF b Box { }\nROUTE b." + CARET).isExactly();
    }

    @Test
    void aRoutesTargetEndIsWhatTheNodeTakesIn() {
        Asked asked = ask(HEADER + "DEF ts TimeSensor { }\nROUTE x TO ts." + CARET);
        asked.isExactly("loop", "startTime", "stopTime", "pauseTime", "resumeTime", "cycleInterval",
                "enabled", "set_loop", "set_startTime", "set_stopTime", "set_pauseTime",
                "set_resumeTime", "set_cycleInterval", "set_enabled");
        asked.offersNothing("time", "fraction_changed", "isActive");
        Asked billboard = ask(HEADER + "DEF a Anchor { }\nDEF b Billboard { }\nROUTE a.description"
                + " TO b." + CARET);
        billboard.isExactly("children", "addChildren", "removeChildren", "axisOfRotation",
                "set_children", "set_axisOfRotation");
        assertTrue(billboard.find("addChildren").detail().contains("eventIn"));
        Asked viewpoint = ask(HEADER + "DEF vp Viewpoint { }\nROUTE x TO vp." + CARET);
        viewpoint.offers("set_bind", "set_position", "jump", "set_jump", "set_fieldOfView");
        viewpoint.offersNothing("bindTime", "isBound");
    }

    /** A {@code Script} is asked of its own declarations, since nothing else in the file has them. */
    @Test
    void aScriptsEventsAreTheOnesTheFileWrotePlusTheThreeTheTableRemembers() {
        Asked source = ask("DEF s Script { field SFVec3f k 0 0 0\n eventOut SFTime done\n}\n"
                + "ROUTE s." + CARET);
        source.isExactly("done", "url", "url_changed");
        source.offersNothing("k", "done_changed", "set_url");
        Asked target = ask("DEF s Script { field SFVec3f k 0 0 0\n eventIn SFBool go\n}\n"
                + "ROUTE x TO s." + CARET);
        target.isExactly("go", "url", "set_url");
        target.offersNothing("k", "set_go");
    }

    @Test
    void aNameIsAskedForTheDefinitionsThatCanAnswerIt() {
        Asked use = ask(HEADER + "DEF b Box { }\nUSE " + CARET + "\nDEF late Anchor { }");
        use.isExactly("b");
        assertEquals(": Box", use.find("b").detail(), "a name without its node type is two lookups");
        Asked routeSource = ask(HEADER + "DEF a Anchor { }\nDEF b Billboard { }\nROUTE " + CARET
                + ".url TO b.");
        routeSource.offers("a", "b");
        Asked routeTarget = ask(HEADER + "DEF a Anchor { }\nDEF b Billboard { }\nROUTE a.url TO "
                + CARET);
        routeTarget.offers("a", "b");
        // A ROUTE may be written before the nodes it wires, so a definition below still answers.
        Asked below = ask(HEADER + "ROUTE " + CARET + " TO z\nDEF d SpotLight { }");
        below.isExactly("d");
    }

    // ---- interfaces --------------------------------------------------------------------------

    @Test
    void anInterfaceLineIsAskedForAnAccessTypeThenAType() {
        ask("PROTO W [ " + CARET).isExactly("field", "exposedField", "eventIn", "eventOut");
        Asked typed = ask("PROTO W [ field " + CARET);
        typed.isExactly("SFBool", "SFColor", "SFFloat", "SFImage", "SFInt32", "SFNode", "SFRotation",
                "SFString", "SFTime", "SFVec2f", "SFVec3f", "MFBool", "MFColor", "MFFloat",
                "MFInt32", "MFNode", "MFRotation", "MFString", "MFTime", "MFVec2f", "MFVec3f");
        assertEquals(21, typed.candidates().size(), "the type set is closed");
        assertEquals("a node or NULL", typed.find("SFNode").detail());
        assertEquals("quoted text", typed.find("MFString").detail());
        Asked inScript = ask(HEADER + "Script { field " + CARET);
        assertEquals(typed.labels(), inScript.labels(), "a Script interface is the same 21");
    }

    /**
     * The interface names an {@code IS} may bind to, which is the enclosing PROTO's own list read
     * through what the body-side field can do - the same filter VRL2014 reports a wrong binding with.
     *
     * <p>An {@code exposedField} in the body can be exposed any way the interface spells it, so it
     * narrows nothing; an {@code initializeOnly} field can only ever be read, and drops both the
     * interface's {@code eventIn} and its {@code exposedField}. Only one PROTO's interface answers at
     * a time: the names another PROTO in the same file declared belong to that other body.
     */
    @Test
    void anIsBindingOffersOnlyTheNamesThatCanCarryIt() {
        Asked asked = ask("PROTO W [ field SFTime when 0.0, eventIn SFBool go ] {\n"
                + "  Transform { visible IS " + CARET + "\n  }\n}\n");
        asked.isExactly("when");
        Asked exposed = ask("PROTO W [ exposedField SFVec3f offset 0 0 0, field SFNode content NULL,"
                + " eventIn SFTime tick ] {\n  Transform { translation IS " + CARET + "\n  }\n}\n");
        exposed.isExactly("offset", "content", "tick");
        Asked readonly = ask("PROTO W [ field SFVec3f offset 0 0 0, eventIn SFBool go, eventOut"
                + " SFTime sent, exposedField SFVec3f pos 0 0 0 ] {\n  Transform { bboxCenter IS "
                + CARET + "\n  }\n}\n");
        readonly.isExactly("offset", "sent");
        Asked elsewhere = ask("PROTO W [ exposedField SFVec3f offset 0 0 0 ] {\n"
                + "  Transform { translation IS offset }\n}\n"
                + "PROTO V [ eventIn SFTime when ] {\n  Transform { translation IS " + CARET
                + "\n  }\n}\n");
        elsewhere.isExactly("when");
    }

    @Test
    void anExternProtosUriListIsTheFilesBesideIt() {
        Asked asked = ask(HEADER + "EXTERNPROTO Foo [ field SFNode x ] [ \"" + CARET);
        asked.isExactly("chair.wrl.gz", "protos.wrl");
        assertEquals("file next to this one", asked.find("protos.wrl").detail());
        assertEquals(Kind.URI, asked.context().kind());
    }

    /**
     * The slots the grammar has settled, where a list would be a guess: a name its author invents,
     * and a position the syntax already closed.
     */
    @Test
    void aNameTheFileIsInventingIsNobodyElsesToChoose() {
        ask(HEADER + "DEF " + CARET).isExactly();
        ask(HEADER + "DEF po" + CARET).isExactly();
        ask("PROTO Widget [ exposedField SFTime " + CARET).isExactly();
        ask("PROTO Widget [ eventIn SFBool set_" + CARET).isExactly();
        // A USE is a whole statement, so nothing may follow it but another statement - which is not
        // this position's question; every name in the file is already spent on the USE itself.
        ask(HEADER + "DEF b Box { }\nUSE b\n" + CARET).isExactly();
        ask(HEADER + "Box { size 1 1 1 }" + CARET).offers("Box");
        ask(HEADER + "Box { size 1 1 1 } # a comment\n" + CARET).offers("Box");
    }
}
