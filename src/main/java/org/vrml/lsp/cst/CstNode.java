package org.vrml.lsp.cst;

import java.util.ArrayList;
import java.util.List;

import org.vrml.lsp.lexer.Token;
import org.vrml.lsp.lexer.TokenType;

/**
 * A concrete syntax node: kind, char span, ordered children.
 *
 * <p>Design note - the tree stores <em>ranges over a flat token array</em> rather than the
 * tokens themselves. Token spans tile the whole document (trivia included), so the tree
 * does not have to hold text for a byte-exact print; keeping structure as
 * {@code [startToken, endToken)} instead makes node objects small, lets consumers resolve
 * offsets without walking parents, and lets incremental re-parse reuse untouched subtrees.
 *
 * <p>Spans of siblings may abut or contain trivia between them; they never overlap for
 * significant tokens, which is all position lookup needs.
 */
public final class CstNode {

    private final CstKind kind;
    private final int tokenIndex;
    private int start;
    private int end;
    private ArrayList<CstNode> children;

    private CstNode(CstKind kind, int tokenIndex, int start, int end) {
        this.kind = kind;
        this.tokenIndex = tokenIndex;
        this.start = start;
        this.end = end;
    }

    /** Composite node; range is filled in by the parser once complete. */
    public static CstNode composite(CstKind kind) {
        return new CstNode(kind, -1, -1, -1);
    }

    public static CstNode leaf(CstKind kind, int tokenIndex, Token token) {
        return new CstNode(kind, tokenIndex, token.start, token.end);
    }

    /** Zero-width placeholder marking an expected-but-absent token. */
    public static CstNode missing(int offset) {
        return new CstNode(CstKind.MISSING, -1, offset, offset);
    }

    public CstKind kind() {
        return kind;
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    public boolean isLeaf() {
        return tokenIndex >= 0;
    }

    /** Index into the token array, or -1 for composites and {@link CstKind#MISSING}. */
    public int tokenIndex() {
        return tokenIndex;
    }

    public int childCount() {
        return children == null ? 0 : children.size();
    }

    public CstNode child(int i) {
        return children.get(i);
    }

    public List<CstNode> children() {
        return children == null ? List.of() : children;
    }

    public void addChild(CstNode child) {
        if (children == null) {
            children = new ArrayList<>(4);
        }
        children.add(child);
        if (child.start < 0) {
            // A composite that ended up with nothing in it - `Sphere {}` has an empty body - has no
            // position of its own. Folding -1 into the parent would move the parent's start to the
            // next token, which is how every node with an empty body became unfindable by cursor.
            return;
        }
        if (start < 0) {
            start = child.start;
            end = child.end;
            return;
        }
        if (child.kind == CstKind.MISSING) {
            // A hole must not stretch the span of its parent; consumers see the child.
            return;
        }
        start = Math.min(start, child.start);
        end = Math.max(end, child.end);
    }

    public void setRange(int start, int end) {
        this.start = start;
        this.end = end;
    }

    public boolean contains(int offset) {
        return start >= 0 && offset >= start && offset <= end;
    }

    /** Direct children of the given kind; allocation-free for the common empty case. */
    public List<CstNode> childrenOf(CstKind wanted) {
        List<CstNode> found = null;
        for (int i = 0; i < childCount(); i++) {
            CstNode c = child(i);
            if (c.kind == wanted) {
                if (found == null) {
                    found = new ArrayList<>(2);
                }
                found.add(c);
            }
        }
        return found == null ? List.of() : found;
    }

    public CstNode firstChildOf(CstKind wanted) {
        for (int i = 0; i < childCount(); i++) {
            CstNode c = child(i);
            if (c.kind == wanted) {
                return c;
            }
        }
        return null;
    }

    /**
     * Innermost node whose span covers {@code offset}.
     *
     * <p>Used by hover, completion and definition: they all start from "what was under the
     * cursor" and then climb by kind.
     */
    public CstNode nodeAt(int offset) {
        CstNode best = this;
        for (int i = 0; i < childCount(); i++) {
            CstNode c = child(i);
            if (c.contains(offset)) {
                CstNode deeper = c.nodeAt(offset);
                if (deeper != null && (deeper.end - deeper.start) <= (best.end - best.start)) {
                    best = deeper;
                }
            }
        }
        return best;
    }

    /** Climb to the nearest ancestor-or-self of {@code wanted}; null when none. */
    public static CstNode ancestor(List<CstNode> path, CstKind wanted) {
        for (int i = path.size() - 1; i >= 0; i--) {
            CstNode n = path.get(i);
            if (n.kind == wanted) {
                return n;
            }
        }
        return null;
    }

    /** Root-to-node path for an offset; the completion context machine walks this. */
    public void pathTo(int offset, List<CstNode> out) {
        out.add(this);
        if (isLeaf()) {
            return;
        }
        for (int i = 0; i < childCount(); i++) {
            CstNode c = child(i);
            if (c.contains(offset)) {
                c.pathTo(offset, out);
                return;
            }
        }
    }

    /**
     * Rebuild the document from a tree, byte for byte.
     *
     * <p>Leaves carry only significant tokens, so the whitespace and comments between two
     * siblings are re-emitted from the flat stream as the cursor sweeps forward. A leaf that
     * is out of order, duplicated or missing therefore cannot be papered over: it shows up
     * both as a text difference and as an entry in {@code holes}, which is what the
     * round-trip test asserts against.
     */
    public static String printLossless(CstNode root, CharSequence source, List<Token> tokens,
            List<String> holes) {
        PrintState st = new PrintState(source, tokens, holes);
        root.appendLossless(st);
        st.flushTo(tokens.size());
        st.out.append(source, st.offset, source.length());
        return st.out.toString();
    }

    private void appendLossless(PrintState st) {
        if (isLeaf()) {
            if (tokenIndex < st.cursorIndex) {
                st.holes.add("token " + tokenIndex + " is out of order or duplicated (" + kind + ")");
                return;
            }
            st.flushTo(tokenIndex);
            Token t = st.tokens.get(tokenIndex);
            st.cursorIndex = tokenIndex + 1;
            st.out.append(st.source, t.start, t.end);
            st.offset = t.end;
            return;
        }
        for (int i = 0; i < childCount(); i++) {
            child(i).appendLossless(st);
        }
    }

    private static final class PrintState {
        final CharSequence source;
        final List<Token> tokens;
        final List<String> holes;
        final StringBuilder out;
        /** Index of the next token to emit, and how far the output text reaches. */
        int cursorIndex;
        int offset;

        PrintState(CharSequence source, List<Token> tokens, List<String> holes) {
            this.source = source;
            this.tokens = tokens;
            this.holes = holes;
            this.out = new StringBuilder(Math.max(16, source.length()));
        }

        /** Emit the trivia lying between the cursor and {@code target}, flagging anything else. */
        void flushTo(int target) {
            for (int t = cursorIndex; t < target; t++) {
                Token tok = tokens.get(t);
                if (tok.type == TokenType.EOF) {
                    break;
                }
                if (!tok.type.isTrivia()) {
                    holes.add("token " + t + " " + tok.type + " is not in the tree");
                }
                out.append(source, tok.start, tok.end);
                offset = tok.end;
            }
        }
    }

    @Override
    public String toString() {
        return kind + "(" + start + ".." + end + ")" + (childCount() > 0 ? " x" + childCount() : "");
    }
}
