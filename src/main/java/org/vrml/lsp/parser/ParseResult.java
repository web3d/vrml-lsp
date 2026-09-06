package org.vrml.lsp.parser;

import java.util.ArrayList;
import java.util.List;

import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.diagnostics.Issue;
import org.vrml.lsp.diagnostics.Severity;
import org.vrml.lsp.lexer.Token;
import org.vrml.lsp.lexer.TokenType;

/**
 * Outcome of one parse: the CST, the complete token stream and the structural issues.
 *
 * <p>Parsing is total - a result always exists, even for garbage input - which is what
 * lets the editor keep working while a file is mid-typing.
 */
public record ParseResult(CstNode root, List<Token> tokens, List<Issue> issues, CharSequence source) {

    public boolean hasErrors() {
        for (Issue issue : issues) {
            if (issue.severity() == Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    /** Significant tokens only, i.e. everything the grammar looked at. */
    public List<Token> significantTokens() {
        List<Token> out = new ArrayList<>(tokens.size());
        for (Token t : tokens) {
            if (!t.type.isTrivia()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Index of the last token whose span reaches {@code offset}; -1 for an empty stream. */
    public int tokenIndexAt(int offset) {
        int lo = 0;
        int hi = tokens.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (tokens.get(mid).start <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    public Token tokenAt(int offset) {
        int idx = tokenIndexAt(offset);
        return idx >= 0 && idx < tokens.size() ? tokens.get(idx) : null;
    }

    /** Text of a node, exactly as it appears in the source. */
    public String text(CstNode node) {
        return source.subSequence(node.start(), node.end()).toString();
    }

    /**
     * Rebuild the document from the token stream.
     *
     * <p>Because tokens tile the input, this is only ever expected to differ from the
     * source if the lexer dropped characters - the round-trip test asserts that it never does.
     */
    public String printTokens() {
        StringBuilder sb = new StringBuilder(source.length());
        for (Token t : tokens) {
            if (t.type != TokenType.EOF) {
                sb.append(source, t.start, t.end);
            }
        }
        return sb.toString();
    }

    /** First and last offsets of every gap in the token tiling; empty when the lexer is sound. */
    public List<String> coverageGaps() {
        List<String> gaps = new ArrayList<>();
        int expected = 0;
        for (Token t : tokens) {
            if (t.type == TokenType.EOF) {
                break;
            }
            if (t.start != expected) {
                gaps.add("expected " + expected + " but token starts at " + t.start + " (" + t.type + ")");
            }
            if (t.end < t.start) {
                gaps.add("token ends before it starts: " + t);
            }
            expected = t.end;
        }
        if (expected != source.length()) {
            gaps.add("stream stops at " + expected + " but source length is " + source.length());
        }
        return gaps;
    }

    /**
     * Rebuild the document from the CST.
     *
     * <p>This is the printer the formatter will reuse, so it is exercised on every corpus
     * file by {@link #cstHoles()} rather than only by formatting requests.
     */
    public String printCst() {
        return CstNode.printLossless(root, source, tokens, new ArrayList<>());
    }

    /**
     * Tokens the tree failed to place, or placed twice or out of order; empty for a
     * well-formed CST. A non-empty list means a parser bug even when the parse had errors -
     * recovery must still hand every token to some node.
     */
    public List<String> cstHoles() {
        List<String> holes = new ArrayList<>();
        CstNode.printLossless(root, source, tokens, holes);
        return holes;
    }
}
