package org.vrml.lsp.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.parser.VrmlParser;
import org.vrml.lsp.semantic.Semantics;
import org.vrml.lsp.semantic.SymbolTable;
import org.vrml.lsp.spec.Specs;

/**
 * The one place a document turns into diagnostics.
 *
 * <p>Everything above the parser - {@code publishDiagnostics}, the {@code --check-file} CLI, the
 * formatter's "is this file too broken to reindent" test - asks this class instead of the parser
 * directly, so that the answer cannot drift between them. The parser contributes the structural
 * verdict of the 31 productions; {@link HeaderCheck} contributes what only the first line can
 * tell; {@link Semantics} reads the finished tree against the generated node table and the file's
 * own DEF/USE/PROTO/ROUTE names.
 *
 * <p>{@link #normalize} then makes the list fit for display: no duplicate markers, a fixed order
 * that does not depend on which pass found what, and a hard cap on how many issues may pile onto
 * a single position, because one mistyped brace must not cost a user a screenful of squiggles.
 */
public final class DocumentAnalyzer {

    /**
     * Cap on issues sharing a start position. The parser already reports at most once per token, so
     * this only bounds the overlap of the three passes - at one unknown field name, say, a
     * structural marker, VRL2002 and its VRL3001 suggestion, which is three and all worth reading.
     */
    private static final int MAX_PER_POSITION = 3;

    private DocumentAnalyzer() {
    }

    /**
     * Parse result plus the finished, display-ready issue list.
     *
     * <p>{@code symbols} comes along because getting the semantic issues meant building it anyway,
     * and {@code definition}, {@code documentSymbol} and completion all ask who defined what. One
     * walk of the tree per edit is then enough for the whole request.
     */
    public record Analysis(ParseResult parse, SymbolTable symbols, List<Issue> issues) {

        public boolean hasErrors() {
            for (Issue issue : issues) {
                if (issue.severity() == Severity.ERROR) {
                    return true;
                }
            }
            return false;
        }
    }

    public static Analysis analyze(CharSequence text) {
        return of(VrmlParser.parse(text));
    }

    /**
     * Analyse an existing parse. Split out because the completion and hover providers keep the
     * CST around and must not pay for a second parse to get the same issue list.
     */
    public static Analysis of(ParseResult parse) {
        SymbolTable symbols = SymbolTable.of(parse);
        List<Issue> all = new ArrayList<>(parse.issues());
        all.addAll(HeaderCheck.check(parse.source(), parse.tokens()));
        all.addAll(Semantics.check(parse, Specs.standard(), symbols));
        return new Analysis(parse, symbols, normalize(all));
    }

    private static List<Issue> normalize(List<Issue> issues) {
        List<Issue> sorted = new ArrayList<>(issues);
        sorted.sort(Comparator.comparingInt(Issue::start)
                .thenComparingInt(Issue::end)
                .thenComparing(Issue::code));
        List<Issue> out = new ArrayList<>(sorted.size());
        int seenAtPosition = 0;
        int lastStart = -1;
        for (Issue issue : sorted) {
            if (issue.start() != lastStart) {
                lastStart = issue.start();
                seenAtPosition = 0;
            } else if (!out.isEmpty() && sameSpan(out.get(out.size() - 1), issue)) {
                // Two passes agreeing on code and span exactly; one marker is enough.
                continue;
            }
            if (seenAtPosition >= MAX_PER_POSITION) {
                continue;
            }
            seenAtPosition++;
            out.add(issue);
        }
        return List.copyOf(out);
    }

    private static boolean sameSpan(Issue a, Issue b) {
        return a.code() == b.code() && a.end() == b.end() && a.message().equals(b.message());
    }
}
