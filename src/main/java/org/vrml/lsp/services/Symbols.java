package org.vrml.lsp.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.semantic.Members;
import org.vrml.lsp.semantic.Members.Member;
import org.vrml.lsp.semantic.SymbolTable;
import org.vrml.lsp.spec.Spec;

/**
 * The document's own tree of names, for an outline.
 *
 * <p>What appears here is what the file declared, in the shape the file put it in: a node under the
 * field that holds it, a field under its node, a {@code PROTO} with its interface above its body, and
 * every {@code ROUTE} where it is written. Nothing is grouped by type and nothing is inferred, since
 * an author reads this list to find the line they wrote, and a list that rearranged the scene would
 * make it slower to find than scrolling is.
 *
 * <p>{@code USE} is the one statement left out: it names a node defined elsewhere, so showing it here
 * would duplicate a name that going to its definition already reaches.
 */
public final class Symbols {

    /** What kind of declaration a line of the file is, so the client can pick an icon and a wording. */
    public enum Kind {
        NODE, FIELD, PROTO, EXTERN_PROTO, INTERFACE, ROUTE,
    }

    /**
     * @param detail how the line is typed - the node a {@code DEF} names, the declared type of a
     *        field, the access and type of an interface line - or what a {@code ROUTE} connects;
     *        empty when the file is all that knows the line and it says no more
     * @param start first offset of the declaration, which is what the client folds and highlights
     * @param end one past its last written character
     * @param nameStart first offset of the name itself, which is what the client shows selected
     * @param nameEnd one past it
     * @param children what the declaration holds, in document order
     */
    public record Symbol(String name, String detail, Kind kind, int start, int end, int nameStart,
                         int nameEnd, List<Symbol> children) {
    }

    private final ParseResult parse;
    private final SymbolTable symbols;
    private final Spec spec;

    private Symbols(ParseResult parse, SymbolTable symbols, Spec spec) {
        this.parse = parse;
        this.symbols = symbols;
        this.spec = spec;
    }

    /** Every declaration of the document, nested as the scene nests them. */
    public static List<Symbol> outline(ParseResult parse, SymbolTable symbols, Spec spec) {
        List<Symbol> found = new ArrayList<>();
        new Symbols(parse, symbols, spec).collect(parse.root(), SymbolTable.FILE_SCOPE, List.of(),
                found);
        return found;
    }

    /**
     * One node of the tree, either reported as a symbol of its own or walked through.
     *
     * @param members the names of the node this statement stands inside, which is what gives a field
     *        line its type; empty wherever nothing is being written to
     */
    private void collect(CstNode node, int scope, List<Member> members, List<Symbol> into) {
        switch (node.kind()) {
            case NODE_STATEMENT -> {
                CstNode defined = node.firstChildOf(CstKind.DEF_CLAUSE);
                CstNode built = node.firstChildOf(CstKind.NODE);
                if (defined != null && built != null) {
                    add(nodeSymbol(built, defined, node, scope), into);
                } else {
                    children(node, scope, members, into);
                }
            }
            case NODE -> add(nodeSymbol(node, null, node, scope), into);
            case PROTO_DECL, EXTERN_PROTO_DECL -> add(prototype(node, scope), into);
            case INTERFACE_DECL -> add(interfaceLine(node, scope), into);
            case FIELD_ASSIGNMENT -> add(assignment(node, scope, members), into);
            case SCRIPT_ELEMENT -> add(element(node, scope), into);
            case ROUTE_DECL -> add(route(node), into);
            // The kinds that hold declarations without being one: the walk goes straight through.
            default -> children(node, scope, members, into);
        }
    }

    private void children(CstNode node, int scope, List<Member> members, List<Symbol> into) {
        for (CstNode child : node.children()) {
            collect(child, scope, members, into);
        }
    }

    private void add(Symbol symbol, List<Symbol> into) {
        if (symbol != null) {
            into.add(symbol);
        }
    }

    // ---- nodes -------------------------------------------------------------------------------

    /**
     * One node of the scene.
     *
     * @param defClause the {@code DEF} in front of it, whose name becomes the label, or null for a
     *        node nobody named
     * @param range the whole {@code NODE_STATEMENT} when there is a {@code DEF}, so that folding the
     *        outline's line hides the name with it, and the node itself otherwise
     */
    private Symbol nodeSymbol(CstNode node, CstNode defClause, CstNode range, int scope) {
        String type = SymbolTable.nodeType(parse, node);
        CstNode named = defClause == null ? null : usable(defClause.firstChildOf(CstKind.NODE_NAME));
        CstNode label = named != null ? named : usable(node.firstChildOf(CstKind.NODE_TYPE));
        if (label == null) {
            return null;
        }
        List<Member> own = Members.of(parse, spec, symbols, type, node, scope);
        List<Symbol> held = new ArrayList<>();
        children(node, scope, own, held);
        return symbol(parse.text(label), named != null ? type : "", Kind.NODE, range.start(),
                range.end(), label.start(), label.end(), held);
    }

    // ---- prototypes --------------------------------------------------------------------------

    /** A {@code PROTO} or {@code EXTERNPROTO}: its interface, then what its body is made of. */
    private Symbol prototype(CstNode decl, int enclosing) {
        CstNode named = usable(decl.firstChildOf(CstKind.PROTO_NAME));
        String label = named == null ? parse.text(decl).split("\\s")[0] : parse.text(named);
        int scope = bodyScope(decl, enclosing);
        List<Symbol> held = new ArrayList<>();
        for (CstNode child : decl.children()) {
            collect(child, scope, List.of(), held);
        }
        return symbol(label, "", decl.kind() == CstKind.EXTERN_PROTO_DECL ? Kind.EXTERN_PROTO
                : Kind.PROTO, decl.start(), decl.end(), named == null ? decl.start() : named.start(),
                named == null ? decl.start() + 5 : named.end(), held);
    }

    /**
     * The scope a {@code PROTO}'s body is written in, which is the scope its names are looked up in.
     *
     * <p>Read from the symbol table rather than counted here, so that the outline and the diagnostics
     * cannot end up treating two bodies as different scopes.
     */
    private int bodyScope(CstNode decl, int enclosing) {
        for (SymbolTable.Prototype prototype : symbols.prototypes()) {
            if (prototype.decl() == decl) {
                return prototype.bodyScope();
            }
        }
        return enclosing;
    }

    /** One line of a {@code PROTO} interface: the name it declares, with its own default below it. */
    private Symbol interfaceLine(CstNode decl, int scope) {
        CstNode named = usable(decl.firstChildOf(CstKind.FIELD_NAME));
        if (named == null) {
            return null;
        }
        List<Symbol> held = new ArrayList<>();
        children(decl, scope, List.of(), held);
        return symbol(parse.text(named), said(decl, CstKind.ACCESS_TYPE, CstKind.FIELD_TYPE),
                Kind.INTERFACE, decl.start(), decl.end(), named.start(), named.end(), held);
    }

    // ---- fields ------------------------------------------------------------------------------

    /** A node body's line that gives a field a value, with the nodes written into it below it. */
    private Symbol assignment(CstNode node, int scope, List<Member> members) {
        CstNode named = usable(node.firstChildOf(CstKind.FIELD_NAME));
        if (named == null) {
            return null;
        }
        String written = parse.text(named);
        CstNode binding = node.firstChildOf(CstKind.IS_CLAUSE);
        String detail;
        if (binding != null) {
            List<CstNode> sides = binding.childrenOf(CstKind.FIELD_NAME);
            detail = sides.isEmpty() ? "IS" : "IS " + parse.text(sides.get(sides.size() - 1));
        } else {
            detail = Members.find(members, written).map(Member::type).orElse("");
        }
        List<Symbol> held = new ArrayList<>();
        children(node, scope, List.of(), held);
        return symbol(written, detail, Kind.FIELD, node.start(), node.end(), named.start(),
                named.end(), held);
    }

    /** One {@code field}/{@code eventIn}/{@code eventOut} line of a {@code Script}'s body. */
    private Symbol element(CstNode node, int scope) {
        CstNode named = usable(node.firstChildOf(CstKind.FIELD_NAME));
        if (named == null) {
            return null;
        }
        List<Symbol> held = new ArrayList<>();
        children(node, scope, List.of(), held);
        return symbol(parse.text(named), said(node, CstKind.ACCESS_TYPE, CstKind.FIELD_TYPE),
                Kind.FIELD, node.start(), node.end(), named.start(), named.end(), held);
    }

    // ---- routes ------------------------------------------------------------------------------

    /**
     * One {@code ROUTE}, named by both of its ends.
     *
     * <p>A route has no name of its own, and the two endpoint names are the whole of what it says, so
     * they become the label. The arrow is the one character that cannot be mistaken for part of a
     * name.
     */
    private Symbol route(CstNode decl) {
        List<CstNode> names = decl.childrenOf(CstKind.NODE_NAME);
        List<CstNode> fields = decl.childrenOf(CstKind.FIELD_NAME);
        if (names.size() != 2 || fields.size() != 2) {
            // Wiring the file never finished: keep the line where it belongs, name it by the keyword.
            return symbol("ROUTE", "", Kind.ROUTE, decl.start(), decl.end(), decl.start(),
                    decl.start() + 5, List.of());
        }
        String label = parse.text(names.get(0)) + "." + parse.text(fields.get(0)) + " → "
                + parse.text(names.get(1)) + "." + parse.text(fields.get(1));
        return symbol(label, "", Kind.ROUTE, decl.start(), decl.end(), decl.start(), tight(decl.end()),
                List.of());
    }

    // ---- wording -----------------------------------------------------------------------------

    /** The two words that type a declaration line, in the order the file wrote them. */
    private String said(CstNode decl, CstKind first, CstKind second) {
        StringBuilder out = new StringBuilder();
        for (CstKind kind : List.of(first, second)) {
            CstNode word = usable(decl.firstChildOf(kind));
            if (word != null) {
                out.append(out.length() == 0 ? "" : " ").append(parse.text(word));
            }
        }
        return out.toString();
    }

    /** The node, unless it is the hole the parser left where a name should have been. */
    private static CstNode usable(CstNode node) {
        return node == null || node.kind() == CstKind.MISSING ? null : node;
    }

    private Symbol symbol(String name, String detail, Kind kind, int start, int end, int nameStart,
                          int nameEnd, List<Symbol> children) {
        List<Symbol> held = children.stream().sorted(Comparator.comparingInt(Symbol::start)).toList();
        return new Symbol(name, detail, kind, start, tight(end), nameStart, nameEnd, held);
    }

    /**
     * The end of what was written, rather than of where the statement's node stops.
     *
     * <p>The parser gives a statement the range up to the next token so that printing cannot lose a
     * character; an outline should highlight the line, and not the space after it.
     */
    private int tight(int end) {
        CharSequence source = parse.source();
        int at = Math.min(end, source.length());
        while (at > 0 && Character.isWhitespace(source.charAt(at - 1))) {
            at--;
        }
        return at;
    }
}
