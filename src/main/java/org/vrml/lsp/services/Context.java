package org.vrml.lsp.services;

import java.util.ArrayList;
import java.util.List;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.lexer.Token;
import org.vrml.lsp.lexer.TokenType;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.semantic.SymbolTable;

/**
 * Where the cursor stands in the grammar, which is the only thing that decides what a completion
 * list may contain and what a hover is allowed to say.
 *
 * <p>Read off the CST path to the offset - the nodes that surround it and the siblings on each side
 * - rather than from a re-lex of the text before it. The parser has already worked out what each
 * token means, that a given identifier is {@code Shape}'s field name and not a node type, and a
 * state machine that inferred the same thing from characters would disagree with the diagnostics
 * about the file in front of it.
 *
 * <p>Kinds are named for what may be written at the cursor, not for the node that surrounds it,
 * because one slot has several shapes: the value of a field is a {@code VALUE} node once the parser
 * has read one, and a gap in a {@code NODE_BODY} while the name in front of it is still being
 * typed. Both answer {@link Kind#FIELD_VALUE}, so the provider has one case instead of two.
 *
 * @param container the syntactic unit the candidates are read from: the assignment whose value is
 *        wanted, the {@code ROUTE} whose end is being written, the clause a name belongs to
 * @param node the innermost node the cursor stands in, so a field name knows whose fields they are;
 *        {@code null} at the file root and at a PROTO body's top level
 * @param routeSubject the node name written before the {@code .} of a ROUTE, empty elsewhere
 * @param defining the PROTO whose body the cursor is in, whose interface an {@code IS} may name
 * @param scope the {@link SymbolTable} scope of the cursor, for DEF and PROTO lookups
 * @param prefix what is already typed of the word being completed, for filtering
 * @param replaceStart first offset an inserted completion must overwrite
 * @param replaceEnd last offset an inserted completion must overwrite
 */
public record Context(Kind kind, CstNode container, CstNode node, String routeSubject,
                      SymbolTable.Prototype defining, int scope, String prefix, int replaceStart,
                      int replaceEnd) {

    /** The slots VRML97 gives a cursor, as the productions name them. */
    public enum Kind {
        /** A node statement may begin: the file's root, a PROTO body, after {@code DEF name}. */
        NODE_STATEMENT,
        /** A field name of the enclosing node. */
        FIELD_NAME,
        /** The value of the field before the cursor - where a node goes when the field holds one. */
        FIELD_VALUE,
        /** A name some {@code DEF} introduced: after {@code USE}, and both ends of a {@code ROUTE}. */
        DEFINED_NAME,
        /** The name a {@code DEF} or an interface line is introducing, which its author invents. */
        NEW_NAME,
        /** The left end of a {@code ROUTE}: what the named node sends out. */
        ROUTE_OUTPUT,
        /** The right end of a {@code ROUTE}: what the named node takes in. */
        ROUTE_INPUT,
        /** {@code field}, {@code eventIn}, {@code eventOut}, {@code exposedField}. */
        ACCESS_TYPE,
        /** One of the 21 field types, after an access type. */
        FIELD_TYPE,
        /** A name of the enclosing PROTO's interface, after {@code IS}. */
        INTERFACE_NAME,
        /** A quoted URL in an {@code EXTERNPROTO}'s list. */
        URI,
        /** Nowhere a list helps: a number, a keyword the grammar already settled, a comment. */
        NONE
    }

    /**
     * What the cursor stands in.
     *
     * @param offset a character offset, the protocol position already translated by the document's
     *        line index
     */
    public static Context of(ParseResult parse, SymbolTable symbols, int offset) {
        if (inComment(parse, offset)) {
            return none(offset);
        }
        int probe = probe(parse, offset);
        List<CstNode> path = new ArrayList<>(16);
        parse.root().pathTo(probe, path);
        int at = path.size() - 1;
        CstNode last = path.get(at);
        CstNode word = last.isLeaf() && isWord(last) && offset > last.start() && offset <= last.end()
                ? last : null;
        // A leaf is never the slot: standing inside one means completing it, and standing at its
        // left edge means the slot is the list that holds it. Either way the parent decides.
        if (last.isLeaf()) {
            at--;
        }
        while (at > 0 && path.get(at).start() >= offset) {
            // A placeholder sitting exactly under the cursor says nothing about the slot; the node
            // that holds it does.
            at--;
        }
        if (word == null && (probe < offset || isClosing(parse, last))) {
            // The construct the cursor has moved past is finished, so the slot is whatever may come
            // after it, and the answer lies further out. A cursor glued to the end of a number is
            // not that: it still sits inside the value, where nothing is completable.
            while (at > 0 && !acceptsMore(parse, path.get(at), offset)) {
                at--;
            }
        }
        Context placed = decide(parse, symbols, path, at, word, offset);
        if (placed.prefix().isEmpty() && placed.replaceEnd() < offset) {
            // Nothing is being overwritten, so the insertion point is the cursor and not the end of
            // the construct before it - which may sit a whole line back in a file being edited.
            return new Context(placed.kind(), placed.container(), placed.node(), placed.routeSubject(),
                    placed.defining(), placed.scope(), "", offset, offset);
        }
        return placed;
    }

    /**
     * Where to start the descent.
     *
     * <p>The tree spans tokens, not the whitespace after them, so a cursor that has moved off the
     * end of the last character - which is where most completions are asked for - finds nothing to
     * descend into and the path collapses to the file root. Starting from the last token read puts
     * the walk back on the construct the author just wrote; the real offset still decides the rest.
     */
    private static int probe(ParseResult parse, int offset) {
        List<Token> tokens = parse.tokens();
        for (int i = Math.min(parse.tokenIndexAt(offset), tokens.size() - 1); i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.type == TokenType.EOF || token.type.isTrivia()) {
                continue;
            }
            return Math.min(offset, token.end);
        }
        return offset;
    }

    /**
     * Whether the grammar can still put something inside this node at the cursor.
     *
     * <p>What the climb out of a finished construct stops at: a list that takes another element, a
     * node whose closing brace was never written, an assignment whose value has not arrived. Past
     * those, every other node the cursor has left is complete, and the slot wanted is the one after
     * it - which is the parent's business, not its own.
     */
    private static boolean acceptsMore(ParseResult parse, CstNode node, int offset) {
        if (node.end() > offset) {
            return true;
        }
        if (closed(parse, node)) {
            return false;
        }
        return switch (node.kind()) {
            case SCENE, PROTO_BODY, NODE_BODY, SCRIPT_BODY, NODE_LIST, MF_VALUE, URI_LIST, NODE -> true;
            case ROUTE_DECL -> hasHole(node);
            case PROTO_DECL, EXTERN_PROTO_DECL -> openList(parse, node);
            case FIELD_ASSIGNMENT, SCRIPT_ELEMENT, INTERFACE_DECL -> !elementIsDone(node);
            case IS_CLAUSE -> node.firstChildOf(CstKind.FIELD_NAME) == null;
            case DEF_CLAUSE, USE_CLAUSE -> node.firstChildOf(CstKind.NODE_NAME) == null;
            default -> false;
        };
    }

    /**
     * Whether the parser had to leave a hole among this node's own children.
     *
     * <p>A {@code ROUTE} is a fixed sequence rather than a list, so it is finished exactly when
     * every part of it arrived: while one is missing the cursor standing after the last written part
     * still belongs to the route.
     */
    private static boolean hasHole(CstNode node) {
        for (CstNode child : node.children()) {
            if (child.kind() == CstKind.MISSING) {
                return true;
            }
        }
        return false;
    }

    /** Whether a {@code [} of this node's own children is still unclosed. */
    private static boolean openList(ParseResult parse, CstNode node) {
        int depth = 0;
        for (CstNode child : node.children()) {
            if (!child.isLeaf()) {
                continue;
            }
            TokenType kind = type(parse, child);
            if (kind == TokenType.LBRACKET) {
                depth++;
            } else if (kind == TokenType.RBRACKET) {
                depth--;
            }
        }
        return depth > 0;
    }

    /** Whether an interface line or assignment has everything it takes. */
    private static boolean elementIsDone(CstNode element) {
        if (element.firstChildOf(CstKind.FIELD_NAME) == null) {
            return false;
        }
        CstNode bound = element.firstChildOf(CstKind.IS_CLAUSE);
        if (bound != null) {
            return bound.firstChildOf(CstKind.FIELD_NAME) != null;
        }
        CstNode value = element.firstChildOf(CstKind.VALUE);
        return value == null || filled(value);
    }

    private static Context decide(ParseResult parse, SymbolTable symbols, List<CstNode> path, int at,
            CstNode word, int offset) {
        Scope scope = scopeOf(parse, symbols, path, at);
        Word typed = Word.of(parse, word, offset);
        CstNode inner = path.get(Math.max(at, 0));
        CstNode node = lastOf(path, at, CstKind.NODE);
        CstNode line = lastOf(path, at, CstKind.FIELD_ASSIGNMENT, CstKind.SCRIPT_ELEMENT,
                CstKind.INTERFACE_DECL);
        if (inner.kind() == CstKind.ERROR) {
            // The parser could not place this; guessing what belongs in debris is how a completion
            // list ends up suggesting things the file cannot hold.
            return none(offset);
        }
        if (word != null) {
            return onWord(parse, inner, word, typed, node, line, scope);
        }
        CstNode left = lastBefore(inner, offset);
        return switch (inner.kind()) {
            case SCENE, PROTO_BODY, STATEMENT, NODE_STATEMENT -> statements(inner, left, line, scope);
            case NODE -> inNode(inner, left, parse, line, scope);
            case NODE_BODY, SCRIPT_BODY -> inBody(inner, left, node, parse, line, scope);
            case FIELD_ASSIGNMENT, SCRIPT_ELEMENT, INTERFACE_DECL -> inLine(inner, left, node, scope);
            case VALUE, SF_VALUE, MF_VALUE, NODE_LIST -> value(inner, line, node, scope);
            case LITERAL_VALUE, NUMBER_ARRAY, STRING_ARRAY -> none(offset);
            case DEF_CLAUSE -> afterDef(inner, left, scope);
            case USE_CLAUSE -> defined(Word.of(offset), inner, "", scope);
            case IS_CLAUSE -> with(Kind.INTERFACE_NAME, Word.of(leftEnd(left, offset)), line, node, "",
                    scope);
            case ROUTE_DECL -> route(parse, inner, left, scope);
            case URI_LIST -> with(Kind.URI, Word.of(offset), inner, node, "", scope);
            case PROTO_DECL, EXTERN_PROTO_DECL -> inProto(parse, inner, left, line, node, scope,
                    offset);
            default -> none(offset);
        };
    }

    /**
     * In a {@code PROTO}, where the interface list and the body are two different questions.
     *
     * <p>Between the brackets an access type is what fits; past the {@code &#123;} the body's node
     * statements are. Which side of the brace the cursor is on is read off the sibling it came past,
     * because a body with nothing written in it yet spans no tokens at all: the descent cannot reach
     * inside a gap of no width, so a cursor standing in one is taken to stand after the empty body
     * itself, which is the nearest thing to inside it the tree has.
     */
    private static Context inProto(ParseResult parse, CstNode decl, CstNode left, CstNode line,
            CstNode node, Scope scope, int offset) {
        CstNode body = decl.firstChildOf(CstKind.PROTO_BODY);
        if (body != null && !openList(parse, decl)
                && (left == body || left != null && type(parse, left) == TokenType.LBRACE)) {
            return statements(body, left, line, scope);
        }
        return with(Kind.ACCESS_TYPE, Word.of(offset), decl, node, "", scope);
    }

    /**
     * The cursor is on a word the grammar named.
     *
     * <p>Which word it is answers the question outright: a {@code FIELD_NAME} leaf can only be a
     * field name, so a user typing there is completing one, whatever else is on the line.
     */
    private static Context onWord(ParseResult parse, CstNode inner, CstNode word, Word typed,
            CstNode node, CstNode line, Scope scope) {
        return switch (word.kind()) {
            case NODE_TYPE, IDENTIFIER -> with(Kind.NODE_STATEMENT, typed, line, node, "", scope);
            case FIELD_NAME -> {
                if (inner.kind() == CstKind.ROUTE_DECL) {
                    yield routeField(parse, inner, word, typed, scope);
                }
                if (inner.kind() == CstKind.IS_CLAUSE) {
                    yield with(Kind.INTERFACE_NAME, typed, line, node, "", scope);
                }
                yield with(Kind.FIELD_NAME, typed, line, node, "", scope);
            }
            case FIELD_TYPE -> with(Kind.FIELD_TYPE, typed, line, node, "", scope);
            case PROTO_NAME -> with(Kind.NEW_NAME, typed, inner, node, "", scope);
            case NODE_NAME -> nodeName(inner, typed, scope);
            case ACCESS_TYPE -> type(parse, word) == TokenType.KW_IS
                    ? with(Kind.INTERFACE_NAME, typed, line, node, "", scope)
                    : with(Kind.FIELD_TYPE, typed, line, node, "", scope);
            case STRING -> inner.kind() == CstKind.URI_LIST
                    ? with(Kind.URI, typed, inner, node, "", scope)
                    : none(typed.start());
            default -> none(typed.start());
        };
    }

    /**
     * A name being typed.
     *
     * <p>{@code USE} and both ends of a {@code ROUTE} take names the file already has, which is what
     * makes a list worth showing while the name is half-written; a {@code DEF} introduces a new one,
     * and inventing candidates for it would only get in the way.
     */
    private static Context nodeName(CstNode inner, Word typed, Scope scope) {
        if (inner.kind() == CstKind.ROUTE_DECL || inner.kind() == CstKind.USE_CLAUSE) {
            return defined(typed, inner, "", scope);
        }
        return with(Kind.NEW_NAME, typed, inner, null, "", scope);
    }

    // ---- the gap the cursor stands in ----------------------------------------------------------

    /** Root, PROTO body, or between two statements: a node statement is what fits. */
    private static Context statements(CstNode inner, CstNode left, CstNode line, Scope scope) {
        if (isUse(left)) {
            // A USE is a whole statement; nothing may follow it on the same one.
            return none(endOf(left));
        }
        return with(Kind.NODE_STATEMENT, Word.of(leftEnd(left, inner)), line, null, "", scope);
    }

    /**
     * Whether the statement the cursor stands after is a {@code USE} that has its name.
     *
     * <p>Reached through the {@code statement}/{@code nodeStatement} wrappers, because the file root
     * holds those rather than the clause itself.
     */
    private static boolean isUse(CstNode element) {
        CstNode at = element;
        while (at != null && (at.kind() == CstKind.STATEMENT || at.kind() == CstKind.NODE_STATEMENT)) {
            at = lastChild(at);
        }
        return at != null && at.kind() == CstKind.USE_CLAUSE
                && at.firstChildOf(CstKind.NODE_NAME) != null;
    }

    /**
     * In a {@code NODE} that has not opened its body, or in one whose body is already finished.
     *
     * <p>Both answers come from the sibling the cursor stands after: after the {@code &#123;} the
     * body's field names are next, and after a complete body the node is done, so the only place
     * left to be is the enclosing list - which the climb out of a closing brace already found.
     */
    private static Context inNode(CstNode inner, CstNode left, ParseResult parse, CstNode line,
            Scope scope) {
        if (left == null) {
            return with(Kind.FIELD_NAME, Word.of(inner.start()), line, inner, "", scope);
        }
        TokenType kind = type(parse, left);
        if (kind == TokenType.LBRACE || left.kind() == CstKind.NODE_BODY
                || left.kind() == CstKind.SCRIPT_BODY) {
            return with(Kind.FIELD_NAME, Word.of(left.end()), line, inner, "", scope);
        }
        return none(left.end());
    }

    /**
     * Inside a {@code nodeBody} or {@code scriptBody}.
     *
     * <p>What follows a finished element is another field name; what follows a field name with no
     * value yet is that field's value. Whether the value arrived is read from the tree instead of
     * guessed from the cursor's column, so an unfinished {@code translation 1 2} - two numbers where
     * three belong - counts as having one and offers the next field name, which is what a user
     * typing on after a wrong number is asking for.
     */
    private static Context inBody(CstNode inner, CstNode left, CstNode node, ParseResult parse,
            CstNode line, Scope scope) {
        CstNode owner = node != null ? node : inner;
        if (left == null) {
            return with(Kind.FIELD_NAME, Word.of(inner.start()), line, owner, "", scope);
        }
        if (left.kind() == CstKind.FIELD_ASSIGNMENT || left.kind() == CstKind.SCRIPT_ELEMENT
                || left.kind() == CstKind.INTERFACE_DECL) {
            return afterAssignment(left, owner, scope);
        }
        return with(Kind.FIELD_NAME, Word.of(left.end()), line, owner, "", scope);
    }

    private static Context afterAssignment(CstNode element, CstNode node, Scope scope) {
        if (elementIsDone(element)) {
            return with(Kind.FIELD_NAME, Word.of(endOf(element)), node, node, "", scope);
        }
        return with(Kind.FIELD_VALUE, Word.of(endOf(element)), element, node, "", scope);
    }

    /** The cursor is inside one assignment's own span. */
    private static Context inLine(CstNode inner, CstNode left, CstNode node, Scope scope) {
        if (left == null) {
            return with(Kind.FIELD_NAME, Word.of(inner.start()), inner, node, "", scope);
        }
        return switch (left.kind()) {
            case FIELD_NAME -> with(Kind.FIELD_VALUE, Word.of(left.end()), inner, node, "", scope);
            case ACCESS_TYPE -> with(Kind.FIELD_TYPE, Word.of(left.end()), inner, node, "", scope);
            case FIELD_TYPE -> with(Kind.NEW_NAME, Word.of(left.end()), inner, node, "", scope);
            case IS_CLAUSE -> with(Kind.INTERFACE_NAME, Word.of(left.end()), inner, node, "", scope);
            default -> with(Kind.FIELD_VALUE, Word.of(left.end()), inner, node, "", scope);
        };
    }

    /** The value of a field, which is where a node statement goes when the field holds nodes. */
    private static Context value(CstNode inner, CstNode line, CstNode node, Scope scope) {
        return with(Kind.FIELD_VALUE, Word.of(Math.max(inner.start(), 0)), line, node, "", scope);
    }

    /** {@code DEF} with its keyword written and its name not yet there. */
    private static Context afterDef(CstNode inner, CstNode left, Scope scope) {
        if (left != null && left.kind() == CstKind.NODE_NAME) {
            return with(Kind.NODE_STATEMENT, Word.of(left.end()), null, null, "", scope);
        }
        return with(Kind.NEW_NAME, Word.of(leftEnd(left, inner.start())), inner, null, "", scope);
    }

    // ---- ROUTE ---------------------------------------------------------------------------------

    /**
     * Which end of a {@code ROUTE} the cursor stands on.
     *
     * <p>The two ends take different lists - one names what sends, the other what receives - and the
     * {@code TO} keyword is the boundary between them, so where the last token before the cursor
     * falls relative to that keyword is the whole question. A route may be written before the nodes
     * it wires, so the names offered are every definition in scope, not only the earlier ones.
     */
    private static Context route(ParseResult parse, CstNode decl, CstNode left, Scope scope) {
        int to = indexOfTo(parse, decl);
        if (left == null) {
            return defined(Word.of(decl.start()), decl, "", scope);
        }
        boolean afterTo = to >= 0 && indexOf(decl, left) > to;
        if (left.kind() == CstKind.KEYWORD) {
            return switch (type(parse, left)) {
                case KW_ROUTE, KW_TO -> defined(Word.of(left.end()), decl, subject(parse, decl, afterTo),
                        scope);
                case DOT -> with(afterTo ? Kind.ROUTE_INPUT : Kind.ROUTE_OUTPUT, Word.of(left.end()),
                        decl, null, subject(parse, decl, afterTo), scope);
                default -> none(left.end());
            };
        }
        if (left.kind() == CstKind.FIELD_NAME) {
            return routeField(parse, decl, left, Word.of(left.end()), scope);
        }
        return none(left.end());
    }

    private static Context routeField(ParseResult parse, CstNode decl, CstNode word, Word typed,
            Scope scope) {
        int to = indexOfTo(parse, decl);
        boolean afterTo = to >= 0 && indexOf(decl, word) > to;
        return with(afterTo ? Kind.ROUTE_INPUT : Kind.ROUTE_OUTPUT, typed, decl, null,
                subject(parse, decl, afterTo), scope);
    }

    /**
     * The node name whose members are being listed.
     *
     * <p>The second one in a route once {@code TO} has been read, the first before it. A route with
     * no {@code TO} yet has one name, so asking for the second is answered with the first rather
     * than with nothing.
     */
    private static String subject(ParseResult parse, CstNode decl, boolean afterTo) {
        List<CstNode> names = decl.childrenOf(CstKind.NODE_NAME);
        if (names.isEmpty()) {
            return "";
        }
        CstNode name = afterTo && names.size() > 1 ? names.get(1) : names.get(0);
        return name.kind() == CstKind.MISSING ? "" : parse.text(name);
    }

    private static int indexOfTo(ParseResult parse, CstNode decl) {
        List<CstNode> children = decl.children();
        for (int i = 0; i < children.size(); i++) {
            CstNode child = children.get(i);
            if (child.kind() == CstKind.KEYWORD && type(parse, child) == TokenType.KW_TO) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(CstNode parent, CstNode child) {
        return parent.children().indexOf(child);
    }

    // ---- reading the tree ----------------------------------------------------------------------

    /** The last element of the path, up to {@code at}, that is one of {@code wanted}. */
    private static CstNode lastOf(List<CstNode> path, int at, CstKind... wanted) {
        for (int i = Math.min(at, path.size() - 1); i >= 0; i--) {
            for (CstKind kind : wanted) {
                if (path.get(i).kind() == kind) {
                    return path.get(i);
                }
            }
        }
        return null;
    }

    /** The last child of {@code parent} that began before the cursor; null if none did. */
    private static CstNode lastBefore(CstNode parent, int offset) {
        CstNode found = null;
        for (CstNode child : parent.children()) {
            if (child.kind() == CstKind.MISSING || child.start() < 0 || child.start() >= offset) {
                continue;
            }
            if (found == null || child.start() >= found.start()) {
                found = child;
            }
        }
        return found;
    }

    /** The child a node would have to be closed by: its last one that holds anything. */
    private static CstNode lastChild(CstNode parent) {
        CstNode found = null;
        for (CstNode child : parent.children()) {
            if (child.kind() != CstKind.MISSING && child.start() >= 0) {
                found = child;
            }
        }
        return found;
    }

    /** Whether the node's own last token closes it: {@code &#125;} or {@code ]}. */
    private static boolean closed(ParseResult parse, CstNode node) {
        return isClosing(parse, lastChild(node));
    }

    /** Whether a node holds anything the grammar understood, as opposed to a placeholder for it. */
    private static boolean filled(CstNode node) {
        if (node.isLeaf()) {
            return node.kind() != CstKind.MISSING;
        }
        for (CstNode child : node.children()) {
            if (filled(child)) {
                return true;
            }
        }
        return false;
    }

    /** Words a user may still be typing; a brace or a settled keyword is not one. */
    private static boolean isWord(CstNode leaf) {
        return switch (leaf.kind()) {
            case NODE_NAME, PROTO_NAME, FIELD_NAME, NODE_TYPE, FIELD_TYPE, IDENTIFIER, ACCESS_TYPE,
                    STRING -> true;
            default -> false;
        };
    }

    /** Whether the leaf the cursor stands on closes something: {@code &#125;} or {@code ]}. */
    private static boolean isClosing(ParseResult parse, CstNode leaf) {
        if (leaf == null || !leaf.isLeaf()) {
            return false;
        }
        TokenType type = type(parse, leaf);
        return type == TokenType.RBRACE || type == TokenType.RBRACKET;
    }

    /**
     * The token type a leaf holds.
     *
     * <p>Read back out of the token stream through the leaf's index, because {@code KEYWORD} as a
     * node kind says only that the grammar reserved the word, not which one - and telling the two
     * ends of a {@code ROUTE} apart turns on knowing where {@code TO} was.
     */
    private static TokenType type(ParseResult parse, CstNode leaf) {
        int index = leaf.tokenIndex();
        List<Token> tokens = parse.tokens();
        return index < 0 || index >= tokens.size() ? TokenType.EOF : tokens.get(index).type;
    }

    private static int leftEnd(CstNode left, int fallback) {
        return left == null ? fallback : left.end();
    }

    private static int leftEnd(CstNode left, CstNode inner) {
        return leftEnd(left, Math.max(inner.start(), 0));
    }

    /** Where a node ends, never before the start of the document. */
    private static int endOf(CstNode node) {
        return Math.max(node.end(), 0);
    }

    /**
     * The scope the cursor stands in, and the PROTO that owns it when it is not the file's.
     *
     * <p>Package-private because {@link AtOffset} answers the same question for a hover or a jump,
     * and two rules for "whose body is this" would eventually disagree about a {@code PROTO}.
     */
    static Scope scopeOf(ParseResult parse, SymbolTable symbols, List<CstNode> path, int at) {
        int scope = SymbolTable.FILE_SCOPE;
        SymbolTable.Prototype defining = null;
        for (int i = 1; i <= Math.min(at, path.size() - 1); i++) {
            if (path.get(i).kind() != CstKind.PROTO_BODY
                    || path.get(i - 1).kind() != CstKind.PROTO_DECL) {
                continue;
            }
            CstNode name = path.get(i - 1).firstChildOf(CstKind.PROTO_NAME);
            if (name == null || name.kind() == CstKind.MISSING) {
                continue;
            }
            SymbolTable.Prototype prototype = symbols.prototype(scope, parse.text(name));
            if (prototype != null) {
                scope = prototype.bodyScope();
                defining = prototype;
            }
        }
        return new Scope(scope, defining);
    }

    /** True when the cursor lies inside a comment, where no completion belongs. */
    private static boolean inComment(ParseResult parse, int offset) {
        CharSequence source = parse.source();
        for (Token token : parse.tokens()) {
            if (token.start >= offset) {
                return false;
            }
            if (token.type != TokenType.COMMENT) {
                continue;
            }
            // A comment reaches the end of its line whatever the token holds, so a cursor in the
            // white space after it is still writing prose rather than a scene. A token that ate its
            // own newline, as the `#VRML` header does, stops there: the next line is code.
            int covered = token.end;
            while (covered > token.start && source.charAt(covered - 1) == '\n') {
                covered--;
            }
            while (covered < source.length() && source.charAt(covered) != '\n') {
                covered++;
            }
            if (offset <= covered) {
                return true;
            }
        }
        return false;
    }

    // ---- building the answer -------------------------------------------------------------------

    private static Context defined(Word word, CstNode clause, String subject, Scope scope) {
        return with(Kind.DEFINED_NAME, word, clause, null, subject, scope);
    }

    private static Context with(Kind kind, Word word, CstNode container, CstNode node, String subject,
            Scope scope) {
        return new Context(kind, container, node, subject, scope.defining(), scope.scope(),
                word.prefix(), word.start(), word.end());
    }

    private static Context none(int offset) {
        return new Context(Kind.NONE, null, null, "", null, SymbolTable.FILE_SCOPE, "", offset, offset);
    }

    /** The text at the cursor that a completion replaces, and the span it replaces. */
    private record Word(String prefix, int start, int end) {

        static Word of(int offset) {
            return new Word("", offset, offset);
        }

        /**
         * @param word the leaf the cursor stands on, or null for a gap between two tokens
         */
        static Word of(ParseResult parse, CstNode word, int offset) {
            if (word == null) {
                return new Word("", offset, offset);
            }
            String text = parse.text(word);
            if (word.kind() == CstKind.STRING) {
                // The quotes are not part of what the author is choosing, so they stay where they
                // are and only the text between them is replaced.
                int open = text.startsWith("\"") ? 1 : 0;
                int close = text.length() > open && text.endsWith("\"") ? 1 : 0;
                int upTo = Math.min(offset, word.end() - close) - word.start();
                return new Word(text.substring(open, Math.max(open, upTo)), word.start() + open,
                        word.end() - close);
            }
            return new Word(text.substring(0, offset - word.start()), word.start(), word.end());
        }
    }

    /** Two facts about where the cursor is, as far as names are concerned. */
    record Scope(int scope, SymbolTable.Prototype defining) {
    }
}
