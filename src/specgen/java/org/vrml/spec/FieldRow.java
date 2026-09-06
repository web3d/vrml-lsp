package org.vrml.spec;

import java.util.List;

/**
 * One field as it will be written to the spec file.
 *
 * <p>{@code defaultValue} and {@code doc} come from UOM and may legitimately be empty - Xj3D knows
 * names UOM never heard of. {@code childNodes} is non-empty only for {@code SFNode}/{@code MFNode}
 * fields, and holds concrete VRML97 node names, never an abstract X3D type. {@code aliases} are the
 * other names this field answers to, which is how the VRML97 spellings X3D renamed ({@code level},
 * {@code choice}) survive in a table built from Xj3D's X3D-facing declarations.
 */
record FieldRow(String name, String type, String access, String defaultValue, String doc,
                List<String> childNodes, List<String> aliases) {

    /** Whether {@code candidate} is what a scene file would have to type for this field. */
    boolean answersTo(String candidate) {
        return name.equals(candidate) || aliases.contains(candidate);
    }
}
