package org.vrml.lsp.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.parser.ParseResult;

/**
 * The names a scene declares, and everywhere they are used.
 *
 * <p>Everything here needs more than one statement to answer: whether a {@code USE} has a
 * definition, whether a {@code ROUTE} points at something that exists, whether a {@code PROTO}
 * instance is writing to a field its interface declares. The parser cannot tell those, because it
 * reads one production at a time, so this class takes the tree it produces and groups the names by
 * the scope VRML97 gives them: the file, plus one scope per {@code PROTO} body, whose definitions
 * are created anew with each instance and are invisible outside it.
 *
 * <p>Order is kept ({@link Definition#order()}, {@link Use#order()}) because VRML97 reads a file
 * front to back: a {@code USE} of a name defined only later is a different mistake from a
 * {@code USE} of a name nobody defines. {@code ROUTE} does not care about order - a route may be
 * written before the nodes it wires - so it looks a definition up by name alone.
 *
 * <p>Nothing here reports an issue. An unknown name is a fact for {@link Semantics} to weigh, and
 * the same tree is what {@code definition}/{@code documentSymbol} will read in M5.
 */
public final class SymbolTable {

    /** The file itself; every {@code PROTO} body gets a scope number above this. */
    public static final int FILE_SCOPE = 0;

    /** A {@code DEF name node} binding. */
    public record Definition(String name, int order, int scope, String nodeType, CstNode nameNode,
                             CstNode node) {
    }

    /** A {@code USE name}, with the name's own node so an error can point at it. */
    public record Use(String name, int order, int scope, CstNode nameNode) {
    }

    /** One {@code field SFTime x} entry of a PROTO, EXTERNPROTO or Script interface. */
    public record Interface(String name, String type, String access, CstNode decl) {
    }

    /**
     * A PROTO or EXTERNPROTO: what it is called, what its interface exports, and which scope its
     * body's definitions belong to ({@code bodyScope} is its own scope for a PROTO, the enclosing
     * one for an EXTERNPROTO, which has no body).
     *
     * <p>{@code declarations} is the interface as written, duplicates and all; {@code fields} is
     * the same thing keyed by name for lookup. A repeated name is a mistake worth reporting, so the
     * list has to survive the map.
     */
    public record Prototype(String name, int scope, CstNode decl, CstNode nameNode,
                            List<Interface> declarations, Map<String, Interface> fields,
                            boolean external, int bodyScope) {

        public Interface field(String wanted) {
            return fields.get(wanted);
        }
    }

    /** One endpoint of a ROUTE: the node name and field name written, each with its own node. */
    public record RouteEnd(String nodeName, CstNode nameNode, String fieldName, CstNode fieldNode) {
    }

    /** A {@code ROUTE a.b TO c.d} statement. */
    public record Route(int order, int scope, CstNode decl, RouteEnd from, RouteEnd to) {
    }

    private final Map<Integer, List<Definition>> definitions;
    private final List<Use> uses;
    private final Map<Integer, List<Prototype>> prototypes;
    private final List<Route> routes;

    private SymbolTable(Map<Integer, List<Definition>> definitions, List<Use> uses,
                        Map<Integer, List<Prototype>> prototypes, List<Route> routes) {
        this.definitions = definitions;
        this.uses = uses;
        this.prototypes = prototypes;
        this.routes = routes;
    }

    /** Walk the tree once and gather every name. */
    public static SymbolTable of(ParseResult parse) {
        Collector collector = new Collector(parse);
        collector.openScope(FILE_SCOPE);
        collector.walk(parse.root(), FILE_SCOPE);
        return new SymbolTable(collector.definitions, collector.uses, collector.prototypes,
                collector.routes);
    }

    /** All definitions in {@code scope}, in document order. */
    public List<Definition> definitions(int scope) {
        return definitions.getOrDefault(scope, List.of());
    }

    /**
     * The definition of {@code name} a reader at {@code beforeOrder} sees, or {@code null}.
     *
     * <p>The latest one before that point, which is what a streaming parser has in hand when it
     * reaches the use. A duplicate {@code DEF} is legal VRML97 - {@code
     * error_handling/double_def.wrl} says so in its own comment - and the later definition
     * shadows the earlier one from there on.
     */
    public Definition definition(int scope, String name, int beforeOrder) {
        Definition found = null;
        for (Definition definition : definitions(scope)) {
            if (definition.name().equals(name) && definition.order() < beforeOrder) {
                found = definition;
            }
        }
        return found;
    }

    /** Whether {@code name} is defined anywhere in {@code scope}, whatever its order. */
    public boolean isDefinedAnywhere(int scope, String name) {
        for (Definition definition : definitions(scope)) {
            if (definition.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Every {@code USE} in the file, in document order. */
    public List<Use> uses() {
        return uses;
    }

    /**
     * The PROTO or EXTERNPROTO named {@code name} that {@code scope} can see, or {@code null}.
     *
     * <p>The last declaration, not the first: redeclaring a PROTO name replaces the definition, which
     * is what a reader does when it meets two of them and what {@code vrml97/proto3.wrl} and
     * {@code proto7.wrl} exist to pin down. Inner scopes shadow outer ones, so the search starts at
     * {@code scope} and works outward.
     */
    public Prototype prototype(int scope, String name) {
        for (int at = scope; at >= FILE_SCOPE; at--) {
            List<Prototype> inScope = prototypes.getOrDefault(at, List.of());
            for (int last = inScope.size() - 1; last >= 0; last--) {
                if (inScope.get(last).name().equals(name)) {
                    return inScope.get(last);
                }
            }
        }
        return null;
    }

    /** Every PROTO {@code scope} can see, its own innermost first. */
    public List<Prototype> prototypes(int scope) {
        List<Prototype> visible = new ArrayList<>();
        for (int at = scope; at >= FILE_SCOPE; at--) {
            visible.addAll(prototypes.getOrDefault(at, List.of()));
        }
        return visible;
    }

    /**
     * Every PROTO and EXTERNPROTO in the file, whatever can see it, document order within a scope.
     *
     * <p>Not the list one scope may use - {@link #prototypes(int)} answers that - but the whole
     * table, for the sweep that judges each declaration on its own terms and for suggesting a name
     * to a typo that no visible prototype explains.
     */
    public List<Prototype> prototypes() {
        List<Prototype> all = new ArrayList<>();
        for (List<Prototype> inScope : prototypes.values()) {
            all.addAll(inScope);
        }
        return all;
    }

    /** Every ROUTE in the file, in document order, each carrying the scope it was written in. */
    public List<Route> routes() {
        return routes;
    }

    /**
     * The interface a {@code Script} node declares in its own body.
     *
     * <p>Script is the awkward case for {@code ROUTE} and {@code IS}: its eventIns and eventOuts
     * are written inside the node, not in the generated table, so a route touching a Script can
     * only be checked against what the file says.
     */
    public static Map<String, Interface> scriptInterface(ParseResult parse, CstNode node) {
        CstNode body = node.firstChildOf(CstKind.SCRIPT_BODY);
        if (body == null) {
            return Map.of();
        }
        Map<String, Interface> fields = new LinkedHashMap<>();
        for (CstNode element : body.childrenOf(CstKind.SCRIPT_ELEMENT)) {
            Interface entry = interfaceOf(parse, element);
            if (entry != null) {
                fields.putIfAbsent(entry.name(), entry);
            }
        }
        return Collections.unmodifiableMap(fields);
    }

    /** {@code accessType fieldType fieldName} - the three leaves one interface line has. */
    private static Interface interfaceOf(ParseResult parse, CstNode decl) {
        CstNode access = decl.firstChildOf(CstKind.ACCESS_TYPE);
        CstNode type = decl.firstChildOf(CstKind.FIELD_TYPE);
        CstNode name = decl.firstChildOf(CstKind.FIELD_NAME);
        if (name == null || name.kind() == CstKind.MISSING) {
            return null;
        }
        return new Interface(parse.text(name), textOrEmpty(parse, type), textOrEmpty(parse, access),
                decl);
    }

    /**
     * The type name a {@code NODE} statement writes, or "" when it has none.
     *
     * <p>{@code Script} is a keyword, and {@code node()} consumes it as one, so the first leaf of a
     * Script's NODE is a KEYWORD rather than a NODE_TYPE. Both spell the node's name; a MISSING
     * placeholder - the parser asked for a name and found something else - has no text to read.
     */
    public static String nodeType(ParseResult parse, CstNode node) {
        CstNode type = node.firstChildOf(CstKind.NODE_TYPE);
        if (type != null) {
            return type.kind() == CstKind.MISSING ? "" : parse.text(type);
        }
        for (CstNode child : node.children()) {
            if (child.kind() == CstKind.KEYWORD) {
                return parse.text(child);
            }
        }
        return "";
    }

    private static String textOrEmpty(ParseResult parse, CstNode node) {
        return node == null || node.kind() == CstKind.MISSING ? "" : parse.text(node);
    }

    /** Mutable during one {@link #of(ParseResult)}, read into the {@link SymbolTable} afterwards. */
    private static final class Collector {

        private final ParseResult parse;
        private final Map<Integer, List<Definition>> definitions = new LinkedHashMap<>();
        private final Map<Integer, List<Prototype>> prototypes = new LinkedHashMap<>();
        private final List<Use> uses = new ArrayList<>();
        private final List<Route> routes = new ArrayList<>();
        private int order;
        private int nextScope = FILE_SCOPE + 1;

        Collector(ParseResult parse) {
            this.parse = parse;
        }

        void openScope(int scope) {
            definitions.putIfAbsent(scope, new ArrayList<>());
            prototypes.putIfAbsent(scope, new ArrayList<>());
        }

        /**
         * Depth-first over the tree, carrying the scope.
         *
         * <p>{@code NODE_STATEMENT} and {@code PROTO_DECL} are handled here rather than by their
         * own children, because a definition is only meaningful together with the node it names -
         * and {@link CstNode} has no parent pointer to recover it from below.
         */
        void walk(CstNode node, int scope) {
            switch (node.kind()) {
                case NODE_STATEMENT -> {
                    nodeStatement(node, scope);
                    return;
                }
                case PROTO_DECL -> {
                    prototype(node, scope, false);
                    return;
                }
                case EXTERN_PROTO_DECL -> {
                    prototype(node, scope, true);
                    return;
                }
                case USE_CLAUSE -> use(node, scope);
                case ROUTE_DECL -> route(node, scope);
                default -> {
                }
            }
            for (CstNode child : node.children()) {
                walk(child, scope);
            }
        }

        /** {@code nodeStatement ::= node | DEF nodeName node | USE nodeName}. */
        private void nodeStatement(CstNode statement, int scope) {
            CstNode clause = statement.firstChildOf(CstKind.DEF_CLAUSE);
            CstNode built = statement.firstChildOf(CstKind.NODE);
            if (clause != null && built != null) {
                CstNode nameNode = clause.firstChildOf(CstKind.NODE_NAME);
                if (nameNode != null && nameNode.kind() != CstKind.MISSING) {
                    definitions.get(scope).add(new Definition(parse.text(nameNode), order++, scope,
                            nodeType(parse, built), nameNode, built));
                }
            }
            for (CstNode child : statement.children()) {
                walk(child, scope);
            }
        }

        private void use(CstNode clause, int scope) {
            CstNode nameNode = clause.firstChildOf(CstKind.NODE_NAME);
            if (nameNode != null && nameNode.kind() != CstKind.MISSING) {
                uses.add(new Use(parse.text(nameNode), order++, scope, nameNode));
            }
        }

        private void route(CstNode decl, int scope) {
            List<CstNode> names = decl.childrenOf(CstKind.NODE_NAME);
            List<CstNode> fields = decl.childrenOf(CstKind.FIELD_NAME);
            if (names.size() < 2 || fields.size() < 2) {
                // The parser already said which part is missing. Guessing an endpoint from a
                // half-written route would report a mistake the author never made.
                return;
            }
            routes.add(new Route(order++, scope, decl, end(names.get(0), fields.get(0)),
                    end(names.get(1), fields.get(1))));
        }

        private RouteEnd end(CstNode nameNode, CstNode fieldNode) {
            return new RouteEnd(parse.text(nameNode), nameNode, textOrEmpty(parse, fieldNode),
                    fieldNode);
        }

        private void prototype(CstNode decl, int scope, boolean external) {
            CstNode nameNode = decl.firstChildOf(CstKind.PROTO_NAME);
            CstNode body = decl.firstChildOf(CstKind.PROTO_BODY);
            List<Interface> declarations = new ArrayList<>();
            Map<String, Interface> fields = new LinkedHashMap<>();
            for (CstNode interfaceDecl : decl.childrenOf(CstKind.INTERFACE_DECL)) {
                Interface entry = interfaceOf(parse, interfaceDecl);
                if (entry != null) {
                    declarations.add(entry);
                    fields.putIfAbsent(entry.name(), entry);
                }
            }
            // The body's scope number is allocated here, before the body is walked, so that the
            // Prototype can carry it: IS clauses and nested DEFs need to know which proto they are
            // in even though they are reached through the body.
            int bodyScope = scope;
            if (!external && body != null) {
                bodyScope = nextScope++;
                openScope(bodyScope);
            }
            if (nameNode != null && nameNode.kind() != CstKind.MISSING) {
                prototypes.get(scope).add(new Prototype(parse.text(nameNode), scope, decl, nameNode,
                        List.copyOf(declarations), Collections.unmodifiableMap(fields), external,
                        bodyScope));
            }
            for (CstNode child : decl.children()) {
                walk(child, child == body ? bodyScope : scope);
            }
        }
    }
}
