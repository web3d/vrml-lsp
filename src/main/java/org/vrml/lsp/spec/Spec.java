package org.vrml.lsp.spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The table of VRML97 nodes, fields and field types the server answers questions from.
 *
 * <p>Read-only data built offline by {@code org.vrml.spec.SpecGenMain} ({@code mvn -Pspecgen}) from
 * Xj3D's own node classes plus the X3D Unified Object Model, and shipped inside the jar as
 * {@value #RESOURCE}. Nothing here is inferred at runtime: an unknown node name is a fact about the
 * file being edited, not a gap in this table, and the difference matters to the diagnostics.
 *
 * <p>The field lists carry names a VRML97 scene file can actually write, including the two the X3D
 * renames would otherwise have lost ({@code LOD.level}, {@code Switch.choice}) - see
 * {@link Field#aliases()}. Everything the three sources disagreed about is in the generated
 * {@code vrml97-vs-uom-report.txt} next to it, not hidden in here.
 */
public final class Spec {

    /** Where the table lives in the jar, relative to the classpath root. */
    public static final String RESOURCE = "/spec/vrml97-spec.json";

    /** The only schema this reader understands; a newer file says so rather than mis-parsing. */
    private static final String SUPPORTED_VERSION = "1";

    /** One node: where it belongs in the spec, and what may be written inside it. */
    public record Node(String name, String component, int level, String summary, String specUrl,
                       List<Field> fields) {

        /**
         * The field a scene file names, whether it used the declared name or one of the aliases the
         * parser also accepts.
         */
        public Optional<Field> field(String wanted) {
            return fields.stream().filter(field -> field.answersTo(wanted)).findFirst();
        }
    }

    /**
     * One field, as generated: name and type from Xj3D, meaning from UOM.
     *
     * <p>{@code defaultValue} and {@code doc} are UOM's own text, unedited - which keeps X3D's
     * spellings, so {@code creaseAngle} defaults to {@code "0"} where {@code maxExtent} defaults
     * to {@code "0.0"}. An empty {@code defaultValue} means UOM wrote no {@code default}
     * attribute, which for an {@code MF*} field is how it says "no values".
     */
    public record Field(String name, String type, String access, String defaultValue, String doc,
                        List<String> childNodes, List<String> aliases) {

        /** Whether a scene file may write {@code candidate} for this field. */
        public boolean answersTo(String candidate) {
            return name.equals(candidate) || aliases.contains(candidate);
        }

        /** Whether this is one of the two node-holding types, whose values are nodes. */
        public boolean holdsNodes() {
            return type.equals("SFNode") || type.equals("MFNode");
        }
    }

    /**
     * One of the 21 field types, with what completion needs: how many numbers one value takes and
     * the snippet to insert.
     */
    public record ValueType(String name, int width, String snippet) {
    }

    private final Map<String, Node> nodes;
    private final Map<String, ValueType> types;

    private Spec(Map<String, Node> nodes, Map<String, ValueType> types) {
        // Unmodifiable rather than Map.copyOf: copyOf reshuffles, and the generated file's order -
        // nodes by name, the 11 SF types before the 10 MF ones - is what makes --spec-dump and the
        // completion lists come out the same way every run.
        this.nodes = Collections.unmodifiableMap(nodes);
        this.types = Collections.unmodifiableMap(types);
    }

    /**
     * The table from this jar's own resources.
     *
     * @throws IllegalStateException if the resource is missing or unreadable, which is a packaging
     *         bug rather than a user error: the fix is {@code mvn -Pspecgen} and a rebuild
     */
    public static Spec load() {
        try (InputStream in = Spec.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("no " + RESOURCE + " on the classpath; build it with"
                        + " `mvn -Pspecgen` before packaging");
            }
            return read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }

    /** The same table from text, so a test can pin a few rows without touching the real file. */
    public static Spec read(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String version = string(root, "version");
        if (!SUPPORTED_VERSION.equals(version)) {
            throw new IllegalStateException("spec version '" + version + "' is not understood by"
                    + " this build, which reads version " + SUPPORTED_VERSION);
        }
        return new Spec(nodesOf(root.getAsJsonArray("nodes")),
                typesOf(root.getAsJsonObject("valueTemplates")));
    }

    private static Map<String, Node> nodesOf(JsonArray array) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (JsonElement element : array) {
            JsonObject json = element.getAsJsonObject();
            List<Field> fields = new ArrayList<>();
            for (JsonElement field : json.getAsJsonArray("fields")) {
                fields.add(fieldOf(field.getAsJsonObject()));
            }
            Node node = new Node(string(json, "name"), string(json, "component"),
                    json.get("level").getAsInt(), string(json, "summary"), string(json, "specUrl"),
                    List.copyOf(fields));
            nodes.put(node.name(), node);
        }
        return nodes;
    }

    private static Field fieldOf(JsonObject json) {
        return new Field(string(json, "name"), string(json, "type"), string(json, "access"),
                string(json, "default"), string(json, "doc"), strings(json, "childNodes"),
                strings(json, "aliases"));
    }

    private static Map<String, ValueType> typesOf(JsonObject templates) {
        Map<String, ValueType> types = new LinkedHashMap<>();
        for (String name : templates.keySet()) {
            JsonObject one = templates.get(name).getAsJsonObject();
            types.put(name, new ValueType(name, one.get("width").getAsInt(),
                    string(one, "snippet")));
        }
        return types;
    }

    /** Every node in the table, in the generated order: alphabetical by node name. */
    public Collection<Node> nodes() {
        return nodes.values();
    }

    public Optional<Node> node(String name) {
        return Optional.ofNullable(nodes.get(name));
    }

    /** Whether {@code name} is a node this server knows; used to decide "unknown node" errors. */
    public boolean hasNode(String name) {
        return nodes.containsKey(name);
    }

    public Optional<ValueType> type(String name) {
        return Optional.ofNullable(types.get(name));
    }

    /** The 21 field types, for a {@code PROTO} body or a value that has no declared type. */
    public Collection<ValueType> types() {
        return types.values();
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static List<String> strings(JsonObject json, String key) {
        JsonArray array = json.getAsJsonArray(key);
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return List.copyOf(values);
    }
}
