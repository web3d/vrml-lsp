package org.vrml.lsp;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.lsp4j.launch.LSPLauncher;
import org.vrml.lsp.diagnostics.DocumentAnalyzer;
import org.vrml.lsp.diagnostics.Issue;
import org.vrml.lsp.diagnostics.Severity;
import org.vrml.lsp.server.VrmlLanguageServer;
import org.vrml.lsp.spec.Spec;
import org.vrml.lsp.text.LineIndex;

/**
 * Entry point of the VRML97 language server.
 *
 * <p>Only {@code System.out} may carry JSON-RPC traffic; all diagnostics and log
 * output go to {@link System#err} (or a file when {@code -Dvrml.lsp.log=<path>}).
 */
public final class Launcher {

    public static final String VERSION = "0.1.0";

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            switch (args[0]) {
                case "--version" -> {
                    System.out.println("vrml-lsp " + VERSION);
                    return;
                }
                case "--help", "-h" -> {
                    usage();
                    return;
                }
                case "--check-file" -> {
                    System.exit(checkFiles(args));
                }
                case "--spec-dump" -> {
                    System.exit(dumpSpec(args));
                }
                default -> {
                    System.err.println("unknown option: " + args[0]);
                    usage();
                    System.exit(2);
                }
            }
        }

        Log.init();
        VrmlLanguageServer server = new VrmlLanguageServer();
        InputStream in = System.in;
        OutputStream out = new BufferedStdout(System.out);
        var launcher = LSPLauncher.createServerLauncher(server, in, out);
        // Documented wiring order: hand the client proxy to the server (publishDiagnostics
        // and friends need it) before the read loop starts.
        server.connect(launcher.getRemoteProxy());
        launcher.startListening();
        Log.info("vrml-lsp " + VERSION + " listening on stdio");
    }

    private static void usage() {
        System.err.println("""
                usage: vrml-lsp                        run as a stdio language server
                       vrml-lsp --version | --help
                       vrml-lsp --check-file FILE...   parse and print diagnostics, no server
                       vrml-lsp --spec-dump [NODE[.FIELD]]
                                                       print the generated node/field table""");
    }

    /**
     * The spec table, as far as the running jar knows.
     *
     * <p>What {@code completion} and {@code hover} would answer, minus the editor: a bare
     * {@code --spec-dump} lists the nodes, {@code --spec-dump NODE} its fields, and
     * {@code --spec-dump NODE.FIELD} one row in full. Exit code 2 on an unknown name, so a shell
     * can use it to check that the shipped table really contains what was just fixed in the
     * generator - which is the only way to see {@code mvn -Pspecgen} output without a client.
     */
    private static int dumpSpec(String[] args) {
        Spec spec = Spec.load();
        if (args.length == 1) {
            for (Spec.Node node : spec.nodes()) {
                // 21 for the component: EnvironmentalEffects is the longest name in the table, and
                // a ragged column makes the diff between two runs unreadable.
                System.out.printf("%-22s %-21s/%-2d %2d fields%n", node.name(), node.component(),
                        node.level(), node.fields().size());
            }
            System.out.println(spec.nodes().size() + " nodes, " + spec.types().size()
                    + " field types");
            return 0;
        }
        String asked = args[1];
        int dot = asked.indexOf('.');
        if (dot < 0) {
            Spec.Node node = spec.node(asked).orElse(null);
            if (node == null) {
                System.err.println("no node named " + asked);
                return 2;
            }
            for (Spec.Field field : node.fields()) {
                // The alias column is the point of printing this list: `level` and `choice` exist
                // nowhere else in the table, so this is where a person sees they survived.
                String alias = field.aliases().isEmpty()
                        ? "" : "also " + String.join(",", field.aliases());
                System.out.println(String.format("    %-22s %-10s %-14s %-12s %s%s", field.name(),
                        field.type(), field.access(),
                        field.defaultValue().isEmpty() ? "" : "= " + field.defaultValue(),
                        alias, childKinds(field.childNodes().size())).stripTrailing());
            }
            return 0;
        }
        String nodeName = asked.substring(0, dot);
        String fieldName = asked.substring(dot + 1);
        Spec.Node node = spec.node(nodeName).orElse(null);
        Spec.Field field = node == null ? null : node.field(fieldName).orElse(null);
        if (field == null) {
            System.err.println("no field named " + fieldName + " on " + nodeName);
            return 2;
        }
        System.out.println(nodeName + "." + field.name() + " " + field.type() + " "
                + field.access());
        if (!field.aliases().isEmpty()) {
            System.out.println("  also written as " + String.join(", ", field.aliases()));
        }
        if (!field.defaultValue().isEmpty()) {
            System.out.println("  default  " + field.defaultValue());
        }
        if (!field.childNodes().isEmpty()) {
            System.out.println("  children " + String.join(", ", field.childNodes()));
        }
        System.out.println("  " + field.doc());
        return 0;
    }

    /** How many nodes an {@code SFNode} field may hold, said once rather than as "1 kinds". */
    private static String childKinds(int count) {
        if (count == 0) {
            return "";
        }
        return " (" + count + (count == 1 ? " child kind)" : " child kinds)");
    }

    /**
     * Offline parsing, for triage and for regenerating the golden diagnostic expectations.
     *
     * <p>Mirrors what the server reports rather than asking the server, so a file that looks
     * clean here is clean in the editor too. Exit code is 1 when any file had an error.
     */
    private static int checkFiles(String[] args) throws IOException {
        if (args.length == 1) {
            System.err.println("--check-file needs at least one path");
            return 2;
        }
        int errors = 0;
        for (int a = 1; a < args.length; a++) {
            Path path = Path.of(args[a]);
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            var analysis = DocumentAnalyzer.analyze(source);
            LineIndex index = new LineIndex(source);
            for (Issue issue : analysis.issues()) {
                if (issue.severity() == Severity.ERROR) {
                    errors++;
                }
                System.out.println(path + ":" + index.describe(issue.start()) + ": ["
                        + issue.codeLabel() + "] " + issue.severity() + ": " + issue.message());
            }
            if (!source.equals(analysis.parse().printCst())) {
                errors++;
                System.out.println(path + ": INTERNAL: the CST does not print back the input at offset "
                        + firstDifference(source, analysis.parse().printCst()));
            }
        }
        return errors == 0 ? 0 : 1;
    }

    private static int firstDifference(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return i;
            }
        }
        return a.length() == b.length() ? -1 : n;
    }

    /**
     * Flushes after every write. LSP4J already flushes per message, but a torn or
     * buffered tail is the kind of bug that costs a whole afternoon to spot, so the
     * safety net stays.
     */
    private static final class BufferedStdout extends FilterOutputStream {
        BufferedStdout(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            out.flush();
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            out.flush();
        }
    }
}
