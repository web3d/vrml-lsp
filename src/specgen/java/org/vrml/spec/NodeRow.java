package org.vrml.spec;

import java.util.List;

/**
 * One node as it will be written to the spec file.
 *
 * <p>{@code component} and {@code level} come from Xj3D's profile listing, which is also where the
 * node set itself comes from; {@code summary} and {@code specUrl} come from UOM and describe the
 * X3D reading of the same node, so they are prose a user can profit from but not names they can
 * type.
 */
record NodeRow(String name, String component, int level, String summary, String specUrl,
               List<FieldRow> fields) {

    /** Whether a scene file may name {@code fieldName} inside this node, under any spelling. */
    boolean hasField(String fieldName) {
        return fields.stream().anyMatch(field -> field.answersTo(fieldName));
    }
}
