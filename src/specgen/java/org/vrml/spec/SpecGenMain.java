package org.vrml.spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@code vrml97-spec.json} - the node, field, type and default table the server answers
 * questions from.
 *
 * <p>A development-time tool, run by {@code mvn -Pspecgen} and never from the shipped jar. Its
 * inputs are three checkouts the server does not depend on at runtime:
 *
 * <ol>
 *   <li>{@code config/2.0/profiles.xml} - which node names exist, and their component and level;</li>
 *   <li>{@code vrml/renderer/common/nodes/**&#47;Base*.java} - which fields each of those nodes
 *       accepts, in the words a {@code .wrl} file has to use;</li>
 *   <li>the X3D Unified Object Model - the default value, description and node constraints for the
 *       names the first two agree on.</li>
 * </ol>
 *
 * A fourth, derived input keeps the other three honest: the {@code xj3d/parsetest} corpus, parsed
 * with this project's own parser, so a field real VRML97 files use but the table does not know
 * about fails the build rather than surprising a user.
 *
 * <p>Where the sources conflict, the two Java-side sources win on names and UOM wins on meaning -
 * see {@link Join}. Anything the rule cannot settle is written to the report for a person, and the
 * generator exits non-zero on anything it would have to guess.
 */
public final class SpecGenMain {

    private static final String USAGE = """
            usage: SpecGenMain --profiles <profiles.xml> --nodes <common/nodes dir> \
                    --uom <UOM.xml> --parsetest <dir> --out <json> --report <txt>""";

    public static void main(String[] args) {
        try {
            run(new Arguments(args));
        } catch (SpecGenException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("I/O failure: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(Arguments args) throws SpecGenException, IOException {
        List<ProfilesReader.NodeEntry> entries = ProfilesReader.read(args.file("--profiles"));
        UomReader uom = UomReader.read(args.file("--uom"));
        CorpusUsage corpus = CorpusUsage.read(args.dir("--parsetest"));

        Map<String, Xj3dFieldReader.Fields> declarations = new LinkedHashMap<>();
        int declared = 0;
        for (ProfilesReader.NodeEntry entry : entries) {
            Xj3dFieldReader.Fields fields = Xj3dFieldReader.read(args.dir("--nodes"), entry.name());
            declarations.put(entry.name(), fields);
            declared += fields.decls().size();
        }

        List<String> names = entries.stream().map(ProfilesReader.NodeEntry::name).toList();
        Join.Result joined = new Join(uom, corpus, names).run(entries, declarations);
        Report report = joined.report();
        report.preamble("profiles.xml lists " + entries.size() + " nodes");
        report.preamble("Xj3D declares " + declared + " fields across those files");
        report.preamble("UOM 4.1 has " + names.stream().filter(uom::nodePresent).count()
                + " of them as <ConcreteNode>");
        report.preamble("corpus: " + corpus);
        reportCorpusGaps(joined.nodes(), corpus, report);
        for (String question : SpecGenQuestions.all()) {
            report.section("open questions").add(question);
        }

        String json = SpecJsonWriter.render(joined.nodes(), FieldTypes.all(), sources());
        write(args.file("--out", true), json);
        write(args.file("--report", true), report.text());
        summary(joined.nodes(), declared, report);
    }

    /**
     * Every field name the {@code #VRML}-headed corpus writes inside a known node, checked against
     * the finished table.
     *
     * <p>This is the one check that does not trust the sources: if hand-written VRML97 files use a
     * name and the table does not list it, either the extraction missed a declaration or one of the
     * sources renamed it, and either way the table is short.
     */
    private static void reportCorpusGaps(List<NodeRow> nodes, CorpusUsage corpus, Report report) {
        for (NodeRow node : nodes) {
            // A Script's fields are declared by the script itself, so the corpus names there are
            // the file's own invention and prove nothing about the language.
            if (node.name().equals("Script")) {
                continue;
            }
            for (String used : corpus.vrmlFieldNames(node.name())) {
                if (!node.hasField(used)) {
                    report.section("corpus fields the table does not know")
                            .add(node.name() + "." + used + " (used "
                                    + corpus.vrmlFieldOccurrences(node.name(), used)
                                    + "x in .wrl)");
                }
            }
        }
    }

    /** Provenance, with the file names only: an absolute path would make the output machine-bound. */
    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("nodes", "Xj3D config/2.0/profiles.xml");
        sources.put("fields", "Xj3D vrml/renderer/common/nodes/**/Base*.java");
        sources.put("metadata", "X3dUnifiedObjectModel-4.1.xml");
        sources.put("usage", "xj3d/parsetest/**/*.wrl");
        return sources;
    }

    private static void summary(List<NodeRow> nodes, int declared, Report report) {
        int fields = nodes.stream().mapToInt(node -> node.fields().size()).sum();
        List<String> parts = new ArrayList<>();
        parts.add(nodes.size() + " nodes");
        parts.add(fields + " fields (" + declared + " declared, " + (declared - fields)
                + " dropped as X3D-only)");
        for (String section : List.of("nodes missing from UOM", "fields UOM does not list",
                "names a field also answers to", "fieldMap names no declaration explains",
                "UOM fields Xj3D never declares", "access disagreements",
                "SFNode fields without a candidate list", "unresolvable acceptableNodeTypes",
                "corpus fields the table does not know", "open questions")) {
            int count = report.count(section);
            if (count > 0) {
                parts.add(section + ": " + count);
            }
        }
        System.out.println(String.join("; ", parts));
    }

    private static void write(Path file, String content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        System.out.println("wrote " + file);
    }

    /** {@code --flag value} pairs, with the presence checks the generator needs before reading. */
    private static final class Arguments {

        private final Map<String, String> values = new LinkedHashMap<>();

        Arguments(String[] args) throws SpecGenException {
            for (int i = 0; i < args.length; i++) {
                String flag = args[i];
                if (!flag.startsWith("--") || i + 1 == args.length) {
                    throw new SpecGenException(USAGE + "\nproblem: '" + flag + "'");
                }
                values.put(flag, args[++i]);
            }
        }

        Path file(String flag) throws SpecGenException {
            return file(flag, false);
        }

        /** With {@code created} the path is an output, so only its name has to be given. */
        Path file(String flag, boolean created) throws SpecGenException {
            Path path = path(flag);
            if (!created && !Files.isRegularFile(path)) {
                throw new SpecGenException(flag + ": no file at " + path
                        + ". The spec sources live in sibling checkouts; pass their paths.");
            }
            return path;
        }

        Path dir(String flag) throws SpecGenException {
            Path path = path(flag);
            if (!Files.isDirectory(path)) {
                throw new SpecGenException(flag + ": no directory at " + path);
            }
            return path;
        }

        private Path path(String flag) throws SpecGenException {
            String value = values.get(flag);
            if (value == null) {
                throw new SpecGenException(USAGE + "\nproblem: " + flag + " is required");
            }
            return Path.of(value);
        }
    }
}
