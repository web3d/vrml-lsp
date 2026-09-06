package org.vrml.spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.parser.VrmlParser;

/**
 * What the 263-file test corpus actually writes, as a check on the other three sources.
 *
 * <p>Neither Xj3D's field arrays nor UOM's tables were written against a list of VRML97 field
 * names, and both are known to disagree with each other. The corpus is the one local artefact made
 * of real {@code .wrl} files, so a name that appears there and not in the generated table is a
 * hole in the table, and a name in the table that only ever appears in the corpus's {@code #X3D}
 * files is a foreigner. Files headed {@code #X3D} are counted separately for exactly that reason -
 * Xj3D's parsetest keeps both dialects, and their evidence points opposite ways.
 *
 * <p>Parse failures are not an error here: the corpus deliberately contains broken files, and the
 * fields that were recognised before the break still count as evidence.
 */
final class CorpusUsage {

    /** node type name to field name to occurrences; a missing entry means "never seen". */
    private final Map<String, Map<String, Integer>> vrmlFields;
    private final Map<String, Map<String, Integer>> x3dFields;
    private final Map<String, Integer> vrmlNodes;
    private final int vrmlFiles;
    private final int x3dFiles;

    private CorpusUsage(Map<String, Map<String, Integer>> vrmlFields,
                        Map<String, Map<String, Integer>> x3dFields,
                        Map<String, Integer> vrmlNodes, int vrmlFiles, int x3dFiles) {
        this.vrmlFields = vrmlFields;
        this.x3dFields = x3dFields;
        this.vrmlNodes = vrmlNodes;
        this.vrmlFiles = vrmlFiles;
        this.x3dFiles = x3dFiles;
    }

    static CorpusUsage read(Path parsetestDir) throws SpecGenException {
        Map<String, Map<String, Integer>> vrml = new HashMap<>();
        Map<String, Map<String, Integer>> x3d = new HashMap<>();
        Map<String, Integer> nodes = new HashMap<>();
        int vrmlFileCount = 0;
        int x3dFileCount = 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(parsetestDir)) {
            files = walk.filter(path -> path.getFileName().toString().endsWith(".wrl"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new SpecGenException("cannot list " + parsetestDir + ": " + e.getMessage(), e);
        }
        for (Path file : files) {
            String text;
            try {
                // ISO-8859-1 again: a handful of corpus files are not valid UTF-8, and field names
                // are ASCII in every dialect, so decoding can never invent an identifier.
                text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            } catch (IOException e) {
                throw new SpecGenException("cannot read " + file + ": " + e.getMessage(), e);
            }
            boolean isX3d = firstLine(text).startsWith("#X3D");
            if (isX3d) {
                x3dFileCount++;
            } else {
                vrmlFileCount++;
            }
            harvest(VrmlParser.parse(text), isX3d ? x3d : vrml, isX3d ? null : nodes);
        }
        if (vrmlFileCount == 0) {
            throw new SpecGenException(parsetestDir + ": no .wrl files to learn from");
        }
        return new CorpusUsage(vrml, x3d, nodes, vrmlFileCount, x3dFileCount);
    }

    /** How often {@code nodeName} was written in a {@code #VRML}-headed corpus file. */
    int vrmlNodeOccurrences(String nodeName) {
        return vrmlNodes.getOrDefault(nodeName, 0);
    }

    /** Occurrences of {@code fieldName} inside {@code nodeName} in VRML-headed files. */
    int vrmlFieldOccurrences(String nodeName, String fieldName) {
        return occurrences(vrmlFields, nodeName, fieldName);
    }

    /** The same, in the corpus's {@code #X3D}-headed files. */
    int x3dFieldOccurrences(String nodeName, String fieldName) {
        return occurrences(x3dFields, nodeName, fieldName);
    }

    /** Field names the VRML-headed corpus uses inside {@code nodeName}, whatever the table says. */
    List<String> vrmlFieldNames(String nodeName) {
        Map<String, Integer> fields = vrmlFields.get(nodeName);
        return fields == null ? List.of() : fields.keySet().stream().sorted().toList();
    }

    int vrmlFileCount() {
        return vrmlFiles;
    }

    int x3dFileCount() {
        return x3dFiles;
    }

    private static int occurrences(Map<String, Map<String, Integer>> byNode, String nodeName,
                                   String fieldName) {
        Map<String, Integer> fields = byNode.get(nodeName);
        return fields == null ? 0 : fields.getOrDefault(fieldName, 0);
    }

    private static void harvest(ParseResult parsed, Map<String, Map<String, Integer>> byNode,
                                Map<String, Integer> nodeCounts) {
        walk(parsed, parsed.root(), byNode, nodeCounts);
    }

    private static void walk(ParseResult parsed, CstNode node,
                             Map<String, Map<String, Integer>> byNode,
                             Map<String, Integer> nodeCounts) {
        if (node.kind() == CstKind.NODE) {
            CstNode type = node.firstChildOf(CstKind.NODE_TYPE);
            CstNode body = node.firstChildOf(CstKind.NODE_BODY);
            if (type != null && body != null) {
                String typeName = parsed.text(type);
                if (nodeCounts != null) {
                    nodeCounts.merge(typeName, 1, Integer::sum);
                }
                for (CstNode assignment : body.childrenOf(CstKind.FIELD_ASSIGNMENT)) {
                    CstNode name = assignment.firstChildOf(CstKind.FIELD_NAME);
                    if (name != null) {
                        byNode.computeIfAbsent(typeName, key -> new HashMap<>())
                                .merge(parsed.text(name), 1, Integer::sum);
                    }
                }
            }
        }
        for (int i = 0; i < node.childCount(); i++) {
            walk(parsed, node.child(i), byNode, nodeCounts);
        }
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        parts.add(vrmlFiles + " VRML files");
        parts.add(x3dFiles + " X3D files");
        return String.join(", ", parts);
    }
}
