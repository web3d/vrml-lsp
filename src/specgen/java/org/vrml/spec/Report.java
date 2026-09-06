package org.vrml.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The difference report the generator writes next to the spec file.
 *
 * <p>Its purpose is the sentence in the plan: a human signs off the disagreements between the three
 * sources instead of the generator picking a winner. So it is organised by kind of disagreement, in
 * a fixed order, with duplicates removed and lines sorted - two runs over the same checkout must
 * produce byte-identical output, or the file is useless for reviewing a change.
 *
 * <p>Sections are declared up front and an unknown name is an error, so a report line cannot be
 * added and then never read because of a typo.
 */
final class Report {

    /** A named list of lines. Returned by {@link #section} so callers can say {@code add(...)}. */
    final class Section {

        private final String name;

        private Section(String name) {
            this.name = name;
        }

        void add(String line) {
            sections.computeIfAbsent(name, key -> new TreeSet<>()).add(line);
        }
    }

    /** Review order, chosen so the questions that can change the table come last. */
    private static final List<String> SECTIONS = List.of(
            "nodes missing from UOM",
            "x3d-only fields dropped",
            "fields UOM does not list",
            "names a field also answers to",
            "fieldMap names no declaration explains",
            "UOM fields Xj3D never declares",
            "access disagreements",
            "SFNode fields without a candidate list",
            "unresolvable acceptableNodeTypes",
            "corpus fields the table does not know",
            "open questions");

    private final Map<String, TreeSet<String>> sections = new LinkedHashMap<>();
    private final List<String> preamble = new ArrayList<>();

    Report() {
        for (String name : SECTIONS) {
            sections.put(name, new TreeSet<>());
        }
    }

    Section section(String name) {
        if (!sections.containsKey(name)) {
            throw new IllegalArgumentException("no such report section: " + name
                    + "; declare it in SECTIONS so reviewers see it");
        }
        return new Section(name);
    }

    /** A line above the sections: counts, provenance, anything that frames the numbers. */
    void preamble(String line) {
        preamble.add(line);
    }

    /** How many lines a section holds, for the summary the main class prints. */
    int count(String name) {
        return sections.get(name).size();
    }

    String text() {
        StringBuilder out = new StringBuilder();
        out.append("# vrml97-spec.json: where the three sources disagree\n");
        out.append("# Written by org.vrml.spec.SpecGenMain; regenerate with `mvn -Pspecgen`.\n");
        out.append("# Every section is a set of facts, not a to-do list: the table already reflects\n");
        out.append("# the rule \"Xj3D decides names, UOM decides meaning\". Read it to find out\n");
        out.append("# whether that rule got any of them wrong.\n");
        for (String line : preamble) {
            out.append("# ").append(line).append('\n');
        }
        for (String name : SECTIONS) {
            TreeSet<String> lines = sections.get(name);
            out.append('\n').append("## ").append(name).append(" (").append(lines.size())
                    .append(")\n");
            if (lines.isEmpty()) {
                out.append("#   none\n");
                continue;
            }
            for (String line : lines) {
                appendWrapped(out, "    ", line);
            }
        }
        return out.toString();
    }

    /**
     * One line, or several. Most sections hold short pairs of names; the questions hold sentences of
     * evidence, and a 1200-column line cannot be read in a diff.
     */
    private static void appendWrapped(StringBuilder out, String start, String line) {
        String indent = start;
        StringBuilder current = new StringBuilder(start);
        for (String word : line.split(" ")) {
            boolean filled = current.length() > indent.length()
                    && current.length() + 1 + word.length() > WIDTH;
            if (filled) {
                out.append(current).append('\n');
                indent = CONTINUATION;
                current = new StringBuilder(indent);
            }
            if (current.length() > indent.length()) {
                current.append(' ');
            }
            current.append(word);
        }
        out.append(current).append('\n');
    }

    /** Column the report wraps at, counting the indent. */
    private static final int WIDTH = 88;

    /** Wrapped text is indented past the bullets so a reader sees it is still one item. */
    private static final String CONTINUATION = "      ";
}
