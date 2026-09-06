package org.vrml.lsp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.semantic.Members;
import org.vrml.lsp.semantic.Members.Member;
import org.vrml.lsp.semantic.SymbolTable;
import org.vrml.lsp.spec.Spec;

/**
 * What a pointer over one token is allowed to say.
 *
 * <p>Every sentence here is read out of the same three sources the diagnostics and the completion
 * popup use - the table, the symbol table and the tree between them - which is the only way the
 * tooltip, the squiggle and the popup can be kept telling one story about one file. Where a fact is
 * not in any of them it is not said: hovering a name nothing defines gets "nothing defines this",
 * not a guess at what the author meant.
 *
 * <p>The links are the spec table's own, which point at the X3D specification: that is where the
 * prose and the default values were taken from when the table was generated (see
 * {@code SpecGenMain}), and it is the closest thing to a VRML97 reference the project has.
 */
public final class Hovers {

    /**
     * @param markdown the text to show
     * @param start first offset of the token the pointer was on
     * @param end last offset of that token, which is what a client underlines while the tooltip is up
     */
    public record Hovered(String markdown, int start, int end) {
    }

    /** How many names fit in a "what may be written here" line before it stops being a quick look. */
    private static final int MAX_NAMES = 8;

    private final ParseResult parse;
    private final SymbolTable symbols;
    private final Spec spec;
    private final AtOffset at;
    private final Context.Scope scope;

    private Hovers(ParseResult parse, SymbolTable symbols, Spec spec, AtOffset at) {
        this.parse = parse;
        this.symbols = symbols;
        this.spec = spec;
        this.at = at;
        this.scope = at.scope(symbols);
    }

    /**
     * What to say about the token at {@code offset}, or null when the pointer is on something that
     * has nothing to explain - whitespace, a brace, a name no table and no file knows.
     */
    public static Hovered of(ParseResult parse, SymbolTable symbols, Spec spec, int offset) {
        AtOffset at = AtOffset.of(parse, offset);
        CstNode leaf = at.leaf();
        if (leaf == null || leaf.kind() == CstKind.MISSING) {
            return null;
        }
        String said = new Hovers(parse, symbols, spec, at).read(leaf);
        return said == null || said.isEmpty() ? null
                : new Hovered(said, leaf.start(), leaf.end());
    }

    private String read(CstNode leaf) {
        String text = parse.text(leaf);
        return switch (leaf.kind()) {
            case NODE_TYPE -> writtenAsNode(text);
            case KEYWORD -> text.equals("Script") ? spec.node("Script").map(Hovers::node).orElse("")
                    : "";
            case NODE_NAME -> name(leaf, text);
            case FIELD_NAME -> member(leaf, text);
            case PROTO_NAME -> declared(text);
            case FIELD_TYPE -> type(text);
            case ACCESS_TYPE -> Completions.accessMeaning(text);
            case IDENTIFIER, NUMBER, STRING -> value(text);
            default -> "";
        };
    }

    // ---- nodes -------------------------------------------------------------------------------

    /** A node type: the table's row if it has one, this file's {@code PROTO} otherwise. */
    private String writtenAsNode(String text) {
        Optional<Spec.Node> builtIn = spec.node(text);
        if (builtIn.isPresent()) {
            return node(builtIn.get());
        }
        SymbolTable.Prototype prototype = symbols.prototype(scope.scope(), text);
        return prototype == null ? "" : prototype(prototype, "used here");
    }

    /** What the table says about one node, with a quick look at what its body may hold. */
    static String node(Spec.Node node) {
        StringBuilder out = new StringBuilder(title("`" + node.name() + "`",
                node.component() + ", level " + node.level()));
        para(out, node.summary());
        List<String> writable = new ArrayList<>();
        for (Spec.Field field : node.fields()) {
            if (field.access().equals("field") || field.access().equals("exposedField")) {
                writable.add(field.name());
            }
        }
        names(out, "may be given a value for", writable);
        link(out, "specification", node.specUrl());
        return out.toString();
    }

    /** A {@code PROTO} or {@code EXTERNPROTO}, by name wherever the pointer found it. */
    private String declared(String text) {
        SymbolTable.Prototype prototype = visibleDeclaration(text);
        return prototype == null ? "" : prototype(prototype, "declared here");
    }

    /** The declaration this name stands for, matched on the node so a redeclaration cannot confuse it. */
    private SymbolTable.Prototype visibleDeclaration(String text) {
        for (SymbolTable.Prototype candidate : symbols.prototypes(scope.scope())) {
            if (candidate.name().equals(text) && candidate.decl() == at.inside(CstKind.PROTO_DECL,
                    CstKind.EXTERN_PROTO_DECL)) {
                return candidate;
            }
        }
        return symbols.prototype(scope.scope(), text);
    }

    /**
     * @param role how the pointer came to be looking at this prototype, which is the one thing a
     *        hover over a name adds to a hover over its declaration
     */
    private String prototype(SymbolTable.Prototype prototype, String role) {
        StringBuilder out = new StringBuilder(title("`" + prototype.name() + "`",
                (prototype.external() ? "EXTERNPROTO " : "PROTO ") + role));
        if (prototype.external()) {
            para(out, "Its implementation is one of the files listed after the interface, which the"
                    + " browser reads before the scene runs.");
        }
        if (prototype.declarations().isEmpty()) {
            para(out, "It declares no interface, so nothing may be written inside an instance of it.");
        }
        for (SymbolTable.Interface entry : prototype.declarations()) {
            bullet(out, entry.access() + " " + entry.type() + " `" + entry.name() + "`", "");
        }
        return out.toString();
    }

    // ---- names -------------------------------------------------------------------------------

    /** {@code DEF} and {@code USE} names, and the two ends of a {@code ROUTE}. */
    private String name(CstNode leaf, String text) {
        CstNode clause = at.inside(CstKind.DEF_CLAUSE, CstKind.USE_CLAUSE, CstKind.ROUTE_DECL);
        if (clause == null) {
            return "";
        }
        return switch (clause.kind()) {
            case DEF_CLAUSE -> defined(own(text), text, "DEF'd here");
            case USE_CLAUSE -> used(text);
            default -> routed(clause, leaf, text);
        };
    }

    /** The definition the pointer is on, read out of the table by the name node's identity. */
    private SymbolTable.Definition own(String text) {
        for (SymbolTable.Definition definition : symbols.definitions(scope.scope())) {
            if (definition.nameNode() == at.leaf()) {
                return definition;
            }
        }
        return null;
    }

    private String used(String text) {
        SymbolTable.Use use = null;
        for (SymbolTable.Use candidate : symbols.uses()) {
            if (candidate.nameNode() == at.leaf()) {
                use = candidate;
            }
        }
        if (use == null) {
            return "";
        }
        SymbolTable.Definition definition =
                symbols.definition(use.scope(), text, use.order());
        return definition == null ? title("`" + text + "`", "USE") + "\n\nNothing `DEF`s this name"
                + useScope(use) + ", so the browser has no node to put here."
                : defined(definition, text, "used here");
    }

    /** Where a {@code USE} stands, since a name inside a {@code PROTO} body is a different name. */
    private String useScope(SymbolTable.Use use) {
        return use.scope() == SymbolTable.FILE_SCOPE ? ""
                : " in the body of the `PROTO` it stands in";
    }

    /**
     * The node one name holds, with how many times the file reads it.
     *
     * <p>The count is the part an author cannot see from the line under the cursor: how much would
     * break by renaming it, and whether anything reads it at all.
     */
    private String defined(SymbolTable.Definition definition, String text, String role) {
        if (definition == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(title("`" + text + "`",
                "`" + definition.nodeType() + "`, " + role));
        int uses = 0;
        for (SymbolTable.Use use : symbols.uses()) {
            if (use.scope() == definition.scope() && use.name().equals(text)) {
                uses++;
            }
        }
        int ends = 0;
        for (SymbolTable.Route route : symbols.routes()) {
            if (route.scope() == definition.scope() && route.from().nodeName().equals(text)) {
                ends++;
            }
            if (route.scope() == definition.scope() && route.to().nodeName().equals(text)) {
                ends++;
            }
        }
        // Zeroes are left out: "0 USEs and 1 end of a ROUTE" makes the reader subtract, and a name
        // nothing mentions is the one case worth spelling out.
        List<String> readers = new ArrayList<>();
        if (uses > 0) {
            readers.add(uses + (uses == 1 ? " `USE`" : " `USE`s"));
        }
        if (ends > 0) {
            readers.add(ends + (ends == 1 ? " end of a `ROUTE`" : " ends of `ROUTE`s"));
        }
        bullet(out, "used by", readers.isEmpty() ? "nothing in this file"
                : String.join(" and ", readers));
        return out.toString();
    }

    /** One end of a {@code ROUTE}: which node it wires, and whether it is the source or the sink. */
    private String routed(CstNode clause, CstNode leaf, String text) {
        SymbolTable.Route route = null;
        for (SymbolTable.Route candidate : symbols.routes()) {
            if (candidate.decl() == clause) {
                route = candidate;
            }
        }
        if (route == null) {
            return "";
        }
        // Either half of an end is that end: `pi` and `value_changed` are the same source, so
        // matching only the node name would call the field the sink of the other node.
        boolean source = route.from().nameNode() == leaf || route.from().fieldNode() == leaf;
        String nodeName = source ? route.from().nodeName() : route.to().nodeName();
        SymbolTable.Definition definition =
                symbols.definition(route.scope(), nodeName, Integer.MAX_VALUE);
        StringBuilder out = new StringBuilder(title("`" + text + "`",
                (source ? "ROUTE source of `" : "ROUTE sink of `") + nodeName + "`"));
        if (definition == null) {
            para(out, "No node named `" + nodeName + "` is `DEF`'d in this scope, so the route goes"
                    + " nowhere.");
            return out.toString();
        }
        bullet(out, "the node is", "`" + definition.nodeType() + "`");
        routedMember(definition, text).ifPresent(found -> {
            bullet(out, "carries", found.type() + " (" + found.access() + ")");
            para(out, found.doc());
        });
        return out.toString();
    }

    /**
     * The member one end of a {@code ROUTE} drives.
     *
     * <p>{@code set_translation} is no name the table carries: an exposedField is routed through the
     * events Xj3D generates for it, and only the field it was made from has a type and a description
     * to show. So the name is undone when it does not match directly, and the answer is the field
     * underneath the event.
     */
    private Optional<Member> routedMember(SymbolTable.Definition definition, String text) {
        List<Member> members = Members.of(parse, spec, symbols, definition.nodeType(),
                definition.node(), definition.scope());
        Optional<Member> declared = Members.find(members, text);
        return declared.isPresent() ? declared
                : Members.madeFrom(text).flatMap(base -> Members.find(members, base));
    }

    // ---- members -----------------------------------------------------------------------------

    /**
     * A field, event or interface name.
     *
     * <p>The same word means four different things depending on where it was written: an assignment
     * to a node's field, one end of a route, a name an interface declares, or the name an {@code IS}
     * binds. Each of those has its own answer, and only the first two come out of the table.
     */
    private String member(CstNode leaf, String text) {
        CstNode element = at.inside(CstKind.FIELD_ASSIGNMENT, CstKind.SCRIPT_ELEMENT,
                CstKind.INTERFACE_DECL, CstKind.IS_CLAUSE, CstKind.ROUTE_DECL);
        if (element == null) {
            return "";
        }
        if (element.kind() == CstKind.ROUTE_DECL) {
            // A route has two field names and neither is first in the way the others are: the slot
            // itself says which end it is, so this cannot use the check below.
            return routed(element, leaf, text);
        }
        if (leaf != element.firstChildOf(CstKind.FIELD_NAME)) {
            return "";
        }
        return switch (element.kind()) {
            case FIELD_ASSIGNMENT -> written(text, "");
            case SCRIPT_ELEMENT -> written(text, "declared here");
            case INTERFACE_DECL -> declaredInterface(text);
            default -> bound(text);
        };
    }

    /** The name on the left of an assignment, or of a {@code Script} body's own line. */
    private String written(String text, String role) {
        CstNode node = at.inside(CstKind.NODE);
        if (node == null) {
            return "";
        }
        String type = SymbolTable.nodeType(parse, node);
        Optional<Member> found = memberOf(node, type, text);
        if (found.isPresent()) {
            return field(found.get(), type, text, role);
        }
        // The name is already marked as no field of this node; saying so is the tooltip's job.
        return title("`" + text + "`", "of `" + type + "`") + "\n\n`" + type + "` has no field named"
                + " this, so nothing is written by this line.";
    }

    private Optional<Member> memberOf(CstNode node, String type, String text) {
        return Members.find(Members.of(parse, spec, symbols, type, node, scope.scope()), text);
    }

    /**
     * The members a {@code PROTO} or {@code EXTERNPROTO} interface declares.
     *
     * <p>Asked of {@link Members} rather than read off {@link SymbolTable.Interface} by hand, so that
     * an interface line's own default value is described the one way it is described everywhere.
     */
    private List<Member> declaredBy(SymbolTable.Prototype prototype) {
        return Members.of(parse, spec, symbols, prototype.name(), null, prototype.scope());
    }

    /** An interface line's own name, in the {@code PROTO} that declares it. */
    private String declaredInterface(String text) {
        CstNode decl = at.inside(CstKind.PROTO_DECL, CstKind.EXTERN_PROTO_DECL);
        if (decl == null) {
            return "";
        }
        SymbolTable.Prototype prototype = visibleDeclaration(parse.text(decl.firstChildOf(
                CstKind.PROTO_NAME)));
        if (prototype == null) {
            return "";
        }
        return declaredBy(prototype).stream().filter(member -> member.name().equals(text)).findFirst()
                .map(found -> field(found, prototype.name(), text, "declared here")).orElse("");
    }

    /** The name after {@code IS}, which is a name of the enclosing {@code PROTO}'s interface. */
    private String bound(String text) {
        SymbolTable.Prototype defining = scope.defining();
        if (defining == null) {
            return "";
        }
        Optional<Member> entry = declaredBy(defining).stream()
                .filter(member -> member.name().equals(text)).findFirst();
        if (entry.isEmpty()) {
            return title("`" + text + "`", "bound by `IS`") + "\n\n`PROTO " + defining.name()
                    + "` declares no " + text + ", so this binding has nothing to reach.";
        }
        String body = bodySide();
        return field(entry.get(), defining.name(), text,
                body.isEmpty() ? "bound by `IS`" : "bound by `IS` to `" + body + "` in the body");
    }

    /** The name on the body side of the {@code IS} the pointer is on. */
    private String bodySide() {
        CstNode element = at.inside(CstKind.FIELD_ASSIGNMENT, CstKind.SCRIPT_ELEMENT);
        CstNode name = element == null ? null : element.firstChildOf(CstKind.FIELD_NAME);
        return name == null || name.kind() == CstKind.MISSING ? "" : parse.text(name);
    }

    /**
     * One field of one node, as the table or the file describes it.
     *
     * @param type the node whose field this is, which is what makes a two-word name like
     *        {@code translation} mean something
     * @param written the spelling used in the file, which for two fields is an alias rather than the
     *        declared name
     * @param role what the pointer's position adds - a declaration, a binding - or "" for a plain
     *        assignment
     */
    private String field(Member member, String type, String written, String role) {
        StringBuilder out = new StringBuilder(title("`" + member.summary() + "`",
                "`" + type + "`" + (role.isEmpty() ? "" : ", " + role)));
        para(out, member.doc());
        if (!written.equals(member.name())) {
            bullet(out, "written as", "`" + written + "`, which is the name this field also answers"
                    + " to");
        }
        if (!member.defaultValue().isEmpty()) {
            bullet(out, "default", "`" + member.defaultValue() + "`");
        } else if (member.type().startsWith("MF")) {
            // The table writes no `default` for a list because "no values" is what it holds; that is
            // a fact worth saying, while saying "nothing" for an unknown default would not be.
            bullet(out, "default", "an empty list");
        }
        if (member.access().equals("exposedField")) {
            bullet(out, "routed as", "`" + Members.routedAs(member, false) + "` in, `"
                    + Members.routedAs(member, true) + "` out");
        }
        names(out, "accepts", member.childNodes());
        return out.toString();
    }

    /** The value on the right of a name: what field it belongs to, and what that field takes. */
    private String value(String text) {
        CstNode element = at.inside(CstKind.FIELD_ASSIGNMENT, CstKind.SCRIPT_ELEMENT,
                CstKind.INTERFACE_DECL);
        if (element == null || at.inside(CstKind.VALUE, CstKind.SF_VALUE, CstKind.MF_VALUE,
                CstKind.NODE_LIST, CstKind.LITERAL_VALUE, CstKind.NUMBER_ARRAY,
                CstKind.STRING_ARRAY) == null) {
            return "";
        }
        CstNode name = element.firstChildOf(CstKind.FIELD_NAME);
        if (name == null || name.kind() == CstKind.MISSING) {
            return "";
        }
        String written = parse.text(name);
        CstNode node = at.inside(CstKind.NODE);
        if (node != null) {
            String type = SymbolTable.nodeType(parse, node);
            Optional<Member> found = memberOf(node, type, written);
            if (found.isPresent()) {
                return field(found.get(), type, written, "the value this field is given");
            }
        }
        CstNode decl = at.inside(CstKind.PROTO_DECL, CstKind.EXTERN_PROTO_DECL);
        SymbolTable.Prototype defining = decl == null ? scope.defining()
                : visibleDeclaration(parse.text(decl.firstChildOf(CstKind.PROTO_NAME)));
        if (defining == null) {
            return "";
        }
        return declaredBy(defining).stream().filter(member -> member.name().equals(written))
                .findFirst()
                .map(found -> field(found, defining.name(), written,
                        "the default this interface line declares")).orElse("");
    }

    // ---- types -------------------------------------------------------------------------------

    /** One of the 21 field types, said the way the completion list says it. */
    private String type(String text) {
        Optional<Spec.ValueType> known = spec.type(text);
        if (known.isEmpty()) {
            return "";
        }
        Spec.ValueType valueType = known.get();
        boolean multi = text.startsWith("MF");
        StringBuilder out = new StringBuilder(title("`" + text + "`", Completions.shape(text)));
        para(out, (valueType.width() > 1 ? "One value is " + valueType.width() + " numbers. " : "")
                + (multi ? "A field of this type holds any number of them, between `[ ]`."
                        : "A field of this type holds exactly one."));
        bullet(out, multi ? "its single value" : "its multiple", "`"
                + (multi ? "SF" : "MF") + text.substring(2) + "`");
        return out.toString();
    }

    // ---- wording -----------------------------------------------------------------------------

    /** The first line: the thing, and the one phrase that places it. */
    private static String title(String what, String where) {
        return "**" + what + "**" + (where.isEmpty() ? "" : " · " + where);
    }

    private static void para(StringBuilder out, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        out.append("\n\n").append(text);
    }

    private static void bullet(StringBuilder out, String key, String value) {
        out.append("\n\n- ").append(key).append(value.isEmpty() ? "" : ": " + value);
    }

    private static void link(StringBuilder out, String key, String url) {
        if (url != null && !url.isEmpty()) {
            bullet(out, key, "[" + url + "](" + url + ")");
        }
    }

    /** A list of names, kept short enough to be a look and not a read. */
    private static void names(StringBuilder out, String key, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        StringBuilder listed = new StringBuilder();
        for (int i = 0; i < Math.min(names.size(), MAX_NAMES); i++) {
            listed.append(i == 0 ? "" : " ").append("`").append(names.get(i)).append("`");
        }
        if (names.size() > MAX_NAMES) {
            listed.append(" … (").append(names.size() - MAX_NAMES).append(" more)");
        }
        bullet(out, key, listed.toString());
    }
}
