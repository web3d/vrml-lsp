package org.vrml.spec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The X3D Unified Object Model, as a lookup table of defaults and prose.
 *
 * <p>UOM 4.1 is the only one of the three sources that records a node's <em>default values</em> and
 * the sentence explaining what each field is for, and it is the only source that knows which
 * abstract type an {@code SFNode} field accepts. It is also X3D: node names VRML97 spelled
 * differently are renamed here, and the component levels describe X3D's own growth rather than the
 * 1997 language. So this class contributes descriptions, defaults and constraints; the decision
 * about which names exist in a {@code .wrl} file is never taken from it.
 *
 * <p>Only {@code <ConcreteNode>} entries are kept as nodes. {@code <AbstractNodeType>} and
 * {@code <AbstractObjectType>} are read for one thing only - their {@code <Inheritance baseType>} -
 * so that "is this node an X3DChildNode" can be answered by walking the graph instead of by a
 * hand-maintained list.
 */
final class UomReader {

    /** One {@code <field>} of a concrete node. */
    record Field(String name, String type, String accessType, String defaultValue,
                 List<String> acceptableNodeTypes, String inheritedFrom, String description,
                 String raisedComponent, Integer raisedLevel) {
    }

    /** One {@code <ConcreteNode>}, with the fields it declares itself plus inherited ones. */
    record Node(String name, String baseType, String component, int level, String specificationUrl,
                String summary, List<Field> fields, String containerFieldDefault) {

        Optional<Field> field(String fieldName) {
            return fields.stream().filter(f -> f.name().equals(fieldName)).findFirst();
        }
    }

    private final Map<String, Node> nodes;
    private final Map<String, String> parents;

    private UomReader(Map<String, Node> nodes, Map<String, String> parents) {
        this.nodes = nodes;
        this.parents = parents;
    }

    static UomReader read(Path uomXml) throws SpecGenException {
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, String> parents = new HashMap<>();
        try (InputStream in = Files.newInputStream(uomXml)) {
            new UomReader(nodes, parents).parse(in, uomXml);
        } catch (IOException | XMLStreamException e) {
            throw new SpecGenException("cannot read " + uomXml + ": " + e.getMessage(), e);
        }
        if (nodes.isEmpty()) {
            throw new SpecGenException(uomXml + ": no <ConcreteNode> entries at all");
        }
        return new UomReader(nodes, parents);
    }

    Optional<Node> node(String name) {
        return Optional.ofNullable(nodes.get(name));
    }

    /** Whether UOM describes this node at all, which is what makes its defaults available. */
    boolean nodePresent(String name) {
        return nodes.containsKey(name);
    }

    /**
     * {@code name} and every type it inherits from, transitively, including {@code X3DNode}.
     *
     * <p>Used to answer {@code acceptableNodeTypes="X3DChildNode"} for {@code MFNode children}:
     * UOM never lists the concrete nodes of an abstract type, but the inheritance edges are all
     * here, so the members can be derived rather than guessed.
     */
    Set<String> typeClosure(String name) {
        Set<String> closure = new LinkedHashSet<>();
        String current = name;
        // The graph is a tree up to X3DNode, so the loop only has to guard against a malformed
        // file that points a type at itself.
        while (current != null && closure.add(current)) {
            current = parents.get(current);
        }
        return closure;
    }

    private void parse(InputStream in, Path file) throws XMLStreamException, SpecGenException {
        XMLStreamReader xml = newInputFactory().createXMLStreamReader(in);
        // Which node is being read, and in what role: a concrete node's fields go into the table,
        // an abstract type's only contribute an inheritance edge.
        String nodeName = null;
        boolean concrete = false;
        String abstractName = null;
        List<Field> fields = null;
        String nodeComponent = null;
        int nodeLevel = -1;
        String nodeUrl = null;
        String nodeSummary = null;
        String nodeContainerField = null;
        FieldBuilder pending = null;

        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String element = xml.getLocalName();
                switch (element) {
                    case "ConcreteNode" -> {
                        nodeName = attribute(xml, "name", file);
                        concrete = true;
                        fields = new ArrayList<>();
                        nodeComponent = null;
                        nodeLevel = -1;
                        nodeUrl = xml.getAttributeValue(null, "specificationUrl");
                        nodeSummary = null;
                        nodeContainerField = null;
                    }
                    case "AbstractNodeType", "AbstractObjectType" -> {
                        abstractName = attribute(xml, "name", file);
                    }
                    case "InterfaceDefinition" -> {
                        if (nodeName != null) {
                            nodeUrl = xml.getAttributeValue(null, "specificationUrl");
                            nodeSummary = xml.getAttributeValue(null, "appinfo");
                        }
                    }
                    case "Inheritance" -> {
                        String base = xml.getAttributeValue(null, "baseType");
                        if (base == null) {
                            continue;
                        }
                        if (nodeName != null) {
                            parents.put(nodeName, base);
                        } else if (abstractName != null) {
                            parents.put(abstractName, base);
                        }
                    }
                    case "componentInfo" -> {
                        String component = xml.getAttributeValue(null, "name");
                        Integer level = intAttribute(xml, "level");
                        if (component == null || level == null) {
                            continue;
                        }
                        if (pending != null) {
                            // A <field> that repeats componentInfo is saying it belongs to a higher
                            // level of the same component than its node does.
                            pending.raisedComponent = component;
                            pending.raisedLevel = level;
                        } else if (nodeName != null && nodeComponent == null) {
                            nodeComponent = component;
                            nodeLevel = level;
                        }
                    }
                    case "field" -> {
                        if (concrete) {
                            pending = new FieldBuilder();
                            pending.name = attribute(xml, "name", file);
                            pending.type = xml.getAttributeValue(null, "type");
                            pending.accessType = xml.getAttributeValue(null, "accessType");
                            pending.defaultValue = xml.getAttributeValue(null, "default");
                            pending.inheritedFrom = xml.getAttributeValue(null, "inheritedFrom");
                            pending.description = xml.getAttributeValue(null, "description");
                            String accepts = xml.getAttributeValue(null, "acceptableNodeTypes");
                            if (accepts != null && !accepts.isBlank()) {
                                // UOM separates several accepted types with '|', not with spaces:
                                // "X3DSingleTextureCoordinateNode|MultiTextureCoordinate" is two
                                // names, and reading it as one silently loses the candidate list
                                // (IndexedFaceSet.texCoord was the proof).
                                pending.acceptable = List.of(accepts.trim().split("[|\\s]+"));
                            }
                        }
                    }
                    case "containerField" -> {
                        if (concrete) {
                            nodeContainerField = xml.getAttributeValue(null, "default");
                        }
                    }
                    default -> {
                    }
                }
                continue;
            }
            if (event != XMLStreamConstants.END_ELEMENT) {
                continue;
            }
            switch (xml.getLocalName()) {
                case "field" -> {
                    if (pending != null) {
                        fields.add(pending.build());
                        pending = null;
                    }
                }
                case "ConcreteNode" -> {
                    if (nodeComponent == null || nodeLevel < 0) {
                        throw new SpecGenException(file + ": node " + nodeName
                                + " has no <componentInfo name= level=>, so it cannot be placed in"
                                + " a component");
                    }
                    nodes.put(nodeName, new Node(nodeName, parents.get(nodeName), nodeComponent,
                            nodeLevel, nodeUrl, nodeSummary, List.copyOf(fields),
                            nodeContainerField));
                    nodeName = null;
                    concrete = false;
                    fields = null;
                }
                case "AbstractNodeType", "AbstractObjectType" -> abstractName = null;
                default -> {
                }
            }
        }
        xml.close();
    }

    /** Mutable while a {@code <field>} is open, so that nested elements can add to it. */
    private static final class FieldBuilder {

        private String name;
        private String type;
        private String accessType;
        private String defaultValue;
        private String inheritedFrom;
        private String description;
        private List<String> acceptable = List.of();
        private String raisedComponent;
        private Integer raisedLevel;

        Field build() {
            return new Field(name, type, accessType, defaultValue, acceptable, inheritedFrom,
                    description, raisedComponent, raisedLevel);
        }
    }

    private static String attribute(XMLStreamReader xml, String name, Path file)
            throws SpecGenException {
        String value = xml.getAttributeValue(null, name);
        if (value == null || value.isBlank()) {
            throw new SpecGenException(file + ": <" + xml.getLocalName() + "> without a " + name);
        }
        return value;
    }

    private static Integer intAttribute(XMLStreamReader xml, String name) {
        String value = xml.getAttributeValue(null, name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static XMLInputFactory newInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        return factory;
    }
}
