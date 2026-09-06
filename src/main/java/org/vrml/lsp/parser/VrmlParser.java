package org.vrml.lsp.parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.diagnostics.Codes;
import org.vrml.lsp.diagnostics.Issue;
import org.vrml.lsp.lexer.Token;
import org.vrml.lsp.lexer.TokenType;
import org.vrml.lsp.lexer.VrmlLexer;

/**
 * Tolerant recursive-descent parser for VRML97 (Relaxed dialect).
 *
 * <p>Productions follow {@code VRML97RelaxedParser.jj} rule for rule; the ISO rule numbers
 * quoted in its javadoc appear here as {@code Rule n} comments so a reader can check them
 * side by side (see GRAMMAR-MAPPING.md for the table).
 *
 * <p>Two properties distinguish this from the generated parser it replaces:
 * <ul>
 *   <li>it never throws - an unexpected token produces an {@link Issue}, a
 *       {@link CstKind#MISSING} hole or an {@link CstKind#ERROR} run, and parsing resumes
 *       at a sync point;</li>
 *   <li>the tree keeps every token, including the ones it could not place, so the CST can
 *       always be printed back to the original bytes. Nothing is discarded, because an
 *       editor that "fixes" a file by dropping text is worse than one that underlines it.</li>
 * </ul>
 */
public final class VrmlParser {

    private final CharSequence text;
    private final List<Token> tokens;
    /** Significant tokens only - the view the grammar walks. */
    private final List<Token> sig = new ArrayList<>();
    /** sig position -> index in {@link #tokens}, so leaves can point at the real stream. */
    private final List<Integer> sigOf = new ArrayList<>();

    private int i;
    private final List<Issue> issues = new ArrayList<>();
    private final ArrayDeque<CstNode> stack = new ArrayDeque<>();
    /** Suppresses cascades: only one issue may point at a given significant token. */
    private int lastErrorSig = -1;
    /** How many {@code {} this parser has swallowed, which is how a statement proves itself. */
    private int bracesConsumed;

    private VrmlParser(CharSequence text) {
        this.text = text;
        this.tokens = VrmlLexer.tokenize(text);
        for (int t = 0; t < tokens.size(); t++) {
            Token tok = tokens.get(t);
            if (!tok.type.isTrivia()) {
                sig.add(tok);
                sigOf.add(t);
            }
        }
    }

    public static ParseResult parse(CharSequence text) {
        VrmlParser p = new VrmlParser(text);
        CstNode root = p.scene();
        return new ParseResult(root, p.tokens, p.issues, text);
    }

    //----------------------------------------------------------------- structure helpers

    /** Rule 0 - {@code vrmlScene ::= statement*} */
    private CstNode scene() {
        scanLexical();
        begin(CstKind.SCENE);
        while (!atEnd()) {
            int before = i;
            int bracesBefore = bracesConsumed;
            int issuesBefore = issues.size();
            statement();
            if (i == before) {
                recoverAtSceneLevel();
            } else if (bracesConsumed == bracesBefore && issues.size() > issuesBefore) {
                skipFallout(this::plausibleStatementStart);
            }
        }
        return end();
    }

    private void begin(CstKind kind) {
        stack.push(CstNode.composite(kind));
    }

    /**
     * Report what only the lexer can see: characters that matched no token and strings that
     * never closed. These are raised up front because the parser is free to skip the offending
     * token entirely, and a missing closing quote must still be told to the user.
     *
     * <p>Nothing else belongs here. Whether the file declares VRML97 at all is a question about
     * the header, not about the grammar - {@link org.vrml.lsp.diagnostics.HeaderCheck} answers
     * it, so that this class can stay a pure implementation of the 31 productions.
     */
    private void scanLexical() {
        for (Token t : tokens) {
            if (t.type == TokenType.BAD) {
                issues.add(Issue.error(Codes.UNEXPECTED_CHAR,
                        "'" + t.image(text) + "' cannot start anything in VRML",
                        t.start, t.end));
            } else if (t.truncated) {
                issues.add(Issue.error(Codes.UNTERMINATED_STRING,
                        "this string is not closed on its line",
                        t.start, t.end));
            }
        }
    }

    private CstNode end() {
        CstNode node = stack.pop();
        if (stack.isEmpty()) {
            stack.push(node);
        } else {
            stack.peek().addChild(node);
        }
        return node;
    }

    private Token cur() {
        return sig.get(Math.min(i, sig.size() - 1));
    }

    private Token peek(int ahead) {
        int at = Math.min(i + ahead, sig.size() - 1);
        return sig.get(at);
    }

    private boolean atEnd() {
        return cur().type == TokenType.EOF;
    }

    private boolean check(TokenType type) {
        return cur().type == type;
    }

    /** Consume the current token as a leaf of the given kind. */
    private CstNode consume(CstKind kind) {
        Token t = cur();
        if (t.type == TokenType.LBRACE) {
            bracesConsumed++;
        }
        CstNode leaf = CstNode.leaf(kind, sigOf.get(Math.min(i, sigOf.size() - 1)), t);
        stack.peek().addChild(leaf);
        if (i < sig.size()) {
            i++;
        }
        return leaf;
    }

    private CstNode consumeCurrent() {
        return consume(kindFor(cur().type));
    }

    private static CstKind kindFor(TokenType type) {
        return switch (type) {
            case IDENT -> CstKind.IDENTIFIER;
            case NUMBER -> CstKind.NUMBER;
            case STRING -> CstKind.STRING;
            case BAD -> CstKind.ERROR;
            default -> type.isKeyword() || type == TokenType.DOT ? CstKind.KEYWORD : CstKind.PUNCT;
        };
    }

    /** Match an expected token, or record a hole at the insertion point and continue. */
    private boolean expect(TokenType type, String what, int code) {
        if (check(type)) {
            consumeCurrent();
            return true;
        }
        report(code, "expected " + what + " but found " + describeCurrent(), cur().start, cur().start);
        stack.peek().addChild(CstNode.missing(cur().start));
        return false;
    }

    private void report(int code, String message, int start, int end) {
        int at = Math.min(i, sig.size() - 1);
        if (at == lastErrorSig) {
            return;
        }
        lastErrorSig = at;
        issues.add(Issue.error(code, message, start, end));
    }

    private String describeCurrent() {
        Token t = cur();
        if (t.type == TokenType.EOF) {
            return "end of file";
        }
        String image = t.image(text);
        if (image.length() > 24) {
            image = image.substring(0, 21) + "...";
        }
        return "'" + image + "'";
    }

    //----------------------------------------------------------------- Rule 0-2: statements

    /** Rule 1 - {@code statement ::= nodeStatement | protoStatement | routeStatement} */
    private void statement() {
        begin(CstKind.STATEMENT);
        switch (cur().type) {
            case KW_ROUTE -> route();
            case KW_PROTO, KW_EXTERNPROTO -> protoStatement();
            default -> nodeStatement();
        }
        end();
    }

    /** Rule 2 - {@code nodeStatement ::= node | DEF nodeName node | USE nodeName} */
    private void nodeStatement() {
        begin(CstKind.NODE_STATEMENT);
        switch (cur().type) {
            case KW_DEF -> {
                begin(CstKind.DEF_CLAUSE);
                consumeCurrent();
                expectName(CstKind.NODE_NAME, "a name after DEF");
                end();
                node();
            }
            case KW_USE -> {
                begin(CstKind.USE_CLAUSE);
                consumeCurrent();
                expectName(CstKind.NODE_NAME, "a name after USE");
                end();
            }
            default -> node();
        }
        end();
    }

    /** Rule 3 - {@code rootNodeStatement ::= node | DEF nodeName node} (a USE is not allowed here) */
    private void rootNodeStatement() {
        if (check(TokenType.KW_USE)) {
            report(Codes.STATEMENT_UNFINISHED,
                    "a PROTO body must build something, so USE alone is not enough here",
                    cur().start, cur().end);
        }
        nodeStatement();
    }

    /** Rule 12 - {@code node ::= nodeTypeId "{" nodeBody "}" | "Script" "{" scriptBody "}"} */
    private void node() {
        begin(CstKind.NODE);
        boolean script = check(TokenType.KW_SCRIPT);
        if (script) {
            consumeCurrent();
        } else {
            expectName(CstKind.NODE_TYPE, "a node type name");
        }
        int lbrace = cur().start;
        if (!expect(TokenType.LBRACE, "'{' after the node name", Codes.MISSING_LBRACE)) {
            end();
            return;
        }
        if (script) {
            scriptBody();
        } else {
            nodeBody();
        }
        if (!check(TokenType.RBRACE)) {
            report(Codes.UNCLOSED_NODE, "this node is never closed", lbrace, lbrace + 1);
            stack.peek().addChild(CstNode.missing(lbrace + 1));
        } else {
            consumeCurrent();
        }
        end();
    }

    /** Rule 13 - {@code nodeBody ::= nodeBodyElement+} */
    private void nodeBody() {
        begin(CstKind.NODE_BODY);
        while (!check(TokenType.RBRACE) && !atEnd()) {
            int before = i;
            int bracesBefore = bracesConsumed;
            int issuesBefore = issues.size();
            nodeBodyElement();
            if (i == before) {
                recoverInNodeBody();
            } else if (bracesConsumed == bracesBefore && issues.size() > issuesBefore) {
                skipFallout(this::plausibleElementStart);
            }
        }
        end();
    }

    /** Rule 14 - {@code nodeBodyElement ::= fieldName value | fieldName IS fieldName | route | proto} */
    private void nodeBodyElement() {
        switch (cur().type) {
            case IDENT -> {
                begin(CstKind.FIELD_ASSIGNMENT);
                consume(CstKind.FIELD_NAME);
                if (check(TokenType.KW_IS)) {
                    isClause();
                } else {
                    fieldValue();
                }
                end();
            }
            case KW_ROUTE -> route();
            case KW_PROTO, KW_EXTERNPROTO -> protoStatement();
            case KW_FIELD, KW_EVENT_IN, KW_EVENT_OUT, KW_EXPOSED_FIELD -> {
                report(Codes.DECL_OUTSIDE_PROTO,
                        "an interface declaration belongs in a PROTO or Script, not in a node body",
                        cur().start, cur().end);
                skipBadDeclaration();
            }
            default -> {
                report(Codes.UNEXPECTED_TOKEN, "unexpected " + describeCurrent() + " in a node body",
                        cur().start, cur().end);
                errorRun(this::atElementBoundary);
            }
        }
    }

    /** Part of Rule 14 - {@code fieldNameId IS fieldNameId} */
    private void isClause() {
        begin(CstKind.IS_CLAUSE);
        consume(CstKind.ACCESS_TYPE);
        expectName(CstKind.FIELD_NAME, "a field name after IS");
        end();
    }

    //----------------------------------------------------------------- Rules 23-29: values

    /** Rule 23 - {@code fieldValue ::= singleFieldValue | multiFieldValue} */
    private void fieldValue() {
        begin(CstKind.VALUE);
        if (check(TokenType.LBRACKET)) {
            multiFieldValue();
        } else {
            singleFieldValue();
        }
        end();
    }

    /** Rule 24 - {@code singleFieldValue ::= nodeStatement | "NULL" | literalValue} */
    private void singleFieldValue() {
        begin(CstKind.SF_VALUE);
        switch (cur().type) {
            case KW_NULL -> consumeCurrent();
            case IDENT, KW_SCRIPT, KW_DEF, KW_USE -> nodeStatement();
            case KW_TRUE, KW_FALSE, NUMBER, STRING -> literalValue();
            default -> {
                report(Codes.MISSING_VALUE, "a field value is missing here", cur().start, cur().start);
                stack.peek().addChild(CstNode.missing(cur().start));
            }
        }
        end();
    }

    /**
     * Rule 25 - {@code multiFieldValue ::= "[" (nodeStatement+ | numberArray | stringArray) "]"}
     *
     * <p>The element kind is chosen from the first item, exactly as the generated parser's
     * alternatives do it, so a mixed {@code [1 "a"]} is one error rather than a cascade.
     */
    private void multiFieldValue() {
        begin(CstKind.MF_VALUE);
        if (!expect(TokenType.LBRACKET, "'[' to open a multiple value", Codes.MISSING_LBRACKET)) {
            singleFieldValue();
            end();
            return;
        }
        if (check(TokenType.RBRACKET) || atEnd()) {
            // "[]" - a legitimately empty multiple value.
        } else if (check(TokenType.NUMBER)) {
            begin(CstKind.NUMBER_ARRAY);
            while (check(TokenType.NUMBER)) {
                consumeCurrent();
            }
            if (!check(TokenType.RBRACKET)) {
                report(Codes.UNEXPECTED_TOKEN,
                        "a numeric multiple value can only be followed by more numbers or ']'",
                        cur().start, cur().end);
                errorRun(() -> check(TokenType.RBRACKET) || atEnd());
            }
            end();
        } else if (check(TokenType.STRING)) {
            begin(CstKind.STRING_ARRAY);
            while (check(TokenType.STRING)) {
                consumeCurrent();
            }
            if (!check(TokenType.RBRACKET)) {
                report(Codes.UNEXPECTED_TOKEN,
                        "a string multiple value can only be followed by more strings or ']'",
                        cur().start, cur().end);
                errorRun(() -> check(TokenType.RBRACKET) || atEnd());
            }
            end();
        } else if (canStartNodeStatement()) {
            begin(CstKind.NODE_LIST);
            while (!check(TokenType.RBRACKET) && !atEnd()) {
                int before = i;
                nodeStatement();
                if (i == before) {
                    recoverInList();
                }
            }
            end();
        } else {
            report(Codes.UNEXPECTED_TOKEN, "unexpected " + describeCurrent() + " inside '[ ]'",
                    cur().start, cur().end);
            errorRun(() -> check(TokenType.RBRACKET) || atEnd());
        }
        if (!expect(TokenType.RBRACKET, "']' to close a multiple value", Codes.MISSING_RBRACKET)) {
            // Nothing more to do; the enclosing field keeps parsing.
        }
        end();
    }

    /** Rules 26-29 - {@code literalValue ::= TRUE | FALSE | numberArray | STRING_LITERAL} */
    private void literalValue() {
        begin(CstKind.LITERAL_VALUE);
        switch (cur().type) {
            case KW_TRUE, KW_FALSE, STRING -> consumeCurrent();
            case NUMBER -> {
                // A single value may carry several numbers (SFVec3f, SFRotation); the upstream
                // grammar accepts any count here and lets the field parser sort out the rest.
                begin(CstKind.NUMBER_ARRAY);
                while (check(TokenType.NUMBER)) {
                    consumeCurrent();
                }
                end();
            }
            default -> {
                report(Codes.MISSING_VALUE, "a field value is missing here", cur().start, cur().start);
                stack.peek().addChild(CstNode.missing(cur().start));
            }
        }
        end();
    }

    //----------------------------------------------------------------- Rules 4-11: PROTO

    /** Rule 2 (protoStatement) - {@code proto | externproto} */
    private void protoStatement() {
        if (check(TokenType.KW_PROTO)) {
            proto();
        } else {
            externProto();
        }
    }

    /** Rule 4 - {@code proto ::= PROTO nodeTypeId "[" interfaceDecl* "]" "{" protoBody "}"} */
    private void proto() {
        begin(CstKind.PROTO_DECL);
        consumeCurrent();
        expectName(CstKind.PROTO_NAME, "a PROTO name");
        expect(TokenType.LBRACKET, "'[' to open the PROTO interface", Codes.MISSING_LBRACKET);
        while (!check(TokenType.RBRACKET) && !atEnd()) {
            int before = i;
            interfaceDecl();
            if (i == before) {
                report(Codes.UNEXPECTED_TOKEN, "unexpected " + describeCurrent()
                        + " in a PROTO interface; expected field, eventIn, eventOut or exposedField",
                        cur().start, cur().end);
                errorRun(() -> check(TokenType.RBRACKET) || isAccessType() || atEnd());
            }
        }
        expect(TokenType.RBRACKET, "']' to close the PROTO interface", Codes.MISSING_RBRACKET);
        int bodyStart = cur().start;
        if (!expect(TokenType.LBRACE, "'{' to open the PROTO body", Codes.MISSING_LBRACE)) {
            end();
            return;
        }
        protoBody(bodyStart);
        if (!check(TokenType.RBRACE)) {
            report(Codes.UNCLOSED_NODE, "this PROTO body is never closed", bodyStart, bodyStart + 1);
            stack.peek().addChild(CstNode.missing(bodyStart + 1));
        } else {
            consumeCurrent();
        }
        end();
    }

    /** Rule 5 - {@code protoBody ::= protoStatement* rootNodeStatement statement*} */
    private void protoBody(int bodyStart) {
        begin(CstKind.PROTO_BODY);
        while ((check(TokenType.KW_PROTO) || check(TokenType.KW_EXTERNPROTO)) && !atEnd()) {
            protoStatement();
        }
        if (!check(TokenType.RBRACE) && !atEnd()) {
            rootNodeStatement();
        } else {
            report(Codes.PROTO_BODY_EMPTY,
                    "a PROTO body must contain at least one node",
                    bodyStart, bodyStart + 1);
            stack.peek().addChild(CstNode.missing(bodyStart + 1));
        }
        while (!check(TokenType.RBRACE) && !atEnd()) {
            int before = i;
            statement();
            if (i == before) {
                recoverInNodeBody();
            }
        }
        end();
    }

    /** Rules 6-7 - interface declaration, with exposedField allowed only in PROTO */
    private void interfaceDecl() {
        if (!isAccessType()) {
            report(Codes.UNEXPECTED_TOKEN, "expected field, eventIn, eventOut or exposedField but found "
                    + describeCurrent(), cur().start, cur().end);
            errorRun(() -> check(TokenType.RBRACKET) || isAccessType() || atEnd());
            return;
        }
        begin(CstKind.INTERFACE_DECL);
        TokenType access = cur().type;
        consume(CstKind.ACCESS_TYPE);
        expectName(CstKind.FIELD_TYPE, "a field type such as SFVec3f");
        expectName(CstKind.FIELD_NAME, "a field name");
        if (access == TokenType.KW_FIELD || access == TokenType.KW_EXPOSED_FIELD) {
            fieldValue();
        }
        end();
    }

    /** Rule 9 - {@code externproto ::= EXTERNPROTO nodeTypeId "[" externDecl* "]" URLList} */
    private void externProto() {
        begin(CstKind.EXTERN_PROTO_DECL);
        consumeCurrent();
        expectName(CstKind.PROTO_NAME, "a PROTO name");
        if (check(TokenType.LBRACKET)) {
            consumeCurrent();
            while (!check(TokenType.RBRACKET) && !atEnd()) {
                int before = i;
                externInterfaceDecl();
                if (i == before) {
                    errorRun(() -> check(TokenType.RBRACKET) || isAccessType() || atEnd());
                }
            }
            expect(TokenType.RBRACKET, "']' to close the EXTERNPROTO interface", Codes.MISSING_RBRACKET);
        } else {
            // VRML97 requires the bracket list; X3D allows it to be omitted. Report and move on.
            report(Codes.MISSING_LBRACKET, "'[' to open the EXTERNPROTO interface is missing",
                    cur().start, cur().start);
            stack.peek().addChild(CstNode.missing(cur().start));
        }
        uriList();
        end();
    }

    /**
     * Rule 10 - {@code externInterfaceDeclaration ::= accessType fieldType fieldNameId}
     *
     * <p>The {@code BNF} comment above the upstream production claims two parts; the code
     * ({@code AccessType() FieldId() FieldNameId()}) takes three, and the corpus is written
     * with three. The code is what Xj3D actually accepts, so that is what is reproduced.
     */
    private void externInterfaceDecl() {
        if (!isAccessType()) {
            report(Codes.UNEXPECTED_TOKEN, "expected an access type but found " + describeCurrent(),
                    cur().start, cur().end);
            errorRun(() -> check(TokenType.RBRACKET) || isAccessType() || atEnd());
            return;
        }
        begin(CstKind.INTERFACE_DECL);
        consume(CstKind.ACCESS_TYPE);
        expectName(CstKind.FIELD_TYPE, "a field type such as SFVec3f");
        expectName(CstKind.FIELD_NAME, "a field name");
        end();
    }

    /** Rule 11 - {@code URLList ::= "[" STRING* "]" | STRING} */
    private void uriList() {
        begin(CstKind.URI_LIST);
        if (check(TokenType.LBRACKET)) {
            consumeCurrent();
            while (check(TokenType.STRING)) {
                consumeCurrent();
            }
            expect(TokenType.RBRACKET, "']' to close the URL list", Codes.MISSING_RBRACKET);
        } else if (check(TokenType.STRING)) {
            consumeCurrent();
        } else {
            report(Codes.MISSING_VALUE, "an EXTERNPROTO needs at least one URL string",
                    cur().start, cur().start);
            stack.peek().addChild(CstNode.missing(cur().start));
        }
        end();
    }

    //----------------------------------------------------------------- Rule 2: ROUTE

    /** {@code routeStatement ::= ROUTE nodeName "." eventOutId TO nodeName "." eventInId} */
    private void route() {
        begin(CstKind.ROUTE_DECL);
        consumeCurrent();
        expectName(CstKind.NODE_NAME, "a node name after ROUTE");
        expect(TokenType.DOT, "'.' between a node name and its field", Codes.MISSING_DOT);
        expectName(CstKind.FIELD_NAME, "an output field name");
        expectKeyword(TokenType.KW_TO, "TO", Codes.MISSING_KEYWORD);
        expectName(CstKind.NODE_NAME, "a node name after TO");
        expect(TokenType.DOT, "'.' between a node name and its field", Codes.MISSING_DOT);
        expectName(CstKind.FIELD_NAME, "an input field name");
        end();
    }

    //----------------------------------------------------------------- Rules 15-16: Script

    /** Rule 15 - {@code scriptBody ::= scriptBodyElement+} */
    private void scriptBody() {
        begin(CstKind.SCRIPT_BODY);
        while (!check(TokenType.RBRACE) && !atEnd()) {
            int before = i;
            int bracesBefore = bracesConsumed;
            int issuesBefore = issues.size();
            scriptElement();
            if (i == before) {
                recoverInNodeBody();
            } else if (bracesConsumed == bracesBefore && issues.size() > issuesBefore) {
                skipFallout(this::plausibleElementStart);
            }
        }
        end();
    }

    /**
     * Rule 16 - {@code scriptBodyElement ::= nodeBodyElement | restrictedInterfaceDeclaration
     * | eventIn/eventOut ... [IS ...] | field ... (IS ... | value)}
     *
     * <p>The upstream {@code LOOKAHEAD(3)} disambiguation becomes a plain switch here, which
     * is also why an {@code exposedField} inside Script is reported instead of misparsed.
     */
    private void scriptElement() {
        switch (cur().type) {
            case KW_EVENT_IN, KW_EVENT_OUT -> {
                begin(CstKind.SCRIPT_ELEMENT);
                consume(CstKind.ACCESS_TYPE);
                expectName(CstKind.FIELD_TYPE, "a field type such as SFTime");
                expectName(CstKind.FIELD_NAME, "a field name");
                if (check(TokenType.KW_IS)) {
                    isClause();
                }
                end();
            }
            case KW_FIELD -> {
                begin(CstKind.SCRIPT_ELEMENT);
                consume(CstKind.ACCESS_TYPE);
                expectName(CstKind.FIELD_TYPE, "a field type such as SFTime");
                expectName(CstKind.FIELD_NAME, "a field name");
                if (check(TokenType.KW_IS)) {
                    isClause();
                } else {
                    fieldValue();
                }
                end();
            }
            case KW_EXPOSED_FIELD -> {
                report(Codes.DECL_OUTSIDE_PROTO,
                        "Script bodies have no exposedField; use field, eventIn or eventOut",
                        cur().start, cur().end);
                skipBadDeclaration();
            }
            default -> nodeBodyElement();
        }
    }

    //----------------------------------------------------------------- recovery

    private boolean isAccessType() {
        return switch (cur().type) {
            case KW_FIELD, KW_EVENT_IN, KW_EVENT_OUT, KW_EXPOSED_FIELD -> true;
            default -> false;
        };
    }

    private boolean canStartNodeStatement() {
        return switch (cur().type) {
            case IDENT, KW_SCRIPT, KW_DEF, KW_USE -> true;
            default -> false;
        };
    }

    /** Where a node body element may legitimately begin again. */
    private boolean atElementBoundary() {
        return switch (cur().type) {
            case RBRACE, IDENT, KW_ROUTE, KW_PROTO, KW_EXTERNPROTO, EOF -> true;
            default -> false;
        };
    }

    /** Where a top level statement may legitimately begin again. */
    private boolean atStatementBoundary() {
        return switch (cur().type) {
            case IDENT, KW_DEF, KW_USE, KW_ROUTE, KW_PROTO, KW_EXTERNPROTO, KW_SCRIPT, EOF -> true;
            default -> false;
        };
    }

    private void recoverInNodeBody() {
        if (atElementBoundary()) {
            // A boundary token that made no progress: consume it so the loop cannot spin.
            report(Codes.UNEXPECTED_TOKEN, "unexpected " + describeCurrent() + " in a node body",
                    cur().start, cur().end);
            begin(CstKind.ERROR);
            consumeCurrent();
            end();
            return;
        }
        report(Codes.UNEXPECTED_TOKEN, "unexpected " + describeCurrent() + " in a node body",
                cur().start, cur().end);
        errorRun(this::atElementBoundary);
    }

    private void recoverInList() {
        if (check(TokenType.RBRACKET) || atEnd()) {
            return;
        }
        report(Codes.UNEXPECTED_TOKEN, "unexpected " + describeCurrent() + " inside '[ ]'",
                cur().start, cur().end);
        errorRun(() -> check(TokenType.RBRACKET) || canStartNodeStatement() || atEnd());
    }

    private void recoverAtSceneLevel() {
        if (atStatementBoundary()) {
            // Plausible start that still failed (e.g. DEF at EOF): drop it and keep going.
            begin(CstKind.ERROR);
            consumeCurrent();
            end();
            return;
        }
        report(Codes.UNEXPECTED_TOKEN,
                "expected DEF, USE, ROUTE, PROTO, EXTERNPROTO or a node name but found "
                        + describeCurrent(), cur().start, cur().end);
        errorRun(this::atStatementBoundary);
    }

    /**
     * Skip a declaration that is not allowed where it was found, taking its own words along.
     *
     * <p>Reporting the keyword and stopping is worse than the mistake itself: the body loop then
     * reads {@code SFInt32 myIntField} as a perfectly legal {@code name value} pair and complains
     * a second time about something the user never wrote. Swallowing {@code accessType type name
     * [IS name]} plus whatever value may trail it keeps one diagnostic per broken declaration.
     * The closing {@code &#125;} is never taken, because that would turn one error into an
     * unclosed node.
     */
    private void skipBadDeclaration() {
        begin(CstKind.ERROR);
        consumeCurrent();
        if (check(TokenType.IDENT)) {
            consumeCurrent();
        }
        if (check(TokenType.IDENT)) {
            consumeCurrent();
        }
        if (check(TokenType.KW_IS)) {
            consumeCurrent();
            if (check(TokenType.IDENT)) {
                consumeCurrent();
            }
        }
        end();
        if (!check(TokenType.RBRACE) && !atEnd()) {
            errorRun(this::atElementBoundary);
        }
    }

    /**
     * Collapse the debris of one mistake into one diagnostic.
     *
     * <p>A statement that reported something and never opened a {@code &#123;} was not parsed -
     * it consumed a token, complained, and stopped. Calling the next token another statement start
     * would then complain again about the same typo, which is how {@code IMPORT INLINE.foo AS bar}
     * used to produce five errors on one line. Sweeping to the next start the grammar can actually
     * believe keeps the count at one per broken statement without guessing where the line ends,
     * because VRML97 has no statement terminator to guess with.
     *
     * <p>The sweep stops at a closing brace or bracket: swallowing the delimiter that ends the
     * enclosing node would turn one error into an unclosed node plus the rest of the file.
     */
    private void skipFallout(java.util.function.BooleanSupplier plausibleStart) {
        if (atEnd() || plausibleStart.getAsBoolean() || closesSomething()) {
            return;
        }
        begin(CstKind.ERROR);
        while (!atEnd() && !closesSomething() && !plausibleStart.getAsBoolean()) {
            consumeCurrent();
        }
        end();
    }

    private boolean closesSomething() {
        return switch (cur().type) {
            case RBRACE, RBRACKET -> true;
            default -> false;
        };
    }

    /**
     * Where a node body element can legitimately begin again: an interface keyword, a nested
     * route/proto statement, or a field name followed by something that can start a value.
     *
     * <p>{@code exposedField} is deliberately absent - it is an error in both bodies, so there is
     * nothing to resume on.
     */
    private boolean plausibleElementStart() {
        return switch (cur().type) {
            case KW_ROUTE, KW_PROTO, KW_EXTERNPROTO, KW_FIELD, KW_EVENT_IN, KW_EVENT_OUT -> true;
            case IDENT -> switch (peek(1).type) {
                case LBRACE, NUMBER, STRING, LBRACKET, KW_TRUE, KW_FALSE, KW_NULL, KW_IS, KW_DEF,
                        KW_USE, KW_SCRIPT -> true;
                // "appearance FontStyle {}": a node value, so the name before its brace.
                case IDENT -> peek(2).type == TokenType.LBRACE;
                default -> false;
            };
            default -> false;
        };
    }

    /**
     * A keyword that begins a statement by rule, or a node type name with its {@code &#123;} in
     * front of it - Rule 12 offers no other shape, so an identifier followed by anything else is
     * fallout rather than a fresh chance to fail.
     */
    private boolean plausibleStatementStart() {
        return switch (cur().type) {
            case KW_DEF, KW_USE, KW_ROUTE, KW_PROTO, KW_EXTERNPROTO -> true;
            case IDENT -> peek(1).type == TokenType.LBRACE;
            default -> false;
        };
    }

    /**
     * Sweep unplaceable tokens into an {@link CstKind#ERROR} node up to a boundary,
     * keeping nested braces together so a stray block cannot eat the rest of the file.
     * Always consumes at least one token.
     */
    private void errorRun(java.util.function.BooleanSupplier atBoundary) {
        begin(CstKind.ERROR);
        int before = i;
        int depth = 0;
        while (!atEnd() && (depth > 0 || !atBoundary.getAsBoolean())) {
            TokenType t = cur().type;
            if (t == TokenType.LBRACE) {
                depth++;
            } else if (t == TokenType.RBRACE) {
                if (depth == 0) {
                    break;
                }
                depth--;
            }
            consumeCurrent();
        }
        if (i == before && !atEnd()) {
            consumeCurrent();
        }
        end();
    }

    //----------------------------------------------------------------- names and misc

    /** Rules 17-19, 22 - all of them are just {@code Id}, differing only in what they mean. */
    private void expectName(CstKind kind, String what) {
        if (check(TokenType.IDENT)) {
            consume(kind);
            return;
        }
        report(Codes.MISSING_NAME, "expected " + what + " but found " + describeCurrent(),
                cur().start, cur().start);
        stack.peek().addChild(CstNode.missing(cur().start));
    }

    private void expectKeyword(TokenType type, String word, int code) {
        if (check(type)) {
            consumeCurrent();
            return;
        }
        report(code, "expected " + word + " but found " + describeCurrent(), cur().start, cur().start);
        stack.peek().addChild(CstNode.missing(cur().start));
    }
}
