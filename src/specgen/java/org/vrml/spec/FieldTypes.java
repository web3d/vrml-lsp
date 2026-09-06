package org.vrml.spec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The closed set of VRML97 field types, with what completion and value checking need to know.
 *
 * <p>The set is not derived from the other sources: it is the grammar's own list, the {@code SF*}
 * and {@code MF*} spellings {@code VRML97FieldParser.jj} can read - eleven SF forms and ten MF ones,
 * {@code SFImage} having no multiple-valued counterpart. Anything outside it is either an X3D
 * addition ({@code SFVec3d}, {@code MFVec4f}) or a typo, and the generator fails on it rather than
 * writing a table the server cannot interpret. {@code MFBool} and {@code MFTime} are kept for the
 * same reason the grammar keeps them: a Script's own interface declarations may use them.
 *
 * <p>{@code width} is how many numbers make up one element of that type, which is the single fact
 * M4 needs to tell {@code translation 0 0} from {@code translation 0 0 0}. {@code snippet} is the
 * LSP completion template: tab stops in declaration order, a trailing stop where the caret should
 * land, and for the MF forms a comma plus newline so a second element is one keystroke away.
 */
final class FieldTypes {

    /** Name, numbers per element, completion snippet. */
    record Type(String name, int width, String snippet) {
    }

    private static final Map<String, Type> TYPES = build();

    private FieldTypes() {
    }

    static boolean isKnown(String typeName) {
        return TYPES.containsKey(typeName);
    }

    static Type of(String typeName) {
        return TYPES.get(typeName);
    }

    /** In the order a reference lists them: the SF forms, then the MF forms. */
    static List<String> names() {
        return List.copyOf(TYPES.keySet());
    }

    static Map<String, Type> all() {
        return TYPES;
    }

    private static Map<String, Type> build() {
        Map<String, Type> types = new LinkedHashMap<>();
        // width 0 means "not a run of numbers": booleans, strings, node references and SFImage's
        // byte array each have their own shape, spelled out in the snippet.
        types.put("SFBool", new Type("SFBool", 0, "${1|true,false|}"));
        types.put("SFColor", new Type("SFColor", 3, "${1:0.0} ${2:0.0} ${3:0.0}"));
        types.put("SFFloat", new Type("SFFloat", 1, "${1:0.0}"));
        types.put("SFImage", new Type("SFImage", 0, "${1:1} ${2:1} ${3:3} 0x${4:000000}"));
        types.put("SFInt32", new Type("SFInt32", 1, "${1:0}"));
        types.put("SFNode", new Type("SFNode", 0, "${1:NULL}"));
        types.put("SFRotation", new Type("SFRotation", 4,
                "${1:0.0} ${2:0.0} ${3:1.0} ${4:0.0}"));
        types.put("SFString", new Type("SFString", 0, "\"$1\""));
        types.put("SFTime", new Type("SFTime", 1, "${1:0.0}"));
        types.put("SFVec2f", new Type("SFVec2f", 2, "${1:0.0} ${2:0.0}"));
        types.put("SFVec3f", new Type("SFVec3f", 3, "${1:0.0} ${2:0.0} ${3:0.0}"));
        types.put("MFBool", new Type("MFBool", 0, "${1|true,false|},\n$0"));
        types.put("MFColor", new Type("MFColor", 3,
                "${1:0.0} ${2:0.0} ${3:0.0},\n$0"));
        types.put("MFFloat", new Type("MFFloat", 1, "${1:0.0},\n$0"));
        types.put("MFInt32", new Type("MFInt32", 1, "${1:0},\n$0"));
        types.put("MFNode", new Type("MFNode", 0, "${1:NULL},\n$0"));
        types.put("MFRotation", new Type("MFRotation", 4,
                "${1:0.0} ${2:0.0} ${3:1.0} ${4:0.0},\n$0"));
        types.put("MFString", new Type("MFString", 0, "\"$1\",\n$0"));
        types.put("MFTime", new Type("MFTime", 1, "${1:0.0},\n$0"));
        types.put("MFVec2f", new Type("MFVec2f", 2, "${1:0.0} ${2:0.0},\n$0"));
        types.put("MFVec3f", new Type("MFVec3f", 3, "${1:0.0} ${2:0.0} ${3:0.0},\n$0"));
        // unmodifiable rather than Map.copyOf: the declaration order above is the order the spec
        // file lists types in, and a copy would shuffle it on every JVM start.
        return Collections.unmodifiableMap(types);
    }
}
