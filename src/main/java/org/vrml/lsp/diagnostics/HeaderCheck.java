package org.vrml.lsp.diagnostics;

import java.util.ArrayList;
import java.util.List;

import org.vrml.lsp.lexer.Token;
import org.vrml.lsp.lexer.TokenType;

/**
 * The file header, which the grammar says nothing about.
 *
 * <p>ISO/IEC 14972 requires {@code #VRML V2.0 utf8} as the first line, but the JavaCC grammar
 * never checks: its {@code HEADER} token is commented out at {@code .jj} L1466 and the reader
 * does the test before handing the stream over. Keeping the check out of
 * {@link org.vrml.lsp.parser.VrmlParser} is what lets a grammar unit test write
 * {@code Box {}} and get a clean parse while the editor still tells a user that line 1 is
 * missing.
 *
 * <p>What has to be forgiven, because the corpus does it: several spaces in a row, trailing
 * prose ({@code #VRML V2.0 utf8 CosmoWorlds V1.0}) and a byte order mark in front of the
 * header. What must still fail is exactly the five things {@code vrml97/bad_header*.wrl}
 * exercise - a space after {@code #}, a dropped {@code V}, a dropped encoding, missing
 * separators, and the whole header pushed to the second line.
 */
public final class HeaderCheck {

    private final CharSequence text;
    private final List<Token> tokens;

    private HeaderCheck(CharSequence text, List<Token> tokens) {
        this.text = text;
        this.tokens = tokens;
    }

    /**
     * @return the header verdict for a tokenised document, usually zero or one issue.
     */
    public static List<Issue> check(CharSequence text, List<Token> tokens) {
        return new HeaderCheck(text, tokens).run();
    }

    private List<Issue> run() {
        List<Issue> issues = new ArrayList<>(1);
        if (isBlankDocument()) {
            // A fresh empty buffer is not a broken file; the moment anything is typed the
            // check engages, which is when the message becomes useful.
            return issues;
        }
        int at = 0;
        // Only same-line filler may come first: spaces, tabs, a byte order mark. A line break
        // means whatever follows is no longer on the first line.
        while (at < tokens.size() && tokens.get(at).type == TokenType.WHITESPACE
                && !hasLineBreak(tokens.get(at))) {
            at++;
        }
        if (at >= tokens.size()) {
            return issues;
        }
        Token first = tokens.get(at);
        if (first.type != TokenType.COMMENT) {
            issues.add(Issue.error(Codes.MISSING_HEADER,
                    "a VRML97 file must start with '#VRML V2.0 utf8' on its first line",
                    first.start, lineEnd(first)));
            return issues;
        }
        if (startsWithX3DHeader(first)) {
            // Two corpus files are .wrl-named X3D, and X3D syntax fails all over a VRML97
            // grammar. Saying why once beats letting the user read seven structural errors as
            // a broken tool - and it replaces the header complaint, which would be the same
            // sentence twice.
            issues.add(Issue.info(Codes.X3D_DIALECT,
                    "this file declares X3D, not VRML97; only VRML97 is parsed here",
                    first.start, Math.min(first.start + 4, first.end)));
            return issues;
        }
        if (isVrmlHeader(first)) {
            return issues;
        }
        for (int n = at + 1; n < tokens.size(); n++) {
            Token t = tokens.get(n);
            if (t.type != TokenType.COMMENT && hasLineBreak(t)) {
                break;
            }
            if (t.type == TokenType.COMMENT && isVrmlHeader(t)) {
                issues.add(Issue.error(Codes.MALFORMED_HEADER,
                        "the VRML97 header belongs on the first line, not below it",
                        first.start, lineEnd(first)));
                return issues;
            }
        }
        issues.add(Issue.error(Codes.MALFORMED_HEADER,
                "expected the header '#VRML V2.0 utf8' but found this",
                first.start, lineEnd(first)));
        return issues;
    }

    /** True when the document holds nothing but whitespace, comments excluded: see run(). */
    private boolean isBlankDocument() {
        for (Token t : tokens) {
            if (t.type == TokenType.EOF) {
                break;
            }
            if (t.type != TokenType.WHITESPACE) {
                return false;
            }
        }
        return true;
    }

    /** {@code #VRML} ws {@code V2.0} ws {@code utf8} then end-of-line or prose. */
    private boolean isVrmlHeader(Token comment) {
        int end = lineEnd(comment);
        int at = matchLiteral(comment.start, end, "#VRML");
        if (at < 0) {
            return false;
        }
        int after = skipInline(at, end);
        if (after == at) {
            return false;
        }
        at = matchLiteral(after, end, "V2.0");
        if (at < 0) {
            return false;
        }
        after = skipInline(at, end);
        if (after == at) {
            return false;
        }
        at = matchLiteral(after, end, "utf8");
        if (at < 0) {
            return false;
        }
        return at == end || text.charAt(at) == ' ' || text.charAt(at) == '\t';
    }

    /** @return true for {@code #X3D ...}, which this server deliberately does not parse. */
    private boolean startsWithX3DHeader(Token comment) {
        int at = comment.start;
        while (at < comment.end && (text.charAt(at) == '\uFEFF' || text.charAt(at) == ' '
                || text.charAt(at) == '\t')) {
            at++;
        }
        return at + 3 < comment.end && text.charAt(at) == '#' && text.charAt(at + 1) == 'X'
                && text.charAt(at + 2) == '3' && text.charAt(at + 3) == 'D';
    }

    /** @return the offset after the word, or -1 when it is not there. */
    private int matchLiteral(int at, int end, String word) {
        if (at + word.length() > end) {
            return -1;
        }
        for (int k = 0; k < word.length(); k++) {
            if (text.charAt(at + k) != word.charAt(k)) {
                return -1;
            }
        }
        return at + word.length();
    }

    private int skipInline(int at, int end) {
        while (at < end && (text.charAt(at) == ' ' || text.charAt(at) == '\t')) {
            at++;
        }
        return at;
    }

    /** End of the comment's text, without the line break the token includes. */
    private int lineEnd(Token token) {
        int end = token.end;
        while (end > token.start) {
            char c = text.charAt(end - 1);
            if (c != '\n' && c != '\r') {
                break;
            }
            end--;
        }
        return end;
    }

    private boolean hasLineBreak(Token token) {
        for (int at = token.start; at < token.end; at++) {
            char c = text.charAt(at);
            if (c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }
}
