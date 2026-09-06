package org.vrml.lsp.semantic;

import java.util.ArrayList;
import java.util.List;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.diagnostics.Codes;
import org.vrml.lsp.diagnostics.Issue;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.spec.Spec;

/**
 * Whether the values written for a field are values that field's type can hold.
 *
 * <p>Counting is done on the CST, never on the source text: the parser has already decided where
 * the value ends, and a nested node's numbers belong to that node, not to the field holding it.
 * {@link #read} therefore stops at a {@code NODE_STATEMENT} and takes it as one value.
 *
 * <p>Two upstream decisions shape what can be said here. The lexer keeps JavaCC's deliberately
 * greedy number rule, so {@code 1-2} arrives as one NUMBER - which is also why every number is
 * handed to {@link Double#parseDouble} and a failure is reported as a format problem: that is how
 * Xj3D finds out too ("faster to let the string-to-number conversion detect bad cases"). The other
 * decision is that a value the parser had to mark {@link CstKind#MISSING}, or whose tokens it
 * dumped into an {@link CstKind#ERROR} run, is not judged at all: the structural code already says
 * where it hurts, and a semantic guess on top of it would be a second, vaguer complaint about one
 * typo.
 */
final class Values {

    /** What a type's values are made of. */
    private enum Family {
        NUMERIC, STRING, BOOL, NODE, IMAGE
    }

    /**
     * A field's written values, tallied.
     *
     * <p>{@code hole} is set for anything the parser could not complete, so every check can be
     * skipped as a unit rather than each one re-deriving "was this value trustworthy".
     */
    private static final class Written {

        private boolean bracketed;
        private boolean hole;
        private boolean nullValue;
        private int booleans;
        private int nodes;
        private final List<CstNode> numbers = new ArrayList<>(16);
        private final List<CstNode> strings = new ArrayList<>(4);

        private boolean nothing() {
            return numbers.isEmpty() && strings.isEmpty() && booleans == 0 && nodes == 0;
        }
    }

    private final ParseResult parse;
    private final String node;
    private final String field;
    private final Spec.ValueType type;
    private final List<Issue> issues = new ArrayList<>(2);

    private int valueStart;
    private int valueEnd;

    private Values(ParseResult parse, String node, String field, Spec.ValueType type) {
        this.parse = parse;
        this.node = node;
        this.field = field;
        this.type = type;
    }

    /**
     * The value problems of one assignment.
     *
     * @param node the built-in node the field belongs to, or "" when the owner is a PROTO or a
     *        {@code Script}, whose fields mean whatever their author says and so carry no range
     * @param type the declared type, or {@code null} when its name is not one of the 21 - there is
     *        then nothing to compare against, and the caller reports the unknown type instead
     */
    static List<Issue> check(ParseResult parse, String node, String field, Spec.ValueType type,
            CstNode value) {
        return type == null ? List.of() : new Values(parse, node, field, type).run(value);
    }

    private List<Issue> run(CstNode value) {
        Written written = read(value);
        if (written.hole || written.nothing()) {
            return issues;
        }
        if (written.bracketed && !multi()) {
            bad("one value, not a list written in [ ]");
            return issues;
        }
        Family family = familyOf(type.name());
        if (written.nullValue) {
            if (family != Family.NODE) {
                bad("a value, not NULL, which only SFNode and MFNode fields take");
            }
            return issues;
        }
        if (family == Family.NODE) {
            if (!written.strings.isEmpty() || !written.numbers.isEmpty() || written.booleans > 0) {
                bad("nodes");
            }
            return issues;
        }
        if (written.nodes > 0) {
            bad("a node");
            return issues;
        }
        switch (family) {
            case STRING -> strings(written);
            case BOOL -> bools(written);
            case IMAGE -> image(written);
            case NUMERIC -> numbers(written);
        }
        return issues;
    }

    /** The 21 names split into five shapes; {@code SpecTest} pins the widths read here. */
    private static Family familyOf(String typeName) {
        return switch (typeName) {
            case "SFString", "MFString" -> Family.STRING;
            case "SFBool", "MFBool" -> Family.BOOL;
            case "SFNode", "MFNode" -> Family.NODE;
            case "SFImage" -> Family.IMAGE;
            default -> Family.NUMERIC;
        };
    }

    // ---- one family at a time ---------------------------------------------------------------

    private void numbers(Written written) {
        if (!written.strings.isEmpty() || written.booleans > 0) {
            bad("numbers");
            return;
        }
        count(written.numbers.size());
        boolean whole = type.name().endsWith("Int32");
        ValueConstraints.Rule rule = ValueConstraints.numeric(node, field, type.name());
        // One complaint per assignment, whichever value happens to be written first: a grid with a
        // thousand heights and one bad one is one mistake to the person reading the file.
        boolean formatSaid = false;
        boolean rangeSaid = false;
        for (CstNode leaf : written.numbers) {
            String text = parse.text(leaf);
            if (whole && !isWhole(text)) {
                if (!formatSaid) {
                    formatSaid = true;
                    issues.add(Issue.warning(Codes.FIELD_VALUE_FORMAT, "'" + field + "' is a "
                            + type.name() + ", which holds whole numbers; '" + text
                            + "' is not", leaf.start(), leaf.end()));
                }
                continue;
            }
            Double value = number(text);
            if (value == null) {
                if (!formatSaid) {
                    formatSaid = true;
                    issues.add(Issue.warning(Codes.FIELD_VALUE_FORMAT, "'" + text + "' is not a"
                            + " number a browser can read", leaf.start(), leaf.end()));
                }
            } else if (rule != null && !rule.accepts(value) && !rangeSaid) {
                rangeSaid = true;
                issues.add(Issue.warning(Codes.FIELD_RANGE, "'" + field + "' must be "
                        + rule.describe() + "; '" + text + "' is not", leaf.start(), leaf.end()));
            }
        }
    }

    private void strings(Written written) {
        if (!written.numbers.isEmpty() || written.booleans > 0) {
            bad("text in quotes");
            return;
        }
        ValueConstraints.Enum allowed = ValueConstraints.enumeration(node, field);
        if (allowed == null) {
            return;
        }
        for (CstNode leaf : written.strings) {
            String text = unquote(parse.text(leaf));
            if (!allowed.values().contains(text)) {
                issues.add(Issue.warning(Codes.FIELD_RANGE, "'" + field + "' takes one of "
                        + String.join(", ", allowed.values()) + "; '" + text + "' is not one of them",
                        leaf.start(), leaf.end()));
                return;
            }
        }
    }

    /**
     * Booleans need no count: a single value holds one {@code TRUE} or {@code FALSE} by
     * construction, and a multiple value may hold as many as it likes.
     */
    private void bools(Written written) {
        if (!written.numbers.isEmpty() || !written.strings.isEmpty()) {
            bad("TRUE or FALSE");
        }
    }

    /**
     * {@code SFImage} is a number field whose pixel data is written in hexadecimal, so none of the
     * number checks apply: {@code 0x00FF00} is not something {@link Double} can read, and the total
     * count depends on whether the author encoded one channel per value or one pixel per value -
     * which nothing in the CST distinguishes. Only the shape is checked.
     */
    private void image(Written written) {
        if (!written.strings.isEmpty() || written.booleans > 0) {
            bad("numbers");
        }
    }

    // ---- shared wording ----------------------------------------------------------------------

    private boolean multi() {
        return type.name().startsWith("MF");
    }

    /** How many numbers a value of this type must hold, when the type fixes that at all. */
    private void count(int written) {
        int width = type.width();
        if (width <= 0) {
            return;
        }
        if (multi()) {
            if (written % width != 0) {
                issues.add(Issue.warning(Codes.FIELD_VALUE_COUNT, "'" + field + "' is a " + type.name()
                        + ", whose values take " + width + " numbers each; " + written
                        + " are written here", valueStart, valueEnd));
            }
        } else if (written != width) {
            issues.add(Issue.warning(Codes.FIELD_VALUE_COUNT, "'" + field + "' is a " + type.name()
                    + " and takes " + width + (width == 1 ? " number" : " numbers") + "; " + written
                    + (written == 1 ? " is" : " are") + " written here", valueStart, valueEnd));
        }
    }

    private void bad(String expects) {
        issues.add(Issue.warning(Codes.FIELD_VALUE_FORMAT,
                "'" + field + "' is a " + type.name() + ", which takes " + expects,
                valueStart, valueEnd));
    }

    // ---- reading the tree --------------------------------------------------------------------

    /** Tally one {@code VALUE} node, or one {@code SF_VALUE}/{@code MF_VALUE} given directly. */
    private Written read(CstNode node) {
        Written written = new Written();
        valueStart = node.start();
        valueEnd = node.end();
        CstNode body = node.kind() == CstKind.VALUE ? onlyChild(node) : node;
        if (body == null) {
            written.hole = true;
            return written;
        }
        switch (body.kind()) {
            case SF_VALUE -> sfValue(body, written);
            case MF_VALUE -> {
                written.bracketed = true;
                mfValue(body, written);
            }
            default -> written.hole = true;
        }
        return written;
    }

    private static CstNode onlyChild(CstNode node) {
        return node.childCount() == 1 ? node.child(0) : null;
    }

    private void sfValue(CstNode node, Written written) {
        for (CstNode child : node.children()) {
            switch (child.kind()) {
                case KEYWORD -> written.nullValue = true;
                case NODE_STATEMENT -> written.nodes++;
                case LITERAL_VALUE -> literal(child, written);
                default -> written.hole = true;
            }
        }
    }

    private void mfValue(CstNode node, Written written) {
        for (CstNode child : node.children()) {
            switch (child.kind()) {
                case NUMBER_ARRAY -> numbersIn(child, written);
                case STRING_ARRAY -> stringsIn(child, written);
                case NODE_LIST -> written.nodes += child.childrenOf(CstKind.NODE_STATEMENT).size();
                case PUNCT -> {
                    // The brackets carry no value.
                }
                // An SF_VALUE in here is the parser's fallback for a missing '[': it reported that
                // already, so counting what is left would be a second opinion about one typo.
                default -> written.hole = true;
            }
        }
    }

    private void literal(CstNode node, Written written) {
        for (CstNode child : node.children()) {
            switch (child.kind()) {
                case KEYWORD -> written.booleans++;
                case STRING -> written.strings.add(child);
                case NUMBER_ARRAY -> numbersIn(child, written);
                default -> written.hole = true;
            }
        }
    }

    private void numbersIn(CstNode array, Written written) {
        for (CstNode leaf : array.children()) {
            if (leaf.kind() == CstKind.NUMBER) {
                written.numbers.add(leaf);
            } else {
                written.hole = true;
            }
        }
    }

    private void stringsIn(CstNode array, Written written) {
        for (CstNode leaf : array.children()) {
            if (leaf.kind() == CstKind.STRING) {
                written.strings.add(leaf);
            } else {
                written.hole = true;
            }
        }
    }

    // ---- small text helpers ------------------------------------------------------------------

    /** The lexer's number rule lets {@code .}, exponents and {@code 0x} through; none is whole. */
    private static boolean isWhole(String text) {
        return text.chars().noneMatch(c -> c == '.' || c == 'e' || c == 'E' || c == 'x' || c == 'X');
    }

    /** {@code null} when the greedy number rule swallowed something no number is, {@code 1-2}. */
    private static Double number(String text) {
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String unquote(String text) {
        int from = text.startsWith("\"") ? 1 : 0;
        int to = text.endsWith("\"") ? text.length() - 1 : text.length();
        return text.substring(from, to);
    }
}
