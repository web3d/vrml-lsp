package org.vrml.spec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The three-source join, in the direction the plan fixed: Xj3D decides which names exist, UOM
 * decides what they mean.
 *
 * <p>Xj3D's {@code fieldDecl} arrays and the {@code fieldMap} beside them are the set of names a
 * {@code .wrl} file can actually use, and they are the only source that is not written for XML.
 * UOM is the only source holding a default value, a sentence of documentation, or a constraint on
 * which nodes an {@code SFNode} may name. So a row is built per Xj3D declaration and enriched
 * from UOM; a UOM field Xj3D never declares is not in the table at all, but is reported, because
 * that is where X3D's renames of VRML97 names show up.
 */
final class Join {

    /** The joined table plus everything a person should look at. */
    record Result(List<NodeRow> nodes, Report report) {
    }

    /**
     * UOM's name for the one thing Xj3D and UOM both must agree on for a row to make sense.
     *
     * <p>{@code containerField} is an XML attribute, {@code DEF}/{@code USE} are statements, and
     * {@code class}/{@code id}/{@code style} are XML styling attributes; none of them can be written
     * in a {@code .wrl} file as a field of a node.
     */
    private static final String X3D_NODE_BASE = "X3DNode";

    private final UomReader uom;
    private final CorpusUsage corpus;
    private final Report report = new Report();
    private final List<String> vrml97Names;

    Join(UomReader uom, CorpusUsage corpus, List<String> vrml97Names) {
        this.uom = uom;
        this.corpus = corpus;
        this.vrml97Names = vrml97Names;
    }

    Result run(List<ProfilesReader.NodeEntry> entries,
               Map<String, Xj3dFieldReader.Fields> declarations) throws SpecGenException {
        List<NodeRow> rows = new ArrayList<>();
        for (ProfilesReader.NodeEntry entry : entries) {
            rows.add(node(entry, declarations.get(entry.name())));
        }
        rows.sort(Comparator.comparing(NodeRow::name));
        return new Result(List.copyOf(rows), report);
    }

    private NodeRow node(ProfilesReader.NodeEntry entry, Xj3dFieldReader.Fields fields)
            throws SpecGenException {
        Optional<UomReader.Node> matched = uom.node(entry.name());
        if (matched.isEmpty()) {
            report.section("nodes missing from UOM").add(entry.name()
                    + ": no <ConcreteNode> in UOM 4.1, so no defaults or descriptions");
        }
        List<FieldRow> declared = new ArrayList<>();
        for (Xj3dFieldReader.Decl decl : fields.decls()) {
            FieldRow row = field(entry.name(), decl, matched);
            if (row == null) {
                continue;
            }
            if (!FieldTypes.isKnown(row.type())) {
                throw new SpecGenException(entry.name() + "." + row.name() + ": type '"
                        + row.type() + "' is not a VRML97 field type");
            }
            declared.add(row);
            for (String alias : row.aliases()) {
                report.section("names a field also answers to")
                        .add(entry.name() + "." + alias + " is " + row.name() + " (" + row.type()
                                + ")" + corpusEvidence(entry.name(), alias));
            }
        }
        for (String unexplained : fields.unexplained()) {
            report.section("fieldMap names no declaration explains")
                    .add(entry.name() + "." + unexplained + corpusEvidence(entry.name(),
                            unexplained));
        }
        if (declared.isEmpty()) {
            throw new SpecGenException(entry.name() + ": every field Xj3D declares was filtered"
                    + " out, leaving a node with nothing to complete");
        }
        if (matched.isPresent()) {
            missingFromXj3d(entry.name(), matched.get(), declared);
        }
        return new NodeRow(entry.name(), entry.component(), entry.level(),
                matched.map(UomReader.Node::summary).orElse(""),
                matched.map(UomReader.Node::specificationUrl).orElse(""), declared);
    }

    /**
     * One Xj3D declaration, enriched - or {@code null} when it is an XML-era field that cannot be
     * written in a {@code .wrl} file.
     */
    private FieldRow field(String nodeName, Xj3dFieldReader.Decl decl,
                           Optional<UomReader.Node> matched) {
        Optional<UomReader.Field> uomField = matched.flatMap(node -> node.field(decl.name()));
        if (X3D_NODE_BASE.equals(uomField.map(UomReader.Field::inheritedFrom).orElse(null))) {
            // IS, metadata, DEF, USE and friends: part of every X3D node, none of them a field a
            // VRML97 scene file can name. This is the plan's one automatic exclusion.
            report.section("x3d-only fields dropped")
                    .add(nodeName + "." + decl.name() + " (" + decl.type() + ")");
            return null;
        }
        if (uomField.isEmpty()) {
            report.section("fields UOM does not list")
                    .add(nodeName + "." + decl.name() + " " + decl.type() + " " + decl.access()
                            + corpusEvidence(nodeName, decl.name()));
        } else {
            accessConflict(nodeName, decl, uomField.get());
        }
        UomReader.Field uomFieldOrNone = uomField.orElse(null);
        return new FieldRow(decl.name(), decl.type(), decl.access(),
                uomFieldOrNone == null || uomFieldOrNone.defaultValue() == null
                        ? "" : uomFieldOrNone.defaultValue(),
                uomFieldOrNone == null || uomFieldOrNone.description() == null
                        ? "" : uomFieldOrNone.description(),
                childrenOf(nodeName, decl, uomFieldOrNone), decl.aliases());
    }

    /** Where the two sources disagree about how a field may be touched, which is a real conflict. */
    private void accessConflict(String nodeName, Xj3dFieldReader.Decl decl,
                                UomReader.Field uomField) {
        String expected = accessOfUom(uomField.accessType());
        if (expected == null || expected.equals(decl.access())) {
            return;
        }
        report.section("access disagreements").add(nodeName + "." + decl.name() + ": Xj3D says "
                + decl.access() + ", UOM says " + uomField.accessType() + " (=" + expected + ")");
    }

    private static String accessOfUom(String accessType) {
        if (accessType == null) {
            return null;
        }
        return switch (accessType) {
            case "inputOutput" -> "exposedField";
            case "initializeOnly" -> "field";
            case "inputOnly" -> "eventIn";
            case "outputOnly" -> "eventOut";
            default -> null;
        };
    }

    /**
     * Abstract X3D types that VRML97 reads as subtypes of a wider one.
     *
     * <p>UOM describes X3D 4, which moved geometry inside {@code Shape} and re-parented
     * {@code X3DGeometryNode} straight off {@code X3DNode}, so expanding {@code X3DChildNode} alone
     * loses every geometry node from {@code children}. {@code children [ Box { } ]} is legal VRML97 -
     * it is how the standard's own examples draw - so the geometry members join a child list here.
     */
    private static final Map<String, String> VRML97_SUBTYPE_OF = Map.of(
            "X3DGeometryNode", "X3DChildNode");

    /**
     * Nodes VRML97 allows in a slot that UOM no longer classifies as one.
     *
     * <p>{@code MovieTexture} is one of VRML97's three texture nodes - ISO 14772-1 6.11.4 puts it in
     * {@code Appearance.texture} - while X3D 4 lists it only as a {@code X3DSoundSourceNode}, having
     * replaced video texturing with {@code MovieTextures}. Expanding UOM's list alone therefore drops
     * a node the language allows, in the same way its re-parented geometry does above.
     */
    private static final Map<String, List<String>> VRML97_SLOT_MEMBERS = Map.of(
            "X3DTextureNode", List.of("MovieTexture"));

    /**
     * The concrete VRML97 nodes {@code field} may hold, resolved through UOM's inheritance graph.
     *
     * <p>{@code acceptableNodeTypes} names abstract X3D types - {@code X3DGeometryNode} is not
     * something a user types - so each name is expanded to the profile nodes that inherit it. That
     * keeps the child list honest as the sources change: a node joins {@code children}'s list
     * because UOM says it is an {@code X3DChildNode}, not because someone remembered to edit a
     * table here.
     */
    private List<String> childrenOf(String nodeName, Xj3dFieldReader.Decl decl,
                                    UomReader.Field uomField) {
        if (!decl.type().equals("SFNode") && !decl.type().equals("MFNode")) {
            return List.of();
        }
        if (uomField == null || uomField.acceptableNodeTypes().isEmpty()) {
            report.section("SFNode fields without a candidate list")
                    .add(nodeName + "." + decl.name() + " (" + decl.type() + ")");
            return List.of();
        }
        TreeSet<String> members = new TreeSet<>();
        for (String accepted : uomField.acceptableNodeTypes()) {
            if (vrml97Names.contains(accepted)) {
                members.add(accepted);
                continue;
            }
            List<String> inherited = membersOf(accepted);
            if (inherited.isEmpty()) {
                report.section("unresolvable acceptableNodeTypes")
                        .add(nodeName + "." + decl.name() + ": " + accepted
                                + " matches none of the " + vrml97Names.size() + " profile nodes");
                continue;
            }
            members.addAll(inherited);
            members.addAll(VRML97_SLOT_MEMBERS.getOrDefault(accepted, List.of()));
            for (Map.Entry<String, String> vrml97 : VRML97_SUBTYPE_OF.entrySet()) {
                if (vrml97.getValue().equals(accepted)) {
                    members.addAll(membersOf(vrml97.getKey()));
                }
            }
        }
        // The node itself stays in its own list on purpose: `Transform { children [ Transform { } ] }`
        // is legal VRML97 and is in ten corpus files, so dropping it would hide the commonest edit.
        return List.copyOf(members);
    }

    private List<String> membersOf(String abstractType) {
        return vrml97Names.stream()
                .filter(name -> uom.typeClosure(name).contains(abstractType))
                .toList();
    }

    /** UOM knows fields Xj3D does not declare; each one is either an X3D addition or a rename. */
    private void missingFromXj3d(String nodeName, UomReader.Node uomNode, List<FieldRow> fields) {
        List<String> present = fields.stream().map(FieldRow::name).toList();
        for (UomReader.Field uomField : uomNode.fields()) {
            if (X3D_NODE_BASE.equals(uomField.inheritedFrom()) || present.contains(uomField.name())
                    || "containerField".equals(uomField.name())) {
                continue;
            }
            report.section("UOM fields Xj3D never declares")
                    .add(nodeName + "." + uomField.name() + " " + uomField.type() + " "
                            + uomField.accessType() + corpusEvidence(nodeName, uomField.name()));
        }
    }

    private String corpusEvidence(String nodeName, String fieldName) {
        return " | corpus: " + corpus.vrmlFieldOccurrences(nodeName, fieldName)
                + " in .wrl as VRML, " + corpus.x3dFieldOccurrences(nodeName, fieldName)
                + " as X3D";
    }
}
