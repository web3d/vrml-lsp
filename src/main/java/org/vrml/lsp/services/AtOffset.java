package org.vrml.lsp.services;

import java.util.ArrayList;
import java.util.List;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.semantic.SymbolTable;

/**
 * The token a pointer is on, and the constructs holding it.
 *
 * <p>Hover, {@code definition} and {@code references} all start from the same question - what is
 * under this position - and it is not the question {@link Context} asks. A completion is about a
 * slot the author is about to fill, so a cursor sitting just past the end of a token has already
 * left that slot and the answer lies further out; a pointer one past the last letter of a name
 * still means that name. So this stops at the leaf instead of climbing away from it, and leaves the
 * reading of it to the caller.
 *
 * <p>Ties are broken to the left, which is what a pointer between two words asks for: the tree's
 * spans are inclusive at both ends, so the offset between {@code Box} and its {@code &#123;} lies in
 * both, and the descent takes the child written first.
 */
final class AtOffset {

    private final ParseResult parse;
    private final int offset;
    private final List<CstNode> path;

    private AtOffset(ParseResult parse, int offset, List<CstNode> path) {
        this.parse = parse;
        this.offset = offset;
        this.path = path;
    }

    static AtOffset of(ParseResult parse, int offset) {
        List<CstNode> path = new ArrayList<>(16);
        parse.root().pathTo(Math.min(Math.max(offset, 0), parse.source().length()), path);
        return new AtOffset(parse, offset, path);
    }

    ParseResult parse() {
        return parse;
    }

    int offset() {
        return offset;
    }

    /** The token under the position, or null in whitespace the tree does not own. */
    CstNode leaf() {
        CstNode last = path.get(path.size() - 1);
        return last.isLeaf() ? last : null;
    }

    /** What kind of token that is, or {@code null} when the position is between two. */
    CstKind kind() {
        CstNode leaf = leaf();
        return leaf == null ? null : leaf.kind();
    }

    /** The text of that token, or "" when there is none. */
    String text() {
        CstNode leaf = leaf();
        return leaf == null ? "" : parse.text(leaf);
    }

    /** Whether the position is on anything with a name a user could be asking about. */
    boolean onWord() {
        CstNode leaf = leaf();
        if (leaf == null) {
            return false;
        }
        return switch (leaf.kind()) {
            case NODE_NAME, PROTO_NAME, FIELD_NAME, NODE_TYPE, FIELD_TYPE, ACCESS_TYPE, IDENTIFIER,
                    STRING, KEYWORD -> true;
            default -> false;
        };
    }

    /** The nearest enclosing construct of the wanted kinds, innermost first; null when none. */
    CstNode inside(CstKind... wanted) {
        for (int i = path.size() - 1; i >= 0; i--) {
            CstNode node = path.get(i);
            for (CstKind kind : wanted) {
                if (node.kind() == kind) {
                    return node;
                }
            }
        }
        return null;
    }

    /** Where the position stands as far as names are concerned: the file, or one PROTO's body. */
    Context.Scope scope(SymbolTable symbols) {
        return Context.scopeOf(parse, symbols, path, path.size() - 1);
    }
}
