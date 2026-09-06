package org.vrml.lsp.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.vrml.lsp.diagnostics.Codes;
import org.vrml.lsp.diagnostics.DocumentAnalyzer;
import org.vrml.lsp.diagnostics.Issue;
import org.vrml.lsp.diagnostics.Severity;
import org.vrml.lsp.text.LineIndex;

/**
 * The semantic rules, each on the shortest document that shows it and nothing else.
 *
 * <p>The corpus tests pin what 263 real files do together; they cannot pin the rules the corpus has
 * no case for, and when a count changes there they do not say which judgement moved. Each case here
 * is one judgement, read through {@link DocumentAnalyzer} because that is the path a user sees.
 *
 * <p>Scaffolding is deliberate: nodes that would be reported for standing at the root of a file are
 * nested inside a {@code Shape}, so a failure points at the rule under test rather than at where the
 * test happened to put the node.
 *
 * <p>Severity is part of every expectation. It is not taste: Xj3D's {@code convertException()} sends
 * a bad field name or value to {@code warningReport()} and keeps loading the world, and the
 * formatter added in M6 refuses to reindent a document holding an error.
 */
class SemanticsTest {

    private static List<Issue> check(String document) {
        return DocumentAnalyzer.analyze(document).issues();
    }

    /** {@code VRL2002 ERROR 3:3-3:12}, one line per issue; the message is asserted where it is the point. */
    private static List<String> labels(String document, List<Issue> issues) {
        LineIndex index = new LineIndex(document);
        List<String> out = new ArrayList<>();
        for (Issue issue : issues) {
            out.add(issue.codeLabel() + " " + issue.severity() + " " + index.describe(issue.start())
                    + "-" + index.describe(issue.end()));
        }
        return out;
    }

    /** The issues come back because several cases are also a claim about what the message says. */
    private static List<Issue> assertReported(String document, String... expected) {
        List<Issue> issues = check(document);
        assertEquals(List.of(expected), labels(document, issues), "wrong reports for\n" + document);
        return issues;
    }

    private static void assertClean(String document) {
        assertEquals(List.of(), labels(document, check(document)),
                "nothing should be reported for\n" + document);
    }

    /**
     * The guard on cases written to be silent. Structural codes are allowed - a broken statement is
     * what makes the statement unjudgeable - and 3005 is allowed because it is the reason an X3D
     * document is left alone. Anything else the semantic pass could say is listed by the assertion.
     */
    private static void assertNoSemanticVerdict(String document) {
        List<String> verdicts = new ArrayList<>();
        for (Issue issue : check(document)) {
            if (issue.code() >= Codes.UNKNOWN_NODE_TYPE && issue.code() != Codes.X3D_DIALECT) {
                verdicts.add(issue.codeLabel() + " " + issue.message());
            }
        }
        assertEquals(List.of(), verdicts, "the semantic pass spoke up about\n" + document);
    }

    // ---- names ---------------------------------------------------------------------------

    @Test
    void anUnknownNodeTypeIsNamedAndItsBodyIsLeftAlone() {
        // Once the type is wrong there is nothing to match 'size' against, and the standard's own
        // PROTO escape hatch means the name may be defined in a file this one reads at load time.
        assertReported("""
                #VRML V2.0 utf8
                Zorkmid {
                  size 1 1 1
                }
                """, "VRL2001 ERROR 2:1-2:8");
    }

    @Test
    void aNameOneEditFromANodeTypeIsAdvice() {
        List<Issue> issues = assertReported("""
                #VRML V2.0 utf8
                Transfom {
                }
                """, "VRL2001 ERROR 2:1-2:9", "VRL3001 HINT 2:1-2:9");
        assertEquals("did you mean the node type 'Transform'?", issues.get(1).message());
    }

    @Test
    void anUnknownFieldNameMarksTheNameNotTheValue() {
        List<Issue> issues = assertReported("""
                #VRML V2.0 utf8
                Shape {
                  apperance Appearance {}
                }
                """, "VRL2002 ERROR 3:3-3:12", "VRL3001 HINT 3:3-3:12");
        assertEquals("'Shape' has no field named 'apperance'", issues.get(0).message());
        assertEquals("did you mean the field 'appearance'?", issues.get(1).message());
    }

    @Test
    void aNameThreeEditsAwayFromAnythingIsNotGivenASuggestion() {
        // Material's own diffuseColor is three edits off 'diffColor': closer than that is advice,
        // further is a different word, and offering it would send the reader to the wrong field.
        assertReported("""
                #VRML V2.0 utf8
                Shape {
                  appearance Appearance {
                    material Material {
                      diffColor 0 0 0
                    }
                  }
                }
                """, "VRL2002 ERROR 5:7-5:16");
    }

    @Test
    void aProtoNamedAfterANodeTypeCanNeverBeInstantiated() {
        assertReported("""
                #VRML V2.0 utf8
                PROTO Box [
                  field SFVec3f size 1 1 1
                ]
                {
                  Box {
                    size IS size
                  }
                }
                """, "VRL2017 WARNING 2:7-2:10");
    }

    // ---- values --------------------------------------------------------------------------

    @Test
    void aVectorWrittenWithTooFewNumbersIsAWarningOnTheValue() {
        assertReported("""
                #VRML V2.0 utf8
                Transform {
                  translation 0 0
                }
                """, "VRL2003 WARNING 3:15-3:18");
    }

    @Test
    void aMultipleValueIsCountedByTheWidthOfItsType() {
        // Four numbers are not four wrong values here; they are one list whose last point is short.
        assertReported("""
                #VRML V2.0 utf8
                DEF c Coordinate {
                  point [ 1 2 3 4 ]
                }
                """, "VRL2018 WARNING 2:7-2:17", "VRL2003 WARNING 3:9-3:20");
    }

    @Test
    void oneNumberTooManyForASingleFieldValueIsReportedOnce() {
        // vrml97/script2.wrl writes 'field SFInt32 blah 0 0 0' in three Script bodies; Xj3D's relaxed
        // parser reads the first number and walks over the rest, a browser does not.
        assertReported("""
                #VRML V2.0 utf8
                DEF s Script {
                  field SFInt32 blah 0 0 0
                  url ["x.js"]
                }
                """, "VRL2003 WARNING 3:22-3:27");
    }

    @Test
    void aFractionInAnIntegerFieldIsItsOwnComplaint() {
        assertReported("""
                #VRML V2.0 utf8
                DEF s Script {
                  field SFInt32 n 1.5
                  url ["x.js"]
                }
                """, "VRL2004 WARNING 3:19-3:22");
    }

    @Test
    void aHexLiteralInANonImageFieldIsNotANumber() {
        // Relaxed VRML97 takes 0xRRGGBB for colours and SFImage pixels; a float field gets neither.
        assertReported("""
                #VRML V2.0 utf8
                Box {
                  size 0x10 2 2
                }
                """, "VRL2004 WARNING 3:8-3:12");
    }

    @Test
    void numbersWhereStringsAreWantedSaySoOnce() {
        assertReported("""
                #VRML V2.0 utf8
                WorldInfo {
                  info 3
                }
                """, "VRL2004 WARNING 3:8-3:9");
    }

    // ---- ranges and enumerations ---------------------------------------------------------

    /**
     * The pair that says why the table is keyed on node and field together: 'height' is a cone's
     * extent, which cannot be negative, and an elevation grid's vertex elevations, which are often
     * below the reference plane. Keyed on the name alone, the corpus's nine ElevationGrid files
     * cost 1120 complaints about correct VRML97.
     */
    @Test
    void theSameFieldNameMeansDifferentThingsInDifferentNodes() {
        assertClean("""
                #VRML V2.0 utf8
                ElevationGrid {
                  height [ -1 -2 -3 ]
                }
                """);
        assertReported("""
                #VRML V2.0 utf8
                Shape {
                  geometry Cone {
                    height -1
                  }
                }
                """, "VRL2011 WARNING 4:12-4:14");
    }

    @Test
    void anAngleAndAnIntervalHaveTheirOwnBounds() {
        assertReported("""
                #VRML V2.0 utf8
                ElevationGrid {
                  creaseAngle -1
                }
                """, "VRL2011 WARNING 3:15-3:17");
        assertReported("""
                #VRML V2.0 utf8
                TimeSensor {
                  cycleInterval -1
                }
                """, "VRL2011 WARNING 3:17-3:19");
    }

    @Test
    void aColourComponentIsBoundedWhereverItIsWritten() {
        // No node-by-node list can catch every colour field, and emissiveColor is bounded by the
        // type, not by the node holding it.
        assertReported("""
                #VRML V2.0 utf8
                Shape {
                  appearance Appearance {
                    material Material {
                      emissiveColor 0 0 3
                    }
                  }
                }
                """, "VRL2011 WARNING 5:25-5:26");
    }

    @Test
    void oneBadValueInAFieldCostsOneComplaint() {
        List<Issue> issues = assertReported("""
                #VRML V2.0 utf8
                Shape {
                  appearance Appearance {
                    material Material {
                      diffuseColor -1 -2 0.5
                    }
                  }
                }
                """, "VRL2011 WARNING 5:20-5:22");
        // ... and it names the value that was written first, since that is the one to fix.
        assertEquals("'diffuseColor' must be between 0 and 1; '-1' is not", issues.get(0).message());
    }

    @Test
    void anEnumeratedStringListsWhatItTakes() {
        List<Issue> issues = assertReported("""
                #VRML V2.0 utf8
                Text {
                  fontStyle FontStyle {
                    style "BOLDITALICS"
                  }
                }
                """, "VRL2011 WARNING 4:11-4:24");
        assertEquals("'style' takes one of PLAIN, BOLD, ITALIC, BOLDITALIC; 'BOLDITALICS'"
                + " is not one of them", issues.get(0).message());
    }

    @Test
    void theEnumerationAcceptsTheSpellingTheStandardUses() {
        // ISO 14772 spells the third Fog type "EXponential", which no X3D tool writes; a table that
        // only had LINEAR and EXP would call a correct file wrong.
        assertClean("""
                #VRML V2.0 utf8
                Fog {
                  fogType "EXponential"
                }
                """);
    }

    // ---- where a node may stand ----------------------------------------------------------

    @Test
    void aNodeThatIsNotOneToDisplayIsWarnedAtTheRoot() {
        // 2018 rather than 2001: the type is known, the position is not, and error_handling's
        // non_child_root.wrl is the file that asks for the difference.
        List<Issue> issues = assertReported("""
                #VRML V2.0 utf8
                Material {
                }
                """, "VRL2018 WARNING 2:1-2:9");
        assertEquals("'Material' cannot stand at the root of a file, which holds what a Group holds"
                + " children of; only a node that may appear in a children list belongs here",
                issues.get(0).message());
    }

    @Test
    void aNodeThatHoldsTheSceneIsSilentAtTheRoot() {
        assertClean("""
                #VRML V2.0 utf8
                Shape {
                }
                """);
    }

    @Test
    void aNodeInAFieldThatTakesOtherKindsIsWarned() {
        assertReported("""
                #VRML V2.0 utf8
                Shape {
                  geometry Appearance {
                  }
                }
                """, "VRL2020 WARNING 3:12-3:22");
    }

    @Test
    void aNodeInTheKindOfFieldItBelongsToIsSilent() {
        assertClean("""
                #VRML V2.0 utf8
                Shape {
                  geometry Box {
                  }
                }
                """);
    }

    // ---- DEF, USE ------------------------------------------------------------------------

    @Test
    void aUseOfANameNobodyDefinedIsAnError() {
        assertReported("""
                #VRML V2.0 utf8
                Group {
                  children [
                    USE neverDefined
                  ]
                }
                """, "VRL2006 ERROR 4:9-4:21");
    }

    @Test
    void aUseAboveItsOwnDefIsADifferentMistake() {
        // 2015 rather than 2006, and a different fix: the name is in the file, VRML97 reads a world
        // front to back, so the DEF has to move up rather than be written.
        List<Issue> issues = assertReported("""
                #VRML V2.0 utf8
                Group {
                  children [
                    USE later
                  ]
                }
                DEF later Transform {
                }
                """, "VRL2015 ERROR 4:9-4:14");
        assertTrue(issues.get(0).message().startsWith("'later' is defined below this USE"),
                issues.get(0).message());
    }

    @Test
    void aDefNameUsedTwiceIsNotAMistake() {
        // error_handling/double_def.wrl exists to prove this, and says so in its own comment: X3D
        // requires unique DEF names, VRML97 does not. VRL2005 is therefore unused for good.
        assertClean("""
                #VRML V2.0 utf8
                DEF a Transform {
                }
                DEF a Group {
                  children [ USE a ]
                }
                """);
    }

    @Test
    void aDefInsideAProtoBodyIsNotVisibleOutsideIt() {
        assertReported("""
                #VRML V2.0 utf8
                PROTO Thing [
                  field SFInt32 n 0
                ]
                {
                  DEF inner Transform {
                  }
                }
                Group {
                  children [ USE inner ]
                }
                """, "VRL2006 ERROR 10:18-10:23");
    }

    // ---- ROUTE ---------------------------------------------------------------------------

    @Test
    void aRouteFromANodeNobodyDefinedIsOneError() {
        assertReported("""
                #VRML V2.0 utf8
                DEF a Transform {
                }
                ROUTE missing.translation_changed TO a.set_translation
                """, "VRL2007 ERROR 4:7-4:14");
    }

    @Test
    void aRouteFromANameThatIsNeitherFieldNorEventIsAnError() {
        assertReported("""
                #VRML V2.0 utf8
                DEF a Transform {
                }
                DEF b Transform {
                }
                ROUTE a.nonsense_changed TO b.set_translation
                """, "VRL2008 ERROR 6:9-6:25");
    }

    @Test
    void aRouteReadsFromWhatCanChangeAndWritesToWhatCanTakeAValue() {
        // Both directions at once: 'field' is initializeOnly, so it can neither send nor receive.
        assertReported("""
                #VRML V2.0 utf8
                DEF a Transform {
                }
                DEF b Transform {
                }
                ROUTE a.bboxCenter TO b.translation
                """, "VRL2009 ERROR 6:9-6:19");
        assertReported("""
                #VRML V2.0 utf8
                DEF timer TimeSensor {
                }
                DEF g IndexedFaceSet {
                }
                ROUTE timer.fraction_changed TO g.coordIndex
                """, "VRL2009 ERROR 6:35-6:45");
    }

    @Test
    void theEventsAnExposedFieldDerivesAreRealRouteEndpoints() {
        // The names every working VRML97 file routes by. Xj3D generates set_translation and
        // translation_changed instead of declaring them, so the table has no rows for either and the
        // language rule is what keeps this file quiet.
        assertClean("""
                #VRML V2.0 utf8
                DEF a Transform {
                }
                DEF b Transform {
                }
                ROUTE a.translation_changed TO b.set_translation
                """);
    }

    @Test
    void aDerivedEventInTheWrongDirectionIsStillTheWrongDirection() {
        List<Issue> issues = assertReported("""
                #VRML V2.0 utf8
                DEF a Transform {
                }
                DEF b Transform {
                }
                ROUTE a.set_translation TO b.translation
                """, "VRL2009 ERROR 6:9-6:24");
        // "a eventIn" would be the message a template that guesses the article writes.
        assertEquals("'set_translation' is an eventIn, and a ROUTE can only read from an eventOut"
                + " or exposedField", issues.get(0).message());
    }

    @Test
    void aRouteOnANodeWhoseTypeIsUnknownSaysNothingMore() {
        // The type is already marked; "no such field" about a name no reader resolved would be a
        // second complaint about one typo.
        assertReported("""
                #VRML V2.0 utf8
                DEF a Zorkmid {
                }
                ROUTE a.foo TO a.bar
                """, "VRL2001 ERROR 2:7-2:14");
    }

    @Test
    void aScriptsEventsComeFromWhatTheFileDeclares() {
        assertClean("""
                #VRML V2.0 utf8
                DEF timer TimeSensor {
                }
                DEF s Script {
                  eventIn SFTime go
                  url ["x.js"]
                }
                ROUTE timer.fraction_changed TO s.go
                """);
        assertReported("""
                #VRML V2.0 utf8
                DEF timer TimeSensor {
                }
                DEF s Script {
                  eventIn SFTime go
                  url ["x.js"]
                }
                ROUTE timer.fraction_changed TO s.noGo
                """, "VRL2008 ERROR 8:35-8:39");
    }

    // ---- assignment in a node body ------------------------------------------------------

    @Test
    void anEventDeclaredByNameIsNotAssignable() {
        // error_handling/eventIn_set.wrl: 'set_coordIndex' is a name IndexedFaceSet does have, so
        // 2019 marks it rather than 2002's claim that it does not exist.
        assertReported("""
                #VRML V2.0 utf8
                DEF o OrientationInterpolator {
                  set_fraction 0.5
                }
                """, "VRL2019 ERROR 3:3-3:15");
    }

    @Test
    void anEventOutIsNotReadableEither() {
        assertReported("""
                #VRML V2.0 utf8
                TimeSensor {
                  fraction_changed 0
                }
                """, "VRL2019 ERROR 3:3-3:19");
    }

    @Test
    void anEventAnExposedFieldDerivesIsNotAssignableEither() {
        // The same reasoning as the ROUTE case: set_loop is a real name for TimeSensor's loop field,
        // which is why this is 2019 and not a claim that no such name exists.
        assertReported("""
                #VRML V2.0 utf8
                TimeSensor {
                  set_loop TRUE
                }
                """, "VRL2019 ERROR 3:3-3:11");
    }

    // ---- PROTO ---------------------------------------------------------------------------

    @Test
    void anInstanceTakesItsFieldsFromItsInterface() {
        assertClean("""
                #VRML V2.0 utf8
                PROTO Thing [
                  field SFVec3f offset 0 0 0
                ]
                {
                  Transform {
                    translation IS offset
                  }
                }
                Thing {
                  offset 1 2 3
                }
                """);
        assertReported("""
                #VRML V2.0 utf8
                PROTO Thing [
                  field SFVec3f offset 0 0 0
                ]
                {
                  Transform {
                    translation IS offset
                  }
                }
                Thing {
                  placement 1 2 3
                }
                """, "VRL2002 ERROR 11:3-11:12");
    }

    @Test
    void aProtoInstantiatesWhicheverInterfaceTheNameLastMeant() {
        // Two PROTOs of one name is what vrml97/proto3.wrl and proto7.wrl test, and the language
        // does say which one an instance refers to: the later. So 'n' is no field of Thing here,
        // which is also why 2017's duplicate branch was dropped.
        assertClean("""
                #VRML V2.0 utf8
                PROTO Thing [
                  field SFInt32 n 0
                ]
                {
                  WorldInfo {
                  }
                }
                PROTO Thing [
                  field SFInt32 m 0
                ]
                {
                  WorldInfo {
                  }
                }
                Thing {
                  m 1
                }
                """);
        assertReported("""
                #VRML V2.0 utf8
                PROTO Thing [
                  field SFInt32 n 0
                ]
                {
                  WorldInfo {
                  }
                }
                PROTO Thing [
                  field SFInt32 m 0
                ]
                {
                  WorldInfo {
                  }
                }
                Thing {
                  n 1
                }
                """, "VRL2002 ERROR 17:3-17:4", "VRL3001 HINT 17:3-17:4");
    }

    @Test
    void anInterfaceThatDeclaresOneNameTwiceIsAnError() {
        assertReported("""
                #VRML V2.0 utf8
                PROTO Thing [
                  field SFVec3f offset 0 0 0
                  field SFFloat offset 1
                ]
                {
                  Transform {
                  }
                }
                """, "VRL2013 ERROR 4:17-4:23");
    }

    @Test
    void aFieldTypeOutsideTheTwentyOneIsNamed() {
        assertReported("""
                #VRML V2.0 utf8
                PROTO Thing [
                  field SFnt32 n 0
                ]
                {
                  Transform {
                  }
                }
                """, "VRL2012 ERROR 3:9-3:15");
    }

    @Test
    void anIsClauseOutsideAProtoBodyBindsToNothing() {
        assertReported("""
                #VRML V2.0 utf8
                Transform {
                  translation IS something
                }
                """, "VRL2021 ERROR 3:15-3:27");
    }

    @Test
    void anIsClauseNamingAnUndeclaredFieldIsNamed() {
        assertReported("""
                #VRML V2.0 utf8
                PROTO Thing [
                  field SFVec3f offset 0 0 0
                ]
                {
                  Transform {
                    translation IS nope
                  }
                }
                """, "VRL2014 ERROR 7:20-7:24");
    }

    @Test
    void anIsClauseCannotExposeAnInitializeOnlyFieldAsAnEventIn() {
        assertReported("""
                #VRML V2.0 utf8
                PROTO Thing [
                  eventIn SFVec3f offset
                ]
                {
                  Transform {
                    bboxCenter IS offset
                  }
                }
                """, "VRL2014 ERROR 7:19-7:25");
    }

    @Test
    void aRoutesEndpointOnAProtoInstanceFollowsTheSameEvents() {
        assertClean("""
                #VRML V2.0 utf8
                PROTO Thing [
                  exposedField SFVec3f offset 0 0 0
                ]
                {
                  Transform {
                    translation IS offset
                  }
                }
                DEF a Transform {
                }
                DEF t Thing {
                }
                ROUTE a.translation_changed TO t.set_offset
                """);
        assertReported("""
                #VRML V2.0 utf8
                PROTO Thing [
                  exposedField SFVec3f offset 0 0 0
                ]
                {
                  Transform {
                    translation IS offset
                  }
                }
                Thing {
                  set_offset 1 2 3
                }
                """, "VRL2019 ERROR 11:3-11:13");
    }

    // ---- what is not judged --------------------------------------------------------------

    @Test
    void aStatementTheParserRepairedIsNotAlsoJudged() {
        // One missing ']' makes every name after it look wrong, and the structural code already
        // points at the bracket. vrml97/import.wrl is the corpus case this gate exists for: X3D's
        // IMPORT reads as a node type, and the 2001 that would follow is a lie about the file.
        assertNoSemanticVerdict("""
                #VRML V2.0 utf8
                Group {
                  children [
                    Zorkmid {
                }
                """);
        assertNoSemanticVerdict("""
                #VRML V2.0 utf8
                Box {
                  size 1 2 three
                }
                """);
    }

    @Test
    void anX3DDocumentIsNotJudgedAgainstTheVrml97Table() {
        // events/boolean_filter.wrl and nurbs/simple_nurbssurface.wrl are X3D wearing a .wrl name;
        // their nodes are not VRML97's, and 3005 on the header is the answer, not forty unknown names.
        assertNoSemanticVerdict("""
                #X3D V3.0 utf8
                PROFILE Immersive
                Shape {
                  geometry Box {
                  }
                  apperance Appearance {
                  }
                }
                """);
    }

    // ---- severity ------------------------------------------------------------------------

    /**
     * The split Xj3D draws, in one file: a name no reader can resolve, or a statement that cannot
     * mean what it says, is an ERROR; a value a browser can talk its way past is a WARNING. The
     * distinction decides whether M6's formatter will touch the document at all.
     */
    @Test
    void namesAreErrorsAndValuesAreWarnings() {
        Map<Integer, Severity> byCode = new HashMap<>();
        for (Issue issue : check("""
                #VRML V2.0 utf8
                Zorkmid {
                }
                Shape {
                  apperance Appearance {}
                }
                DEF t Transform {
                  translation 0 0
                }
                Group {
                  children [ USE nowhere ]
                }
                Shape {
                  geometry Appearance {
                  }
                }
                ROUTE t.nonsense TO t.translation
                """)) {
            byCode.put(issue.code(), issue.severity());
        }
        assertEquals(Severity.ERROR, byCode.get(Codes.UNKNOWN_NODE_TYPE), "2001");
        assertEquals(Severity.ERROR, byCode.get(Codes.UNKNOWN_FIELD), "2002");
        assertEquals(Severity.WARNING, byCode.get(Codes.FIELD_VALUE_COUNT), "2003");
        assertEquals(Severity.ERROR, byCode.get(Codes.UNDEFINED_USE), "2006");
        assertEquals(Severity.ERROR, byCode.get(Codes.ROUTE_UNKNOWN_FIELD), "2008");
        assertEquals(Severity.WARNING, byCode.get(Codes.NODE_KIND_NOT_ACCEPTED), "2020");
        assertEquals(Severity.HINT, byCode.get(Codes.NAME_SUGGESTION), "3001");
    }
}
