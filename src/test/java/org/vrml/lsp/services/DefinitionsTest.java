package org.vrml.lsp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.vrml.lsp.diagnostics.DocumentAnalyzer;
import org.vrml.lsp.spec.Specs;

/**
 * Where a name was written, and everywhere the file reads it.
 *
 * <p>An answer is a range of the document, and a range means nothing on its own, so each target is
 * rendered the way a reader checks it: the line and column it starts on - line from 1, column from 0,
 * as the protocol counts them - and the stretch of source it covers. A test can then see at once
 * whether a jump landed on the {@code DEF} or on the word the pointer left, and whether a reference
 * search named the line it claims.
 *
 * <p>The pointer is given by writing a caret into one of the fixture's words, which has to appear
 * exactly once; a case that meant to ask about the second {@code intensity} says so with more of the
 * line around it.
 */
class DefinitionsTest {

    /** The marker a case writes into the word the pointer stands on. */
    private static final String CARET = "^";
    private static final String HEADER = "#VRML V2.0 utf8\n";

    /** The character each rendered target quotes the source slice with. */
    private static final char QUOTE = '`';

    /** Both {@code Script}s declare {@code eventIn SFBool go}, so a marker needs the line after it. */
    private static final String GO_DECLARATION = "eventIn SFBool g^o\n  eventOut";

    /** A {@code PROTO} with its interface, one {@code IS} binding and two instances of it. */
    private static final String PROTO = HEADER + "PROTO Lamp [\n"
            + "  exposedField SFFloat intensity 0.8\n"
            + "  eventIn SFTime turnOn\n"
            + "  eventOut SFColor glow_changed\n"
            + "] {\n"
            + "  PointLight {\n"
            + "    intensity IS intensity\n"
            + "    on TRUE\n"
            + "  }\n"
            + "}\n"
            + "DEF hall Lamp {\n"
            + "  intensity 0.4\n"
            + "}\n"
            + "Lamp {\n"
            + "  intensity 0.2\n"
            + "}\n";

    /** Two {@code Script}s that declare the same event name, wired to a clock both ways. */
    private static final String SCRIPT = HEADER + "DEF body Script {\n"
            + "  field SFVec3f k 0 0 0\n"
            + "  eventIn SFBool go\n"
            + "  eventOut SFTime done\n"
            + "  url \"javascript: print(k.x)\"\n"
            + "}\n"
            + "DEF clock TimeSensor {\n"
            + "  cycleInterval 2\n"
            + "}\n"
            + "ROUTE clock.isActive TO body.go\n"
            + "ROUTE body.done TO clock.stopTime\n"
            + "DEF other Script {\n"
            + "  eventIn SFBool go\n"
            + "  url \"\"\n"
            + "}\n";

    /** A light and a transform, one {@code USE} inside a node and one at the top level, and a route. */
    private static final String SCENE = HEADER + "DEF sun DirectionalLight {\n"
            + "  intensity 0.9\n"
            + "}\n"
            + "DEF root Transform {\n"
            + "  translation 0 0 0\n"
            + "  children [ USE sun ]\n"
            + "}\n"
            + "USE sun\n"
            + "ROUTE sun.intensity_changed TO root.set_translation\n";

    /** A field the language declares, which no line of the file declares. */
    private static final String BUILTIN_FIELD = HEADER + "DEF root Transform {\n"
            + "  rotation 0 0 0 1\n"
            + "}\n";

    /** A prototype whose interface arrives from another file. */
    private static final String EXTERN = HEADER + "EXTERNPROTO Lamp [\n"
            + "  exposedField SFFloat intensity\n"
            + "] \"lamp.wrl#Lamp\"\n"
            + "DEF hall Lamp {\n"
            + "  intensity 0.4\n"
            + "}\n";

    /** A name defined inside a node's contents and read both there and outside it. */
    private static final String NESTED = HEADER + "DEF root Transform {\n"
            + "  children [\n"
            + "    DEF inner Box {\n"
            + "      size 1 1 1\n"
            + "    }\n"
            + "    USE inner\n"
            + "  ]\n"
            + "}\n"
            + "USE inner\n";

    /** One file that cannot be parsed as written: {@code Def} is no keyword. */
    private static final String BROKEN = HEADER + "DEF a Box {\n"
            + "  size 1 1\n"
            + "}\n"
            + "Def b Box {}\n"
            + "ROUTE a.fraction_changed TO b.set_shininess\n";

    /** The prototype's instance wired into, so a route reaches the interface through {@code set_}. */
    private static final String WIRED = PROTO + "DEF clock TimeSensor {\n}\n"
            + "ROUTE clock.isActive TO hall.set_intensity\n";

    /** The same instance wired out of, which is the other half of that naming. */
    private static final String SOURCED = PROTO + "DEF clock TimeSensor {\n}\n"
            + "ROUTE hall.glow_changed TO clock.stopTime\n";

    /** One pointer's two answers. */
    private record Pointer(DocumentAnalyzer.Analysis analysis, int caret, String text) {

        /** Where the word was declared, which is a built-in's answer of nothing. */
        List<String> jumps() {
            return listed(Definitions.definition(analysis.parse(), analysis.symbols(),
                    Specs.standard(), caret), text);
        }

        /** Everywhere the file reads the word, its declaration among them. */
        List<String> reads() {
            return listed(Definitions.references(analysis.parse(), analysis.symbols(),
                    Specs.standard(), caret), text);
        }
    }

    private static Pointer pointAt(String document, String marker) {
        String word = marker.replace(CARET, "");
        int found = document.indexOf(word);
        assertTrue(found >= 0, "the fixture has no such text: " + word);
        assertTrue(document.indexOf(word, found + 1) < 0,
                "the marker names more than one spot: " + word);
        int caret = found + marker.indexOf(CARET);
        return new Pointer(DocumentAnalyzer.analyze(document), caret, document);
    }

    private static List<String> listed(List<Definitions.Target> targets, String text) {
        List<String> shown = new ArrayList<>();
        for (Definitions.Target target : targets) {
            shown.add("L" + line(text, target.start()) + ":" + column(text, target.start())
                    + " " + QUOTE + text.substring(target.start(), target.end()) + QUOTE);
        }
        return shown;
    }

    private static int line(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int column(String text, int offset) {
        return offset - text.lastIndexOf('\n', offset - 1) - 1;
    }

    // ---- going to a declaration --------------------------------------------------------------

    /** Standing on a {@code DEF} is standing on the answer, so the jump is to itself. */
    @Test
    void aDefinitionIsItsOwnLanding() {
        assertEquals(List.of("L2:4 `sun`"), pointAt(SCENE, "DEF s^un").jumps());
    }

    /** A {@code USE} reaches the {@code DEF} written before it. */
    @Test
    void aUseJumpsToTheDefinitionItReads() {
        assertEquals(List.of("L2:4 `sun`"), pointAt(SCENE, "USE s^un\nROUTE").jumps());
    }

    /** Both ends of a {@code ROUTE} name a node, and each of them reaches its {@code DEF}. */
    @Test
    void eachEndOfARouteNamesANodeWorthJumpingTo() {
        assertEquals(List.of("L2:4 `body`"), pointAt(SCRIPT, "ROUTE b^ody.done TO").jumps());
        assertEquals(List.of("L5:4 `root`"), pointAt(SCENE, "TO ro^ot.set_translation").jumps());
    }

    /** A {@code ROUTE} says {@code set_translation} of a field the file wrote {@code translation}. */
    @Test
    void theSetNameOnARouteUndoesItsPrefix() {
        assertEquals(List.of("L6:2 `translation`"),
                pointAt(SCENE, "TO root.set_translat^ion").jumps());
    }

    /** The other half: {@code intensity_changed} is the field the scene gave a value. */
    @Test
    void theEventNameOnARouteUndoesItsSuffix() {
        assertEquals(List.of("L3:2 `intensity`"),
                pointAt(SCENE, "ROUTE sun.intensity^_changed").jumps());
    }

    /** An instance writes an interface name and owns nothing, so the jump leaves the instance. */
    @Test
    void anInstanceFieldJumpsToTheInterfaceLineThatDeclaredIt() {
        assertEquals(List.of("L3:23 `intensity`"),
                pointAt(PROTO, "DEF hall Lamp {\n  i^ntensity 0.4").jumps());
    }

    /** An {@code IS} reaches out of the body to the interface the enclosing {@code PROTO} exports. */
    @Test
    void anIsClauseRightHandSideJumpsOutOfTheBody() {
        assertEquals(List.of("L3:23 `intensity`"), pointAt(PROTO, "IS i^ntensity").jumps());
    }

    /** A prototype's instances are spelled with its name, which is where the jump goes. */
    @Test
    void anInstancesTypeJumpsToItsPrototype() {
        assertEquals(List.of("L2:6 `Lamp`"), pointAt(PROTO, "DEF hall L^amp").jumps());
    }

    /** An {@code EXTERNPROTO} has no body to search, but its name is a line all the same. */
    @Test
    void anExternalPrototypeIsDeclaredByTheLineThatImportsIt() {
        assertEquals(List.of("L2:12 `Lamp`"), pointAt(EXTERN, "DEF hall L^amp {").jumps());
        assertEquals(List.of("L3:23 `intensity`"),
                pointAt(EXTERN, "exposedField SFFloat intens^ity").jumps());
        assertEquals(List.of("L3:23 `intensity`"),
                pointAt(EXTERN, "DEF hall Lamp {\n  i^ntensity 0.4").jumps());
    }

    /** A route reaching a prototype instance goes through both namings at once. */
    @Test
    void aRouteIntoAnInstanceReachesTheInterfaceThroughTheSetName() {
        assertEquals(List.of("L3:23 `intensity`"),
                pointAt(WIRED, "TO hall.set_i^ntensity").jumps());
        assertEquals(List.of("L5:19 `glow_changed`"),
                pointAt(SOURCED, "ROUTE hall.g^low_changed TO").jumps());
        assertEquals(List.of("L12:4 `hall`"), pointAt(WIRED, "TO h^all.set_intensity").jumps());
    }

    /** A {@code Script}'s declarations are the file's own, so the jump stays where it stands. */
    @Test
    void aScriptDeclarationIsItsOwnLanding() {
        assertEquals(List.of("L4:17 `go`"), pointAt(SCRIPT, GO_DECLARATION).jumps());
        assertEquals(List.of("L4:17 `go`"), pointAt(SCRIPT, "TO body.g^o").jumps());
    }

    /** A built-in is declared in the language and in no file, so there is nowhere to go. */
    @Test
    void aNameTheLanguageDeclaredHasNoLineToReach() {
        assertEquals(List.of(), pointAt(SCENE, "DEF sun Directio^nalLight").jumps());
        assertEquals(List.of(), pointAt(BUILTIN_FIELD, "rotatio^n 0 0 0 1").jumps());
        assertEquals(List.of(), pointAt(SCRIPT, "  u^rl \"javascript").jumps());
    }

    /** Text inside a {@code Script}'s string is not a name, however much it looks like one. */
    @Test
    void aWordInsideAStringIsNobody() {
        assertEquals(List.of(), pointAt(SCRIPT, "print(^k.x)").jumps());
    }

    /** A broken file names no node, and a jump refuses to pretend otherwise. */
    @Test
    void aNameNothingDefinedIsAnsweredWithSilence() {
        assertEquals(List.of(), pointAt(BROKEN, "TO b^.set_shininess").jumps());
    }

    // ---- finding every use -------------------------------------------------------------------

    /** A name's uses are its {@code USE}s and every {@code ROUTE} end that speaks it. */
    @Test
    void aDefinitionListsItsUsesAndBothEndsOfEveryRoute() {
        assertEquals(List.of("L2:4 `sun`", "L7:17 `sun`", "L9:4 `sun`", "L10:6 `sun`"),
                pointAt(SCENE, "DEF s^un").reads());
    }

    /** Reading the name from a {@code USE} rather than the {@code DEF} changes nothing. */
    @Test
    void theSameNameReadsTheSameSetFromEitherSpelling() {
        assertEquals(pointAt(SCENE, "DEF s^un").reads(), pointAt(SCENE, "USE s^un\nROUTE").reads());
    }

    /** A name defined inside a node stays visible to the end of the scope that encloses it. */
    @Test
    void aNestedNameIsFollowedToTheEndOfItsEnclosingScope() {
        assertEquals(List.of("L4:8 `inner`", "L7:8 `inner`", "L10:4 `inner`"),
                pointAt(NESTED, "DEF i^nner Box").reads());
        assertEquals(pointAt(NESTED, "DEF i^nner Box").reads(),
                pointAt(NESTED, "}\nUSE i^nner").reads());
    }

    /**
     * An interface member is valued by every instance the file writes, which is the edit a reference
     * search exists to catch before it is made.
     */
    @Test
    void anInterfaceMemberListsEveryInstanceThatValuesIt() {
        assertEquals(List.of("L3:23 `intensity`", "L8:17 `intensity`", "L13:2 `intensity`",
                "L16:2 `intensity`"), pointAt(PROTO, "i^ntensity 0.8").reads());
    }

    /** ... and a route that drives it is a fifth reading of the same line. */
    @Test
    void aRoutedInterfaceMemberAddsTheRouteEnd() {
        assertEquals(List.of("L3:23 `intensity`", "L8:17 `intensity`", "L13:2 `intensity`",
                "L16:2 `intensity`", "L20:29 `set_intensity`"),
                pointAt(WIRED, "i^ntensity 0.8").reads());
    }

    /** A prototype's name is read by its instances, and by nothing inside its own body. */
    @Test
    void aPrototypeNameListsItsInstances() {
        assertEquals(List.of("L2:6 `Lamp`", "L12:9 `Lamp`", "L15:0 `Lamp`"),
                pointAt(PROTO, "PROTO L^amp [").reads());
        assertEquals(List.of("L2:12 `Lamp`", "L5:9 `Lamp`"),
                pointAt(EXTERN, "EXTERNPROTO L^amp [").reads());
    }

    /** Two {@code Script}s declaring one event are two nodes, not one name read twice. */
    @Test
    void twoScriptsNamingOneEventAreNotEachOthersReferences() {
        assertEquals(List.of("L4:17 `go`", "L11:29 `go`"),
                pointAt(SCRIPT, GO_DECLARATION).reads());
    }

    /** What the language declares, the file at least shows where it writes. */
    @Test
    void aBuiltInFieldListsWhereTheSceneWritesIt() {
        assertEquals(List.of("L6:2 `url`", "L15:2 `url`"),
                pointAt(SCRIPT, "  u^rl \"javascript").reads());
        assertEquals(List.of("L3:2 `rotation`"), pointAt(BUILTIN_FIELD, "rotatio^n 0 0 0 1").reads());
    }

    /** One endpoint of a route is a reading of the name even when nothing else knows it. */
    @Test
    void aRouteEndCountsWhereNoDefinitionDoes() {
        assertEquals(List.of("L6:30 `set_shininess`"),
                pointAt(BROKEN, "TO b.set_shini^ness").reads());
    }

    /** A broken file still answers for what survived of it. */
    @Test
    void aBrokenFilesSurvivorStillListsItsRouteEnd() {
        assertEquals(List.of("L2:4 `a`", "L6:6 `a`"), pointAt(BROKEN, "DEF ^a Box").reads());
        assertEquals(List.of(), pointAt(BROKEN, "TO b^.set_shininess").reads());
    }

    // ---- what every answer owes the reader ---------------------------------------------------

    /** A landing spot is a word, never a line: what the source slice covers holds no whitespace. */
    @Test
    void everyTargetIsAWordAndNotALine() {
        for (Pointer pointer : pointers()) {
            for (String target : combine(pointer)) {
                String slice = target.substring(target.indexOf(QUOTE) + 1, target.length() - 1);
                assertTrue(slice.matches("\\S+"), "a target spanning more than a word: " + target);
            }
        }
    }

    /** Where the editor says a name was written is one of the places it says the name is read. */
    @Test
    void aJumpLandsOnOneOfTheListedReferences() {
        for (Pointer pointer : pointers()) {
            List<String> reads = pointer.reads();
            for (String jump : pointer.jumps()) {
                assertTrue(reads.contains(jump), jump + " was offered as a declaration but not as "
                        + "a reference; the references were " + reads);
            }
        }
    }

    /** Targets come back in the order the reader would meet them. */
    @Test
    void everyAnswerReadsTopToLower() {
        for (Pointer pointer : pointers()) {
            assertAscending(pointer.jumps());
            assertAscending(pointer.reads());
        }
    }

    private static void assertAscending(List<String> targets) {
        List<Integer> starts = targets.stream().map(DefinitionsTest::lineOf).toList();
        assertEquals(starts.stream().sorted().toList(), starts, "out of order: " + targets);
    }

    private static int lineOf(String target) {
        return Integer.parseInt(target.substring(1, target.indexOf(':')));
    }

    private static List<String> combine(Pointer pointer) {
        List<String> all = new ArrayList<>(pointer.jumps());
        all.addAll(pointer.reads());
        return all;
    }

    /** Every pointer these cases ask about, so the three judgements above can hold each to its word. */
    private static List<Pointer> pointers() {
        return List.of(
                pointAt(SCENE, "DEF s^un"),
                pointAt(SCENE, "USE s^un\nROUTE"),
                pointAt(SCENE, "TO ro^ot.set_translation"),
                pointAt(SCENE, "TO root.set_translat^ion"),
                pointAt(SCENE, "ROUTE sun.intensity^_changed"),
                pointAt(SCENE, "DEF sun Directio^nalLight"),
                pointAt(SCRIPT, GO_DECLARATION),
                pointAt(SCRIPT, "TO body.g^o"),
                pointAt(SCRIPT, "ROUTE b^ody.done"),
                pointAt(SCRIPT, "print(^k.x)"),
                pointAt(SCRIPT, "  u^rl \"javascript"),
                pointAt(PROTO, "i^ntensity 0.8"),
                pointAt(PROTO, "DEF hall Lamp {\n  i^ntensity 0.4"),
                pointAt(PROTO, "IS i^ntensity"),
                pointAt(PROTO, "DEF hall L^amp"),
                pointAt(PROTO, "PROTO L^amp ["),
                pointAt(EXTERN, "EXTERNPROTO L^amp ["),
                pointAt(EXTERN, "exposedField SFFloat intens^ity"),
                pointAt(EXTERN, "DEF hall Lamp {\n  i^ntensity 0.4"),
                pointAt(NESTED, "DEF i^nner Box"),
                pointAt(NESTED, "}\nUSE i^nner"),
                pointAt(WIRED, "TO hall.set_i^ntensity"),
                pointAt(WIRED, "TO h^all.set_intensity"),
                pointAt(SOURCED, "ROUTE hall.g^low_changed TO"),
                pointAt(BUILTIN_FIELD, "rotatio^n 0 0 0 1"),
                pointAt(BROKEN, "DEF ^a Box"),
                pointAt(BROKEN, "TO b^.set_shininess"),
                pointAt(BROKEN, "TO b.set_shini^ness"));
    }
}
