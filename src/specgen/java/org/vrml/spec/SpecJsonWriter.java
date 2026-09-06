package org.vrml.spec;

import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Renders the joined table as the file the server loads at startup.
 *
 * <p>Two things matter here that do not matter in most JSON: the key order is fixed by the code
 * below rather than by a hash, because the output is checked in and a regenerated table should show
 * a reviewable diff; and the {@code valueTemplates} block is written next to the node list so the
 * server never has to hold a second copy of the type rules.
 */
final class SpecJsonWriter {

    /** Bumped when the shape of this file changes, so a stale jar cannot misread a new table. */
    static final String SCHEMA_VERSION = "1";

    private SpecJsonWriter() {
    }

    static String render(List<NodeRow> nodes, Map<String, FieldTypes.Type> types,
                         Map<String, String> sources) {
        JsonObject root = new JsonObject();
        root.addProperty("version", SCHEMA_VERSION);
        addStrings(root, "sources", sources);
        root.add("sfTypes", stringArray(types.keySet().stream().toList()));
        root.add("valueTemplates", templates(types));
        root.add("nodes", nodes(nodes));
        // disableHtmlEscaping: descriptions quote '<' and '&' and a \u003c in every hover would
        // make the file unreadable in review.
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        return gson.toJson(root) + "\n";
    }

    private static JsonObject templates(Map<String, FieldTypes.Type> types) {
        JsonObject out = new JsonObject();
        for (FieldTypes.Type type : types.values()) {
            JsonObject one = new JsonObject();
            one.addProperty("width", type.width());
            one.addProperty("snippet", type.snippet());
            out.add(type.name(), one);
        }
        return out;
    }

    private static JsonArray nodes(List<NodeRow> rows) {
        JsonArray out = new JsonArray();
        for (NodeRow row : rows) {
            JsonObject node = new JsonObject();
            node.addProperty("name", row.name());
            node.addProperty("component", row.component());
            node.addProperty("level", row.level());
            node.addProperty("summary", row.summary());
            node.addProperty("specUrl", row.specUrl());
            JsonArray fields = new JsonArray();
            for (FieldRow field : row.fields()) {
                JsonObject one = new JsonObject();
                one.addProperty("name", field.name());
                one.addProperty("type", field.type());
                one.addProperty("access", field.access());
                one.addProperty("default", field.defaultValue());
                one.addProperty("doc", field.doc());
                one.add("childNodes", stringArray(field.childNodes()));
                one.add("aliases", stringArray(field.aliases()));
                fields.add(one);
            }
            node.add("fields", fields);
            out.add(node);
        }
        return out;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray out = new JsonArray();
        for (String value : values) {
            out.add(value);
        }
        return out;
    }

    private static void addStrings(JsonObject parent, String key, Map<String, String> values) {
        JsonObject out = new JsonObject();
        values.forEach(out::addProperty);
        parent.add(key, out);
    }
}
