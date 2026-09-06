package org.vrml.lsp.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.diagnostics.Codes;
import org.vrml.lsp.diagnostics.Issue;
import org.vrml.lsp.lexer.Token;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.spec.Spec;

/**
 * What the file says, as far as VRML97 and the generated table can tell.
 *
 * <p>The parser reads one production at a time and cannot know that {@code apperance} is not a
 * {@code Shape} field, or that a {@code ROUTE} names a node nobody defined. This class reads the
 * finished tree against {@link Spec} and {@link SymbolTable} and says so.
 *
 * <p>Two rules keep the noise down, and both come from how a broken file is edited rather than
 * written:
 * <ul>
 * <li>a statement the parser had to repair is not judged at all ({@link Broken}) - one missing brace
 * makes every name after it look wrong, and the structural code already points at the brace;</li>
 * <li>a document whose first line says {@code #X3D} is not judged at all. {@code
 * events/boolean_filter.wrl} and {@code nurbs/simple_nurbssurface.wrl} are X3D files wearing a
 * {@code .wrl} name; their nodes are not VRML97's nodes, and 3005 on the header is the useful
 * answer, not forty unknown names.</li>
 * </ul>
 *
 * <p>Severity follows Xj3D, whose {@code VRML97Reader.convertException()} sends a bad field name or
 * value to {@code warningReport()} and keeps loading the world. So a value a browser can talk its way
 * past is a WARNING; what is an ERROR is a name no reader can resolve, or a statement that cannot
 * mean what it says.
 */
public final class Semantics {

    /** Where the walk stands: which scope, whose fields a name must match, what a field may hold. */
    private record At(int scope, Spec.Node node, SymbolTable.Prototype instance,
                      SymbolTable.Prototype defining, List<String> accepted, boolean root) {

        static At scene(int scope) {
            return new At(scope, null, null, null, List.of(), true);
        }

        /** A PROTO body: its own scope, and its interface is what {@code IS} may name. */
        At protoBody(SymbolTable.Prototype defining) {
            return new At(defining.bodyScope(), null, null, defining, List.of(), false);
        }

        /** A node body, whose field names are checked against {@code target}. */
        At nodeBody(SymbolTable.Prototype ofInstance, Spec.Node target) {
            return new At(scope, target, ofInstance, defining, List.of(), false);
        }

        /** The value of a node-holding field, which may only hold {@code kinds}. */
        At field(List<String> kinds) {
            return new At(scope, null, null, defining, kinds, false);
        }
    }

    private Semantics() {
    }

    /** Everything semantic about one parsed document. */
    public static List<Issue> check(ParseResult parse, Spec spec, SymbolTable symbols) {
        return new Checker(parse, spec, symbols).run();
    }

    /**
     * The node kinds a grouping node's {@code children} takes, which the file root takes too.
     *
     * <p>Public because two questions have this one answer: standing at the root with a node that
     * is not in the set is VRL2010, and a completion list at a statement position has to put what
     * would not be reported first.
     */
    public static Set<String> sceneChildren(Spec spec) {
        Set<String> names = new HashSet<>();
        for (Spec.Node node : spec.nodes()) {
            for (Spec.Field field : node.fields()) {
                if (field.name().equals("children")) {
                    names.addAll(field.childNodes());
                }
            }
        }
        return names;
    }

    /**
     * Whether one access lets a {@code ROUTE} carry a value in the given direction: {@code source}
     * asks what the end may be read from, otherwise what it may be written to.
     *
     * <p>An {@code exposedField} does both, which is why it is not on either side of the choice.
     * Public for the same reason as {@link #exposes}: the check that reports a wrong direction and
     * the list that offers only the right ones must be one rule, not two that can drift.
     */
    public static boolean carries(String access, boolean source) {
        return access.equals("exposedField") || access.equals(source ? "eventOut" : "eventIn");
    }

    /**
     * Whether a body field may be exposed through an interface field, per what each side can do:
     * an {@code eventIn} has to be writable, an {@code eventOut} readable, an
     * {@code exposedField} both, and a {@code field} - initializeOnly - only ever read.
     *
     * <p>Public because it decides two things with the same answer: {@code IS} is reported when it
     * binds incompatibly, and an {@code IS} completion must not offer a name that would be reported.
     */
    public static boolean exposes(String bodyAccess, String interfaceAccess) {
        boolean readable = bodyAccess.equals("field") || bodyAccess.equals("exposedField");
        boolean writable = bodyAccess.equals("exposedField") || bodyAccess.equals("eventIn");
        return switch (interfaceAccess) {
            case "eventIn" -> writable;
            case "eventOut" -> readable || bodyAccess.equals("eventOut");
            case "exposedField" -> bodyAccess.equals("exposedField");
            default -> readable;
        };
    }

    /** Positions where the parse already complained or had to insert a placeholder. */
    private static final class Broken {

        private final int[] starts;
        private final int[] tokenStarts;

        Broken(ParseResult parse) {
            List<Integer> marks = new ArrayList<>();
            for (Issue issue : parse.issues()) {
                marks.add(issue.start());
            }
            holes(parse.root(), marks);
            marks.sort(Comparator.naturalOrder());
            starts = marks.stream().mapToInt(Integer::intValue).toArray();
            List<Token> tokens = parse.significantTokens();
            tokenStarts = new int[tokens.size()];
            for (int at = 0; at < tokens.size(); at++) {
                tokenStarts[at] = tokens.get(at).start;
            }
        }

        private static void holes(CstNode node, List<Integer> marks) {
            if (node.kind() == CstKind.MISSING || node.kind() == CstKind.ERROR) {
                marks.add(node.start());
                return;
            }
            for (CstNode child : node.children()) {
                holes(child, marks);
            }
        }

        /**
         * Whether anything at all went wrong between {@code start} and {@code end}.
         *
         * <p>The window reaches to the start of the token after {@code end}, because a placeholder
         * is placed where the missing token should have been - after the last one the parser
         * understood, so an unfinished statement's own hole sits just past its end. That costs a
         * clean statement before such a position its semantic pass at most once, and it had nothing
         * to be blamed for there: the parser read it completely.
         */
        boolean within(int start, int end) {
            int lo = 0;
            int hi = starts.length;
            int last = reach(end);
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (starts[mid] < start) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo < starts.length && starts[lo] <= last;
        }

        private int reach(int end) {
            int lo = 0;
            int hi = tokenStarts.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (tokenStarts[mid] < end) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo < tokenStarts.length ? tokenStarts[lo] : end;
        }
    }

    private static final class Checker {

        private final ParseResult parse;
        private final Spec spec;
        private final SymbolTable symbols;
        private final Broken broken;
        private final Set<String> sceneChildren;
        private final List<Issue> issues = new ArrayList<>();

        Checker(ParseResult parse, Spec spec, SymbolTable symbols) {
            this.parse = parse;
            this.spec = spec;
            this.symbols = symbols;
            this.broken = new Broken(parse);
            this.sceneChildren = sceneChildren(spec);
        }

        List<Issue> run() {
            if (declaresX3D()) {
                return List.of();
            }
            statements(parse.root(), At.scene(SymbolTable.FILE_SCOPE));
            uses();
            routes();
            prototypes();
            return issues;
        }

        /** The dialect claim is HeaderCheck's to report; here it only decides whether to stay quiet. */
        private boolean declaresX3D() {
            CharSequence source = parse.source();
            int end = 0;
            while (end < source.length() && source.charAt(end) != '\n') {
                end++;
            }
            return source.subSequence(0, end).toString().strip().regionMatches(true, 1, "X3D", 0, 3);
        }

        // ---- statements ----------------------------------------------------------------------

        /** Anything holding a list of statements: the file, a PROTO body, a {@code STATEMENT} wrapper. */
        private void statements(CstNode list, At at) {
            for (CstNode child : list.children()) {
                switch (child.kind()) {
                    case STATEMENT, PROTO_BODY -> statements(child, at);
                    case NODE_STATEMENT -> nodeStatement(child, at);
                    case PROTO_DECL, EXTERN_PROTO_DECL -> declaration(child, at);
                    // USE and ROUTE are judged from the symbol table, where a name's order and scope
                    // are known together with everything else of its kind.
                    default -> {
                    }
                }
            }
        }

        /** {@code nodeStatement ::= node | DEF nodeName node | USE nodeName}. */
        private void nodeStatement(CstNode statement, At at) {
            if (broken.within(statement.start(), statement.end())) {
                return;
            }
            CstNode node = statement.firstChildOf(CstKind.NODE);
            if (node == null) {
                return;
            }
            String type = SymbolTable.nodeType(parse, node);
            if (type.isEmpty()) {
                return;
            }
            Spec.Node target = spec.node(type).orElse(null);
            SymbolTable.Prototype instance = target == null ? symbols.prototype(at.scope(), type) : null;
            CstNode name = node.firstChildOf(CstKind.NODE_TYPE);
            int typeStart = name == null ? node.start() : name.start();
            int typeEnd = name == null ? node.end() : name.end();
            if (target == null && instance == null) {
                unknownNode(type, typeStart, typeEnd);
            } else if (target != null && at.root() && !sceneChildren.contains(type)) {
                issues.add(Issue.warning(Codes.NODE_NOT_DISPLAYABLE, "'" + type + "' cannot stand at"
                        + " the root of a file, which holds what a Group holds children of; only a"
                        + " node that may appear in a children list belongs here", typeStart, typeEnd));
            } else if (instance == null && !at.accepted().isEmpty() && !at.accepted().contains(type)) {
                issues.add(Issue.warning(Codes.NODE_KIND_NOT_ACCEPTED, "'" + type + "' cannot be a"
                        + " value of this field, which holds " + kinds(at.accepted()), typeStart,
                        typeEnd));
            }
            body(node, at.nodeBody(instance, target));
        }

        /** A name that is neither a VRML97 node nor a PROTO visible here. */
        private void unknownNode(String type, int start, int end) {
            issues.add(Issue.error(Codes.UNKNOWN_NODE_TYPE, "there is no VRML97 node type '" + type
                    + "', and no PROTO of that name is declared in this file", start, end));
            suggest(type, nodeNames(), start, end, "node type");
        }

        /** "an" before a vowel, so a message never reads "a eventIn". */
        private static String article(String word) {
            return switch (word.charAt(0)) {
                case 'a', 'e', 'i', 'o', 'u', 'A', 'E', 'I', 'O', 'U' -> "an";
                default -> "a";
            };
        }

        /** A candidate list of 45 names does not fit in a message; the count and a few do. */
        private static String kinds(List<String> accepted) {
            if (accepted.size() <= 4) {
                return String.join(", ", accepted);
            }
            return String.join(", ", accepted.subList(0, 3)) + ", and "
                    + (accepted.size() - 3) + " other kinds";
        }

        private List<String> nodeNames() {
            List<String> names = new ArrayList<>(spec.nodes().size() + 4);
            for (Spec.Node node : spec.nodes()) {
                names.add(node.name());
            }
            for (SymbolTable.Prototype prototype : symbols.prototypes()) {
                names.add(prototype.name());
            }
            return names;
        }

        // ---- bodies --------------------------------------------------------------------------

        /** The {@code NODE_BODY} or {@code SCRIPT_BODY} of a node, whose elements are checked. */
        private void body(CstNode node, At at) {
            CstNode body = node.firstChildOf(CstKind.NODE_BODY);
            boolean script = body == null;
            if (script) {
                body = node.firstChildOf(CstKind.SCRIPT_BODY);
            }
            if (body == null) {
                return;
            }
            Set<String> declared = script ? new HashSet<>(4) : null;
            for (CstNode element : body.children()) {
                switch (element.kind()) {
                    case FIELD_ASSIGNMENT -> assignment(element, at);
                    case SCRIPT_ELEMENT -> scriptElement(element, at, declared);
                    case PROTO_DECL, EXTERN_PROTO_DECL -> declaration(element, at);
                    case STATEMENT -> statements(element, at);
                    default -> {
                    }
                }
            }
        }

        private void assignment(CstNode element, At at) {
            CstNode nameNode = element.firstChildOf(CstKind.FIELD_NAME);
            if (nameNode == null || nameNode.kind() == CstKind.MISSING) {
                return;
            }
            String name = parse.text(nameNode);
            CstNode is = element.firstChildOf(CstKind.IS_CLAUSE);
            CstNode value = element.firstChildOf(CstKind.VALUE);
            if (at.node() != null) {
                Spec.Field field = at.node().field(name).orElse(null);
                if (field == null) {
                    // The two names an exposedField brings with it are real names that a body cannot
                    // be given a value for, which is 2019's point rather than 2002's.
                    String derived = derivedEvent(name,
                            wanted -> at.node().field(wanted).map(Spec.Field::access).orElse(null));
                    if (derived == null) {
                        unknownField(name, at.node().name(), fieldNames(at.node()), nameNode);
                    } else {
                        assignable(nameNode, name, derived);
                    }
                    nodeValues(value, at.field(List.of()));
                    return;
                }
                if (is != null) {
                    binding(is, at, field.name(), field.access());
                } else {
                    assignable(nameNode, name, field.access());
                    issues.addAll(Values.check(parse, at.node().name(), field.name(),
                            spec.type(field.type()).orElse(null), value));
                }
                nodeValues(value, at.field(field.childNodes()));
            } else if (at.instance() != null) {
                SymbolTable.Interface entry = at.instance().field(name);
                if (entry == null) {
                    String derived = derivedEvent(name, wanted -> interfaceAccess(at.instance(),
                            wanted));
                    if (derived == null) {
                        unknownField(name, at.instance().name(), interfaceNames(at.instance()),
                                nameNode);
                    } else {
                        assignable(nameNode, name, derived);
                    }
                } else {
                    if (is != null) {
                        binding(is, at, entry.name(), entry.access());
                    } else {
                        assignable(nameNode, name, entry.access());
                        // A PROTO instance's field is the prototype author's own, so no built-in
                        // node's range applies to it; only the type's shape does.
                        issues.addAll(Values.check(parse, "", entry.name(),
                                spec.type(entry.type()).orElse(null), value));
                    }
                }
                nodeValues(value, at.field(List.of()));
            } else {
                // Nothing to match the name against - an unknown node's body, or a value inside one.
                // The nodes below it are still real, which is what this descent is for.
                if (is != null) {
                    binding(is, at, name, "");
                }
                nodeValues(value, at.field(List.of()));
            }
        }

        /** A field a scene file may write a value for: not an event, which only a ROUTE drives. */
        private void assignable(CstNode nameNode, String name, String access) {
            if (access.equals("eventIn") || access.equals("eventOut")) {
                issues.add(Issue.error(Codes.FIELD_NOT_ASSIGNABLE, "'" + name + "' is an " + access
                        + ", which a node body cannot be given a value for" + (access.equals("eventIn")
                        ? "; a ROUTE or the browser is what sends one" : "; a ROUTE is what reads it"),
                        nameNode.start(), nameNode.end()));
            }
        }

        /** {@code fieldName IS otherName}, which only means something inside a PROTO body. */
        private void binding(CstNode is, At at, String fieldName, String access) {
            if (at.defining() == null) {
                issues.add(Issue.error(Codes.IS_OUTSIDE_PROTO, "'" + fieldName + " IS ...' binds a node"
                        + " inside a PROTO body to that PROTO's interface, and this is not a PROTO"
                        + " body", is.start(), is.end()));
                return;
            }
            CstNode target = is.firstChildOf(CstKind.FIELD_NAME);
            if (target == null || target.kind() == CstKind.MISSING) {
                return;
            }
            String wanted = parse.text(target);
            SymbolTable.Interface declared = at.defining().field(wanted);
            if (declared == null) {
                issues.add(Issue.error(Codes.IS_UNKNOWN_FIELD, "PROTO '" + at.defining().name()
                        + "' declares no '" + wanted + "' in its interface, so '" + fieldName
                        + "' has nothing to bind to", target.start(), target.end()));
            } else if (!access.isEmpty() && !exposes(access, declared.access())) {
                issues.add(Issue.error(Codes.IS_UNKNOWN_FIELD, "'" + fieldName + "' is " + article(access)
                        + " " + access + " in this node, which cannot be exposed through the PROTO's '"
                        + wanted + "' declared as " + declared.access(), target.start(), target.end()));
            }
        }

        private void unknownField(String name, String owner, List<String> candidates, CstNode nameNode) {
            issues.add(Issue.error(Codes.UNKNOWN_FIELD, "'" + owner + "' has no field named '" + name
                    + "'", nameNode.start(), nameNode.end()));
            suggest(name, candidates, nameNode.start(), nameNode.end(), "field");
        }

        private List<String> fieldNames(Spec.Node node) {
            List<String> names = new ArrayList<>(node.fields().size());
            for (Spec.Field field : node.fields()) {
                names.add(field.name());
                names.addAll(field.aliases());
            }
            return names;
        }

        private List<String> interfaceNames(SymbolTable.Prototype prototype) {
            List<String> names = new ArrayList<>(prototype.declarations().size());
            for (SymbolTable.Interface entry : prototype.declarations()) {
                names.add(entry.name());
            }
            return names;
        }

        /** The node statements written as a value, which are nodes even when the field's name was not. */
        private void nodeValues(CstNode value, At inside) {
            if (value != null) {
                visit(value, inside);
            }
        }

        private void visit(CstNode node, At inside) {
            for (CstNode child : node.children()) {
                switch (child.kind()) {
                    case NODE_STATEMENT -> nodeStatement(child, inside);
                    case SF_VALUE, MF_VALUE, NODE_LIST -> visit(child, inside);
                    default -> {
                    }
                }
            }
        }

        // ---- interfaces: PROTO, EXTERNPROTO, Script -------------------------------------------

        private void declaration(CstNode decl, At at) {
            String owner = textOf(decl.firstChildOf(CstKind.PROTO_NAME));
            interfaceClashes(decl, owner);
            for (CstNode entry : decl.childrenOf(CstKind.INTERFACE_DECL)) {
                interfaceEntry(entry, at);
            }
            CstNode body = decl.firstChildOf(CstKind.PROTO_BODY);
            if (body == null) {
                return;
            }
            SymbolTable.Prototype declared =
                    owner.isEmpty() ? null : symbols.prototype(at.scope(), owner);
            // An unnamed PROTO is one the parser already reported; its body still gets walked, just
            // with no interface for an IS clause to be checked against.
            statements(body, declared == null ? at.field(List.of()) : at.protoBody(declared));
        }

        /** A {@code Script} body's own interface line, which is an interface entry plus a name clash. */
        private void scriptElement(CstNode entry, At at, Set<String> declared) {
            interfaceEntry(entry, at);
            CstNode name = entry.firstChildOf(CstKind.FIELD_NAME);
            if (declared != null && name != null && name.kind() != CstKind.MISSING
                    && !declared.add(parse.text(name))) {
                issues.add(Issue.error(Codes.DUPLICATE_PROTO_FIELD, "'" + parse.text(name)
                        + "' is declared twice in this Script's interface", name.start(), name.end()));
            }
        }

        /** One {@code accessType fieldType fieldName [value | IS]} line, wherever it is written. */
        private void interfaceEntry(CstNode entry, At at) {
            CstNode type = entry.firstChildOf(CstKind.FIELD_TYPE);
            String typeName = textOf(type);
            if (!typeName.isEmpty() && spec.type(typeName).isEmpty()) {
                issues.add(Issue.error(Codes.UNKNOWN_FIELD_TYPE, "'" + typeName + "' is not one of"
                        + " VRML97's 21 field types", type.start(), type.end()));
            }
            CstNode is = entry.firstChildOf(CstKind.IS_CLAUSE);
            if (is != null) {
                binding(is, at, textOf(entry.firstChildOf(CstKind.FIELD_NAME)),
                        textOf(entry.firstChildOf(CstKind.ACCESS_TYPE)));
            }
            CstNode value = entry.firstChildOf(CstKind.VALUE);
            if (value != null && !typeName.isEmpty()) {
                issues.addAll(Values.check(parse, "", textOf(entry.firstChildOf(CstKind.FIELD_NAME)),
                        spec.type(typeName).orElse(null), value));
            }
        }

        /** Names an interface declares twice: an instance below cannot say which one it means. */
        private void interfaceClashes(CstNode decl, String owner) {
            Set<String> seen = new HashSet<>();
            for (CstNode entry : decl.childrenOf(CstKind.INTERFACE_DECL)) {
                CstNode name = entry.firstChildOf(CstKind.FIELD_NAME);
                String written = textOf(name);
                if (written.isEmpty()) {
                    continue;
                }
                if (!seen.add(written)) {
                    issues.add(Issue.error(Codes.DUPLICATE_PROTO_FIELD, "'" + written + "' is declared"
                            + " twice in " + owner + "'s interface", name.start(), name.end()));
                }
            }
        }

        // ---- DEF, USE and ROUTE --------------------------------------------------------------

        private void uses() {
            for (SymbolTable.Use use : symbols.uses()) {
                CstNode name = use.nameNode();
                if (broken.within(name.start(), name.end())
                        || symbols.definition(use.scope(), use.name(), use.order()) != null) {
                    continue;
                }
                if (symbols.isDefinedAnywhere(use.scope(), use.name())) {
                    issues.add(Issue.error(Codes.DEF_AFTER_USE, "'" + use.name() + "' is defined below"
                            + " this USE, and VRML97 reads a file front to back: move the DEF above it",
                            name.start(), name.end()));
                } else {
                    issues.add(Issue.error(Codes.UNDEFINED_USE, "nothing in this file is DEF'd as '"
                            + use.name() + "'", name.start(), name.end()));
                }
            }
        }

        private void routes() {
            for (SymbolTable.Route route : symbols.routes()) {
                if (broken.within(route.decl().start(), route.decl().end())) {
                    continue;
                }
                routeEnd(route, route.from(), true);
                routeEnd(route, route.to(), false);
            }
        }

        /**
         * One endpoint: a node that exists, a field it declares, and a direction that can carry the
         * value.
         *
         * <p>Whether the two ends' types agree is deliberately not checked. An {@code exposedField}
         * routes as its {@code _changed} event, {@code SFTime} and {@code SFFloat} cross that
         * boundary in browsers, and a wrong rule here would be a wrong error on a file that works.
         */
        private void routeEnd(SymbolTable.Route route, SymbolTable.RouteEnd end, boolean source) {
            SymbolTable.Definition definition =
                    symbols.definition(route.scope(), end.nodeName(), Integer.MAX_VALUE);
            if (definition == null) {
                issues.add(Issue.error(Codes.ROUTE_UNKNOWN_NODE, "no node is DEF'd as '"
                        + end.nodeName() + "', so this ROUTE leads nowhere", end.nameNode().start(),
                        end.nameNode().end()));
                return;
            }
            if (end.fieldNode().kind() == CstKind.MISSING) {
                return;
            }
            FieldAnswer answer = accessOf(definition, end.fieldName());
            if (!answer.known()) {
                return;
            }
            if (answer.access() == null) {
                issues.add(Issue.error(Codes.ROUTE_UNKNOWN_FIELD, "'" + definition.nodeType() + "' has"
                        + " no field or event named '" + end.fieldName() + "'", end.fieldNode().start(),
                        end.fieldNode().end()));
                return;
            }
            // The direction rule is the language's, not this node's, so it reads the same way here
            // as it does in the ROUTE-end completion list.
            if (!carries(answer.access(), source)) {
                issues.add(Issue.error(Codes.ROUTE_DIRECTION, "'" + end.fieldName() + "' is "
                        + article(answer.access()) + " " + answer.access() + ", and a ROUTE "
                        + (source ? "can only read from"
                        : "can only write to") + " " + (source ? "an eventOut or exposedField"
                        : "an eventIn or exposedField"), end.fieldNode().start(),
                        end.fieldNode().end()));
            }
        }

        /**
         * What one name means on a DEF'd node: {@code known} says whether the node had a field list
         * to consult at all, which {@code access} being null does not - it means either "no such
         * field" or "no way to tell".
         */
        private record FieldAnswer(boolean known, String access) {

            static FieldAnswer of(String access) {
                return new FieldAnswer(true, access);
            }

            static FieldAnswer absent() {
                return new FieldAnswer(true, null);
            }

            static FieldAnswer unknowable() {
                return new FieldAnswer(false, null);
            }
        }

        /**
         * How a field of a DEF'd node may be used.
         *
         * <p>A {@code Script} takes its events from what the file declares inside it and a PROTO
         * instance from its prototype's interface; only for a built-in node is the table enough. For
         * a node whose own type was unknown there is no answer to give, and none is reported: the
         * type is already marked, and saying "no such field" about a name no reader resolved would
         * be a second complaint about one typo.
         */
        private FieldAnswer accessOf(SymbolTable.Definition definition, String field) {
            String type = definition.nodeType();
            if (type.equals("Script") && definition.node() != null) {
                Map<String, SymbolTable.Interface> script =
                        SymbolTable.scriptInterface(parse, definition.node());
                // A Script's own three fields stay fields, so a route may still read them: what the
                // body declares is asked first, and the table only answers for a name it does not.
                // `Members#of` merges the same two sources in the same order, so that a ROUTE end
                // offered by completion is never one reported here.
                Spec.Node declared = spec.node("Script").orElse(null);
                return resolve(field, name -> {
                    SymbolTable.Interface entry = script.get(name);
                    if (entry != null) {
                        return entry.access();
                    }
                    return declared == null ? null : declared.field(name).map(Spec.Field::access)
                            .orElse(null);
                });
            }
            Optional<Spec.Node> node = spec.node(type);
            if (node.isPresent()) {
                Spec.Node target = node.get();
                return resolve(field, name -> target.field(name).map(Spec.Field::access).orElse(null));
            }
            SymbolTable.Prototype prototype = symbols.prototype(definition.scope(), type);
            if (prototype == null) {
                return FieldAnswer.unknowable();
            }
            return resolve(field, name -> interfaceAccess(prototype, name));
        }

        /** How a PROTO's interface says one name may be used, or null if it declares no such name. */
        private static String interfaceAccess(SymbolTable.Prototype prototype, String name) {
            SymbolTable.Interface entry = prototype.field(name);
            return entry == null ? null : entry.access();
        }

        /**
         * Look {@code name} up among declared fields, then among the events an {@code exposedField}
         * brings with it: {@code field x} is written {@code set_x} from outside and reaches the
         * world as {@code x_changed}. Every real ROUTE uses those names, and Xj3D generates them
         * instead of declaring them, so the table has no rows for them - which is why this is the
         * language's rule to apply rather than something the data could have said.
         */
        private static FieldAnswer resolve(String name, Function<String, String> declared) {
            String access = declared.apply(name);
            if (access != null) {
                return FieldAnswer.of(access);
            }
            String derived = derivedEvent(name, declared);
            return derived == null ? FieldAnswer.absent() : FieldAnswer.of(derived);
        }

        /**
         * Whether {@code name} is the {@code set_} or {@code _changed} event of some other name the
         * {@code declared} lookup answers {@code exposedField} for, and if so which of the two it is.
         * Only an exposedField derives events; a {@code field} has no {@code set_} and an
         * {@code eventOut} no {@code _changed}.
         */
        private static String derivedEvent(String name, Function<String, String> declared) {
            Optional<String> base = Members.madeFrom(name);
            if (base.isEmpty()) {
                return null;
            }
            // `set_` is how a route writes to an exposedField and `_changed` how it reads from one.
            return exposed(declared.apply(base.get()))
                    ? name.startsWith("set_") ? "eventIn" : "eventOut" : null;
        }

        private static boolean exposed(String access) {
            return access != null && access.equals("exposedField");
        }

        // ---- the PROTO table itself ----------------------------------------------------------

        /**
         * A PROTO or EXTERNPROTO named after a node type: instances of it can never be reached,
         * because the reader finds the node with that name first. Two PROTOs sharing one name is
         * not reported, because the language does say which wins - the later one, which is what
         * {@link SymbolTable#prototype(int, String)} returns - and {@code vrml97/proto3.wrl},
         * {@code proto7.wrl} and the {@code externproto*} files are Xj3D's own tests that a reader
         * tolerates the redeclaration.
         */
        private void prototypes() {
            for (SymbolTable.Prototype prototype : symbols.prototypes()) {
                CstNode name = prototype.nameNode();
                if (spec.hasNode(prototype.name()) && !broken.within(name.start(), name.end())) {
                    issues.add(Issue.warning(Codes.PROTO_NAME_CONFLICT, "a PROTO cannot be called '"
                            + prototype.name() + "': that is a VRML97 node type, so an instance of"
                            + " either one would be ambiguous", name.start(), name.end()));
                }
            }
        }

        // ---- did-you-mean --------------------------------------------------------------------

        /** One edit away is advice; two names that merely look alike are noise. */
        private void suggest(String written, List<String> candidates, int start, int end, String what) {
            String best = null;
            int bestDistance = 3;
            for (String candidate : candidates) {
                int distance = distance(written, candidate, bestDistance);
                if (distance > 0 && distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
            if (best != null) {
                issues.add(Issue.hint(Codes.NAME_SUGGESTION, "did you mean the " + what + " '" + best
                        + "'?", start, end));
            }
        }

        /**
         * Levenshtein, case-insensitively, abandoned as soon as a row proves the candidate is
         * further away than the best so far.
         */
        private static int distance(String written, String candidate, int limit) {
            if (written.equalsIgnoreCase(candidate)) {
                return written.equals(candidate) ? 0 : 1;
            }
            int[] previous = new int[candidate.length() + 1];
            int[] current = new int[candidate.length() + 1];
            for (int c = 0; c <= candidate.length(); c++) {
                previous[c] = c;
            }
            for (int r = 1; r <= written.length(); r++) {
                current[0] = r;
                int best = r;
                int row = Character.toLowerCase(written.charAt(r - 1));
                for (int c = 1; c <= candidate.length(); c++) {
                    int cost = row == Character.toLowerCase(candidate.charAt(c - 1)) ? 0 : 1;
                    current[c] = Math.min(Math.min(current[c - 1] + 1, previous[c] + 1),
                            previous[c - 1] + cost);
                    best = Math.min(best, current[c]);
                }
                if (best > limit) {
                    return limit;
                }
                int[] swap = previous;
                previous = current;
                current = swap;
            }
            return previous[candidate.length()];
        }

        /** Source text of a node, or "" for a hole the parser left. */
        private String textOf(CstNode node) {
            return node == null || node.kind() == CstKind.MISSING ? "" : parse.text(node);
        }
    }
}
