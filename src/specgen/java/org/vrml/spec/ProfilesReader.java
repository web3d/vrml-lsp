package org.vrml.spec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The VRML97 node list, as Xj3D itself defines it.
 *
 * <p>{@code config/2.0/profiles.xml} groups every node Xj3D can instantiate by ISO component and
 * component level, which is both the set of node names the server should complete and the group
 * heading hover can show. It is XML but flat enough to read with StAX rather than a DOM: the file
 * has exactly three interesting elements, and the only structure that matters is "which component
 * and level was I inside when I met this {@code <node>}".
 *
 * <p>The renderer-specific {@code <nodeLocation>} entries and the {@code <profileConfig>} section
 * are deliberately ignored: the former names Java packages, which says nothing about the language,
 * and the latter repeats component names already read per node.
 */
final class ProfilesReader {

    /** One {@code <node name>} with the component and level that contained it. */
    record NodeEntry(String name, String component, int level) {
    }

    private ProfilesReader() {
    }

    /**
     * Reads every {@code <node name=.../>} in document order.
     *
     * @throws SpecGenException if the file has no nodes, repeats one, or nests a node outside any
     *         component/level, which would leave the output with nothing to say about it
     */
    static List<NodeEntry> read(Path profilesXml) throws SpecGenException {
        List<NodeEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String component = null;
        int level = -1;
        try (InputStream in = Files.newInputStream(profilesXml)) {
            XMLStreamReader xml = newInputFactory().createXMLStreamReader(in);
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.END_ELEMENT) {
                    // Levels nest inside components; leaving either invalidates what a later
                    // <node> would otherwise inherit from a stale attribute.
                    String closed = xml.getLocalName();
                    if ("component".equals(closed)) {
                        component = null;
                    } else if ("componentLevel".equals(closed)) {
                        level = -1;
                    }
                    continue;
                }
                if (event != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (xml.getLocalName()) {
                    case "component" -> {
                        String name = xml.getAttributeValue(null, "name");
                        // <component> appears twice: as a definition here, as a level-less
                        // cross-reference in <profileConfig>. Only the definition has a title.
                        if (xml.getAttributeValue(null, "levels") != null) {
                            component = require(name, "component name", profilesXml);
                        }
                    }
                    case "componentLevel" -> level = parseLevel(
                            require(xml.getAttributeValue(null, "level"), "componentLevel level",
                                    profilesXml),
                            profilesXml);
                    case "node" -> {
                        String name = require(xml.getAttributeValue(null, "name"), "node name",
                                profilesXml);
                        if (!seen.add(name)) {
                            throw new SpecGenException(profilesXml + ": node '" + name
                                    + "' is listed twice; the spec table cannot hold two entries"
                                    + " for one name");
                        }
                        if (component == null || level < 0) {
                            throw new SpecGenException(profilesXml + ": node '" + name
                                    + "' is not inside a <component>/<componentLevel>, so it has no"
                                    + " component to report");
                        }
                        entries.add(new NodeEntry(name, component, level));
                    }
                    default -> {
                    }
                }
            }
            xml.close();
        } catch (IOException | XMLStreamException e) {
            throw new SpecGenException("cannot read " + profilesXml + ": " + e.getMessage(), e);
        }
        if (entries.isEmpty()) {
            throw new SpecGenException(profilesXml + ": no <node name=...> entries at all");
        }
        return List.copyOf(entries);
    }

    private static XMLInputFactory newInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // The files are read from a checkout, but never trust a specification document enough to
        // fetch external entities on its behalf.
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        return factory;
    }

    private static String require(String value, String what, Path file) throws SpecGenException {
        if (value == null || value.isBlank()) {
            throw new SpecGenException(file + ": expected a " + what);
        }
        return value;
    }

    private static int parseLevel(String value, Path file) throws SpecGenException {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new SpecGenException(file + ": level '" + value + "' is not a number", e);
        }
    }
}
