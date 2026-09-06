package org.vrml.lsp.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.semantic.Members;
import org.vrml.lsp.semantic.SymbolTable;
import org.vrml.lsp.spec.Spec;

/**
 * Where the name under the pointer was written, and everywhere else the file reads it.
 *
 * <p>Every answer is a line of this document. A built-in field or node type is declared in the
 * language and in no file, so {@code definition} has nothing to return for it and says so by
 * returning nothing rather than by inventing a jump; {@code references} still has a real answer for
 * such a name, because where a scene writes and wires it is in the file even though where it was
 * declared is not.
 *
 * <p>The routing rule is what needs care: a {@code ROUTE} says {@code set_translation} about a field
 * the file wrote as {@code translation}, so a jump has to undo that naming before it can find a line
 * to land on. {@link Members#madeFrom(String)} is the same undoing the diagnostics use, so a name the
 * checker accepts is a name the editor can navigate.
 */
public final class Definitions {

    /** A range of the document, in offsets; the server turns it into a {@code Location}. */
    public record Target(int start, int end) {
    }

    private final ParseResult parse;
    private final SymbolTable symbols;
    private final Spec spec;
    private final AtOffset at;
    private final Context.Scope scope;

    private Definitions(ParseResult parse, SymbolTable symbols, Spec spec, int offset) {
        this.parse = parse;
        this.symbols = symbols;
        this.spec = spec;
        this.at = AtOffset.of(parse, offset);
        this.scope = at.scope(symbols);
    }

    /** Where the name under {@code offset} was declared, usually one line and at most a few. */
    public static List<Target> definition(ParseResult parse, SymbolTable symbols, Spec spec,
            int offset) {
        return new Definitions(parse, symbols, spec, offset).declaration();
    }

    /** Every place the name under {@code offset} is used, including the line it was declared on. */
    public static List<Target> references(ParseResult parse, SymbolTable symbols, Spec spec,
            int offset) {
        return new Definitions(parse, symbols, spec, offset).usages();
    }

    // ---- going to a declaration --------------------------------------------------------------

    private List<Target> declaration() {
        CstNode leaf = at.leaf();
        if (leaf == null || leaf.kind() == CstKind.MISSING) {
            return List.of();
        }
        String text = parse.text(leaf);
        return switch (leaf.kind()) {
            case NODE_NAME -> definedNode(leaf, text);
            case NODE_TYPE -> prototypeByName(text);
            case PROTO_NAME -> List.of(range(leaf));
            case FIELD_NAME -> memberDeclaration(leaf, text);
            default -> List.of();
        };
    }

    /**
     * The {@code DEF} a name stands for.
     *
     * <p>A {@code USE} is answered with the definition written before it, because a forward reference
     * is a mistake no reader will forgive; a {@code ROUTE} is resolved by name alone, because the
     * browser wires routes once the whole file is read and a route standing above the nodes it joins
     * is ordinary.
     */
    private List<Target> definedNode(CstNode leaf, String text) {
        CstNode clause = at.inside(CstKind.DEF_CLAUSE, CstKind.USE_CLAUSE, CstKind.ROUTE_DECL);
        if (clause == null) {
            return List.of();
        }
        if (clause.kind() == CstKind.DEF_CLAUSE) {
            return List.of(range(leaf));
        }
        SymbolTable.Definition found = clause.kind() == CstKind.USE_CLAUSE
                ? useDefinition(leaf, text)
                : routeDefinition(clause, text);
        return found == null ? List.of() : List.of(range(found.nameNode()));
    }

    private SymbolTable.Definition useDefinition(CstNode leaf, String text) {
        for (SymbolTable.Use use : symbols.uses()) {
            if (use.nameNode() == leaf) {
                return symbols.definition(use.scope(), text, use.order());
            }
        }
        return null;
    }

    private SymbolTable.Definition routeDefinition(CstNode decl, String text) {
        SymbolTable.Route route = route(decl);
        return route == null ? null
                : symbols.definition(route.scope(), text, Integer.MAX_VALUE);
    }

    /** The {@code PROTO} or {@code EXTERNPROTO} an instance's type name refers to. */
    private List<Target> prototypeByName(String text) {
        if (spec.hasNode(text)) {
            return List.of();
        }
        SymbolTable.Prototype prototype = symbols.prototype(scope.scope(), text);
        return prototype == null ? List.of() : List.of(range(prototype.nameNode()));
    }

    /**
     * The line that declares the field, event or interface member under the pointer.
     *
     * <p>Five constructs write a field name and each binds it somewhere different: a {@code Script}
     * body and a {@code PROTO} interface declare one where they stand, an {@code IS} reaches the
     * enclosing prototype's interface, and an assignment or a {@code ROUTE} end refers into a node.
     */
    private List<Target> memberDeclaration(CstNode leaf, String text) {
        CstNode line = at.inside(CstKind.FIELD_ASSIGNMENT, CstKind.SCRIPT_ELEMENT,
                CstKind.INTERFACE_DECL, CstKind.IS_CLAUSE, CstKind.ROUTE_DECL);
        if (line == null) {
            return List.of();
        }
        if (line.kind() == CstKind.SCRIPT_ELEMENT || line.kind() == CstKind.INTERFACE_DECL) {
            return List.of(range(leaf));
        }
        if (line.kind() == CstKind.IS_CLAUSE) {
            SymbolTable.Prototype defining = scope.defining();
            SymbolTable.Interface entry = defining == null ? null : defining.field(text);
            return entry == null ? List.of() : List.of(range(declarationName(entry)));
        }
        Owner owner = ownerOf(line, leaf, text);
        return owner == null ? List.of() : declarationOf(owner);
    }

    /**
     * One node and one name written against it, which is what both a jump and a search need.
     *
     * @param written the spelling this line used, which for a {@code ROUTE} end is the event an
     *        exposedField is driven through rather than the field itself
     */
    private record Owner(CstNode node, String type, int scope, String written) {
    }

    /** The node a field name on {@code line} names, or null where nothing can be told about it. */
    private Owner ownerOf(CstNode line, CstNode leaf, String text) {
        if (line.kind() == CstKind.ROUTE_DECL) {
            SymbolTable.Route route = route(line);
            SymbolTable.RouteEnd end = routeEnd(route, leaf);
            if (end == null) {
                return null;
            }
            SymbolTable.Definition definition =
                    symbols.definition(route.scope(), end.nodeName(), Integer.MAX_VALUE);
            return definition == null ? null : new Owner(definition.node(), definition.nodeType(),
                    definition.scope(), end.fieldName());
        }
        CstNode node = at.inside(CstKind.NODE);
        return node == null ? null : new Owner(node, SymbolTable.nodeType(parse, node),
                scope.scope(), text);
    }

    /**
     * What the file declares this name to be.
     *
     * <p>Only what the file declared has a line to reach, so a name the table knows is answered by
     * the node's own body instead: where this very scene gives it a value. A {@code Script} is asked
     * of its body, whose declarations are the file's own, and a prototype of its interface.
     */
    private List<Target> declarationOf(Owner owner) {
        SymbolTable.Interface declared = declared(owner);
        if (declared != null) {
            return List.of(range(declarationName(declared)));
        }
        CstNode line = writtenLine(owner);
        return line == null ? List.of() : List.of(range(line));
    }

    private SymbolTable.Interface declared(Owner owner) {
        Map<String, SymbolTable.Interface> table = interfaceOf(owner);
        SymbolTable.Interface exact = table.get(owner.written());
        if (exact != null) {
            return exact;
        }
        String base = Members.madeFrom(owner.written()).orElse("");
        SymbolTable.Interface exposed = base.isEmpty() ? null : table.get(base);
        return exposed != null && exposed.access().equals("exposedField") ? exposed : null;
    }

    private Map<String, SymbolTable.Interface> interfaceOf(Owner owner) {
        if (owner.type().equals("Script")) {
            return SymbolTable.scriptInterface(parse, owner.node());
        }
        if (owner.type().isEmpty() || spec.hasNode(owner.type())) {
            return Map.of();
        }
        SymbolTable.Prototype prototype = symbols.prototype(owner.scope(), owner.type());
        return prototype == null ? Map.of() : prototype.fields();
    }

    /**
     * The line in a node's own body that gives this name a value.
     *
     * <p>Where a field was declared nowhere but the language, this is the closest a scene comes to a
     * definition of it - and only where the node actually writes it, since a jump that lands back on
     * the line the pointer left is not a jump.
     */
    private CstNode writtenLine(Owner owner) {
        CstNode body = owner.node().firstChildOf(CstKind.NODE_BODY);
        if (body == null) {
            body = owner.node().firstChildOf(CstKind.SCRIPT_BODY);
        }
        if (body == null) {
            return null;
        }
        String base = Members.madeFrom(owner.written()).orElse(owner.written());
        for (CstNode element : body.children()) {
            if (element.kind() != CstKind.FIELD_ASSIGNMENT
                    && element.kind() != CstKind.SCRIPT_ELEMENT) {
                continue;
            }
            CstNode name = usable(element.firstChildOf(CstKind.FIELD_NAME));
            if (name == null || name == at.leaf()) {
                continue;
            }
            String said = parse.text(name);
            if (said.equals(owner.written()) || said.equals(base)) {
                return name;
            }
        }
        return null;
    }

    // ---- finding every use -------------------------------------------------------------------

    private List<Target> usages() {
        CstNode leaf = at.leaf();
        if (leaf == null || leaf.kind() == CstKind.MISSING) {
            return List.of();
        }
        String text = parse.text(leaf);
        return switch (leaf.kind()) {
            case NODE_NAME -> readsOfName(leaf, text);
            case NODE_TYPE, PROTO_NAME -> readsOfType(leaf, text);
            case FIELD_NAME -> readsOfMember(leaf, text);
            default -> List.of();
        };
    }

    /** Every {@code USE} and every {@code ROUTE} end of one node's name, plus the {@code DEF}. */
    private List<Target> readsOfName(CstNode leaf, String text) {
        CstNode clause = at.inside(CstKind.DEF_CLAUSE, CstKind.USE_CLAUSE, CstKind.ROUTE_DECL);
        SymbolTable.Definition definition = clause == null ? null
                : clause.kind() == CstKind.DEF_CLAUSE ? ownDefinition(leaf, text)
                : clause.kind() == CstKind.USE_CLAUSE ? useDefinition(leaf, text)
                : routeDefinition(clause, text);
        if (definition == null) {
            return List.of();
        }
        Set<Target> found = new LinkedHashSet<>();
        found.add(range(definition.nameNode()));
        for (SymbolTable.Use use : symbols.uses()) {
            if (use.scope() == definition.scope() && use.name().equals(text)) {
                found.add(range(use.nameNode()));
            }
        }
        for (SymbolTable.Route route : symbols.routes()) {
            if (route.scope() != definition.scope()) {
                continue;
            }
            for (SymbolTable.RouteEnd end : List.of(route.from(), route.to())) {
                if (end.nodeName().equals(text)) {
                    found.add(range(end.nameNode()));
                }
            }
        }
        return sorted(found);
    }

    /** The {@code DEF} the pointer stands on, matched by its name node so a redeclaration cannot confuse it. */
    private SymbolTable.Definition ownDefinition(CstNode leaf, String text) {
        for (SymbolTable.Definition definition : symbols.definitions(scope.scope())) {
            if (definition.nameNode() == leaf) {
                return definition;
            }
        }
        return symbols.definition(scope.scope(), text, Integer.MAX_VALUE);
    }

    /** A prototype's own declaration line and every instance of it the file writes. */
    private List<Target> readsOfType(CstNode leaf, String text) {
        if (spec.hasNode(text)) {
            return List.of();
        }
        SymbolTable.Prototype prototype = leaf.kind() == CstKind.PROTO_NAME ? declaredHere(leaf)
                : symbols.prototype(scope.scope(), text);
        if (prototype == null) {
            return List.of();
        }
        Set<Target> found = new LinkedHashSet<>();
        found.add(range(prototype.nameNode()));
        List<CstNode> spellings = new ArrayList<>();
        collect(parse.root(), CstKind.NODE_TYPE, spellings);
        for (CstNode spelled : spellings) {
            if (text.equals(parse.text(spelled))) {
                found.add(range(spelled));
            }
        }
        return sorted(found);
    }

    private SymbolTable.Prototype declaredHere(CstNode leaf) {
        for (SymbolTable.Prototype prototype : symbols.prototypes()) {
            if (prototype.nameNode() == leaf) {
                return prototype;
            }
        }
        return null;
    }

    /**
     * Everything that reads one member.
     *
     * <p>Three things a scene writes down: the {@code ROUTE}s that name it, the lines that give it a
     * value, and - for a {@code PROTO}'s interface - the {@code IS} clauses that bind it. Routes are
     * matched by resolving each end back to the line that declares it, so a route written {@code
     * set_intensity} is found from the {@code exposedField intensity} line rather than by comparing
     * strings that were never going to be equal. The value lines are matched the same way round: for
     * an interface member they are the lines of every instance the file writes.
     */
    private List<Target> readsOfMember(CstNode leaf, String text) {
        CstNode line = at.inside(CstKind.FIELD_ASSIGNMENT, CstKind.SCRIPT_ELEMENT,
                CstKind.INTERFACE_DECL, CstKind.IS_CLAUSE, CstKind.ROUTE_DECL);
        if (line == null) {
            return List.of();
        }
        Owner owner = ownerOf(line, leaf, text);
        SymbolTable.Interface declared = owner == null ? null : declared(owner);
        if (declared == null && line.kind() == CstKind.INTERFACE_DECL) {
            declared = declaredLine(line);
        }
        if (declared == null && line.kind() == CstKind.IS_CLAUSE && scope.defining() != null) {
            declared = scope.defining().field(text);
        }
        if (declared == null) {
            // The language declared this one, so the file's own lines are all there is to find.
            Set<Target> found = new LinkedHashSet<>();
            found.add(range(leaf));
            if (owner != null) {
                found.addAll(writtenTo(owner.type(), owner.written()));
                found.addAll(routedNames(owner));
            }
            return sorted(found);
        }
        Set<Target> found = new LinkedHashSet<>();
        found.add(range(declarationName(declared)));
        found.addAll(routedTo(declared));
        found.addAll(boundInBody(declared));
        found.addAll(givenValues(declared));
        return sorted(found);
    }

    /**
     * The lines where this file's instances of a {@code PROTO} hand one of its interface members a
     * value.
     *
     * <p>An instance writes the interface name and owns nothing else, so renaming that name has to
     * list every {@code Lamp { intensity ... }} the scene contains - which is the edit
     * find-all-references exists to protect against. A member declared by a {@code Script}'s body has
     * no prototype to search for, and its two instances are separate nodes with separate declarations
     * of the same word, which are not one another's references.
     */
    private List<Target> givenValues(SymbolTable.Interface entry) {
        SymbolTable.Prototype owning = owning(entry);
        return owning == null ? List.of() : writtenTo(owning.name(), entry.name());
    }

    /** The interface line the pointer stands on, as the symbol table's own record. */
    private SymbolTable.Interface declaredLine(CstNode line) {
        for (SymbolTable.Prototype prototype : symbols.prototypes()) {
            for (SymbolTable.Interface entry : prototype.declarations()) {
                if (entry.decl() == line) {
                    return entry;
                }
            }
        }
        return null;
    }

    /** The {@code ROUTE} ends whose field resolves to this very declaration. */
    private List<Target> routedTo(SymbolTable.Interface entry) {
        List<Target> found = new ArrayList<>();
        for (SymbolTable.Route route : symbols.routes()) {
            for (SymbolTable.RouteEnd end : List.of(route.from(), route.to())) {
                Owner owner = ownerOfRoute(route, end);
                if (owner != null && entry.equals(declared(owner))) {
                    found.add(range(end.fieldNode()));
                }
            }
        }
        return found;
    }

    /** The node one end of a route names, which is whose interface its field has to be read in. */
    private Owner ownerOfRoute(SymbolTable.Route route, SymbolTable.RouteEnd end) {
        if (end.fieldNode().kind() == CstKind.MISSING) {
            return null;
        }
        SymbolTable.Definition definition =
                symbols.definition(route.scope(), end.nodeName(), Integer.MAX_VALUE);
        return definition == null ? null : new Owner(definition.node(), definition.nodeType(),
                definition.scope(), end.fieldName());
    }

    /** The {@code ROUTE} ends that name this field on a node of the same type, declared or not. */
    private List<Target> routedNames(Owner owner) {
        List<Target> found = new ArrayList<>();
        for (SymbolTable.Route route : symbols.routes()) {
            for (SymbolTable.RouteEnd end : List.of(route.from(), route.to())) {
                SymbolTable.Definition definition =
                        symbols.definition(route.scope(), end.nodeName(), Integer.MAX_VALUE);
                if (definition == null || !owner.type().equals(definition.nodeType())
                        || end.fieldNode().kind() == CstKind.MISSING) {
                    continue;
                }
                if (sameMember(owner.written(), end.fieldName())) {
                    found.add(range(end.fieldNode()));
                }
            }
        }
        return found;
    }

    /** Every line of the file that gives {@code written} a value on a node of type {@code type}. */
    private List<Target> writtenTo(String type, String written) {
        List<Target> found = new ArrayList<>();
        writtenIn(parse.root(), type, written, found);
        return found;
    }

    private void writtenIn(CstNode node, String type, String written, List<Target> into) {
        if (node.kind() == CstKind.NODE) {
            if (type.equals(SymbolTable.nodeType(parse, node))) {
                addWritten(node.firstChildOf(CstKind.NODE_BODY), written, into);
                addWritten(node.firstChildOf(CstKind.SCRIPT_BODY), written, into);
            }
            return;
        }
        for (CstNode child : node.children()) {
            writtenIn(child, type, written, into);
        }
    }

    private void addWritten(CstNode body, String written, List<Target> into) {
        if (body == null) {
            return;
        }
        for (CstNode element : body.children()) {
            if (element.kind() != CstKind.FIELD_ASSIGNMENT
                    && element.kind() != CstKind.SCRIPT_ELEMENT) {
                continue;
            }
            CstNode name = usable(element.firstChildOf(CstKind.FIELD_NAME));
            if (name != null && sameMember(written, parse.text(name))) {
                into.add(range(name));
            }
        }
    }

    /** The {@code IS} clauses of the {@code PROTO} this interface line belongs to that bind it. */
    private List<Target> boundInBody(SymbolTable.Interface entry) {
        SymbolTable.Prototype owning = owning(entry);
        CstNode body = owning == null ? null : owning.decl().firstChildOf(CstKind.PROTO_BODY);
        if (body == null) {
            return List.of();
        }
        List<Target> found = new ArrayList<>();
        List<CstNode> bindings = new ArrayList<>();
        collect(body, CstKind.IS_CLAUSE, bindings);
        for (CstNode binding : bindings) {
            List<CstNode> names = binding.childrenOf(CstKind.FIELD_NAME);
            CstNode name = usable(names.isEmpty() ? null : names.get(names.size() - 1));
            if (name != null && parse.text(name).equals(entry.name())) {
                found.add(range(name));
            }
        }
        return found;
    }

    /** The {@code PROTO} or {@code EXTERNPROTO} that declared this member, or null for a Script's own. */
    private SymbolTable.Prototype owning(SymbolTable.Interface entry) {
        for (SymbolTable.Prototype candidate : symbols.prototypes()) {
            for (SymbolTable.Interface declared : candidate.declarations()) {
                if (declared.equals(entry)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // ---- reading the tree --------------------------------------------------------------------

    private SymbolTable.Route route(CstNode decl) {
        for (SymbolTable.Route candidate : symbols.routes()) {
            if (candidate.decl() == decl) {
                return candidate;
            }
        }
        return null;
    }

    private SymbolTable.RouteEnd routeEnd(SymbolTable.Route route, CstNode leaf) {
        if (route == null) {
            return null;
        }
        if (route.from().fieldNode() == leaf || route.from().nameNode() == leaf) {
            return route.from();
        }
        return route.to().fieldNode() == leaf || route.to().nameNode() == leaf ? route.to() : null;
    }

    /** Every node of {@code kind} below {@code node}, in document order. */
    private static void collect(CstNode node, CstKind kind, List<CstNode> into) {
        if (node.kind() == kind) {
            into.add(node);
            return;
        }
        for (CstNode child : node.children()) {
            collect(child, kind, into);
        }
    }

    /** The name on a declaration line, which is where a jump lands rather than the whole line. */
    private CstNode declarationName(SymbolTable.Interface entry) {
        CstNode name = usable(entry.decl().firstChildOf(CstKind.FIELD_NAME));
        return name == null ? entry.decl() : name;
    }

    /** Whether two spellings name one member, once a routed event's prefix or suffix is undone. */
    private static boolean sameMember(String written, String other) {
        return Members.madeFrom(written).orElse(written).equals(Members.madeFrom(other)
                .orElse(other));
    }

    private static CstNode usable(CstNode node) {
        return node == null || node.kind() == CstKind.MISSING ? null : node;
    }

    private Target range(CstNode node) {
        return new Target(node.start(), node.end());
    }

    private static List<Target> sorted(Set<Target> found) {
        return found.stream().sorted(Comparator.comparingInt(Target::start)).toList();
    }
}
