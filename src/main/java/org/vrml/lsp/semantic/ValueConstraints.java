package org.vrml.lsp.semantic;

import java.util.List;
import java.util.Map;

/**
 * What VRML97 says a field's values must lie within.
 *
 * <p>Keyed by node <em>and</em> field, because field names repeat with different meanings:
 * {@code height} is Cone's and Cylinder's extent, which has a size and cannot be negative, and
 * {@code ElevationGrid.height} is the grid of vertex elevations, which is negative in every terrain
 * that has a valley in it. A table keyed on the name alone invented 1120 complaints about nine
 * corpus files that are correct VRML97.
 *
 * <p>Only bounds the language states as forbidden are here. Where a browser special-cases a value
 * instead - {@code Viewpoint.fieldOfView} of 0 meaning "use the browser's default", a
 * {@code Sound.sourceGeometry} changing what the distance fields are measured against - the field is
 * left out, because the worst outcome of writing such a value is that it looks odd, and a rule that
 * has to guess about that is worse than no rule. Everything reported from here is a warning for the
 * same reason: Xj3D reads the value, hands it to the renderer and carries on.
 */
public final class ValueConstraints {

    /**
     * One number's rule. A bound of {@link Double#NaN} means the field is unbounded on that side;
     * an open bound is one where the limit itself is not a violation.
     */
    record Rule(double min, boolean minOpen, double max, boolean maxOpen) {

        boolean accepts(double value) {
            if (Double.isNaN(value)) {
                // Not this rule's business: an unparseable number is a format problem, reported by
                // the check that reads the text.
                return true;
            }
            if (!Double.isNaN(min) && (value < min || (minOpen && value == min))) {
                return false;
            }
            return !(!Double.isNaN(max) && (value > max || (maxOpen && value == max)));
        }

        /** "between 0 and 1", "at least 0", "greater than 0" - the phrase in the message. */
        String describe() {
            if (!Double.isNaN(min) && !Double.isNaN(max)) {
                return "between " + number(min) + " and " + number(max);
            }
            if (!Double.isNaN(min)) {
                return minOpen ? "greater than " + number(min) : "at least " + number(min);
            }
            return maxOpen ? "less than " + number(max) : "at most " + number(max);
        }

        /** So that a bound of 0 reads "0" and not "0.0" in a sentence meant for a person. */
        private static String number(double value) {
            return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        }
    }

    /** An enumerated string field, with the words it accepts as written. */
    record Enum(List<String> values) {
    }

    private static final double UNBOUNDED = Double.NaN;

    /** Fractions of full intensity, which is what a colour component and an amount are. */
    private static final Rule UNIT = new Rule(0, false, 1, false);

    /** A distance or an extent: nothing says how big it may be, but it cannot be behind you. */
    private static final Rule NON_NEGATIVE = new Rule(0, false, UNBOUNDED, false);

    /**
     * Per-field bounds, keyed {@code "Node field"}. The names that repeat across nodes -
     * {@code size}, {@code height}, {@code radius}, {@code intensity}, {@code color} - are the
     * reason for the prefix: each is a different quantity in each owner, and some of them are
     * node-holding fields of the same name in a third.
     */
    private static final Map<String, Rule> NUMERIC = Map.ofEntries(
            // Materials and lights mix by fraction, so a component outside the unit range is a
            // typo rather than a very bright surface.
            Map.entry("Material ambientIntensity", UNIT),
            Map.entry("Material shininess", UNIT),
            Map.entry("Material transparency", UNIT),
            Map.entry("Background transparency", UNIT),
            Map.entry("Sound intensity", UNIT),
            Map.entry("Sound priority", UNIT),
            // Extents and radii: zero leaves a degenerate shape the browser draws as nothing,
            // which is what the author usually meant to write; a negative one is not a size.
            Map.entry("Box size", NON_NEGATIVE),
            Map.entry("Cone bottomRadius", NON_NEGATIVE),
            Map.entry("Cone height", NON_NEGATIVE),
            Map.entry("Cylinder height", NON_NEGATIVE),
            Map.entry("Cylinder radius", NON_NEGATIVE),
            Map.entry("FontStyle size", NON_NEGATIVE),
            Map.entry("ProximitySensor size", NON_NEGATIVE),
            Map.entry("Sphere radius", NON_NEGATIVE),
            Map.entry("Text maxExtent", NON_NEGATIVE),
            Map.entry("TimeSensor cycleInterval", NON_NEGATIVE),
            Map.entry("VisibilitySensor size", NON_NEGATIVE),
            // A negative crease angle has no reading; the upper end is left alone because the
            // renderer clamps it and the wording about it is a recommendation, not a limit.
            Map.entry("ElevationGrid creaseAngle", NON_NEGATIVE),
            Map.entry("Extrusion creaseAngle", NON_NEGATIVE),
            Map.entry("IndexedFaceSet creaseAngle", NON_NEGATIVE),
            // Sound's attenuation distances are measured from the listener, so each is a distance.
            Map.entry("Sound minFront", NON_NEGATIVE),
            Map.entry("Sound maxFront", NON_NEGATIVE),
            Map.entry("Sound minBack", NON_NEGATIVE),
            Map.entry("Sound maxBack", NON_NEGATIVE));

    /**
     * {@code EXponential} is not a typo: it is VRML97's own third spelling, kept for files that
     * were written against the standard's example rather than against its list.
     */
    private static final Map<String, Enum> ENUMS = Map.of(
            "FontStyle style", new Enum(List.of("PLAIN", "BOLD", "ITALIC", "BOLDITALIC")),
            "Fog fogType", new Enum(List.of("LINEAR", "EXP", "EXponential")));

    private ValueConstraints() {
    }

    /**
     * The bound on one number written for {@code node}'s {@code field} of type {@code typeName}.
     *
     * <p>A colour's range belongs to its type - every {@code SFColor} and {@code MFColor} component
     * in the language is a fraction of full intensity, in every node - so it is answered here
     * rather than repeated per field.
     */
    static Rule numeric(String node, String field, String typeName) {
        if (typeName.equals("SFColor") || typeName.equals("MFColor")) {
            return UNIT;
        }
        return node.isEmpty() ? null : NUMERIC.get(node + " " + field);
    }

    /** The allowed words, or {@code null} when the field is free text. */
    static Enum enumeration(String node, String field) {
        return node.isEmpty() ? null : ENUMS.get(node + " " + field);
    }

    /**
     * The words {@code node}'s {@code field} accepts, empty when it is free text.
     *
     * <p>The same table read the other way round: {@link #enumeration} answers "is this value
     * wrong", and completion needs "what would not be wrong" - which is only ever the whole list
     * or nothing, since a field either has a fixed vocabulary or has none.
     */
    public static List<String> enumeratedValues(String node, String field) {
        Enum words = ENUMS.get(node + " " + field);
        return words == null ? List.of() : words.values();
    }
}
