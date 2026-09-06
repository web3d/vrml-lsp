package org.vrml.lsp.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.semantic.Members;
import org.vrml.lsp.semantic.Members.Member;
import org.vrml.lsp.semantic.Semantics;
import org.vrml.lsp.semantic.SymbolTable;
import org.vrml.lsp.semantic.ValueConstraints;
import org.vrml.lsp.spec.Spec;

/**
 * What may be written where the cursor stands.
 *
 * <p>One method per {@link Context.Kind}: each reads the candidates out of the tree, the table and
 * the symbol table, in the order a reader would want them, and nothing else. Nothing here decides
 * what is <em>legal</em> - {@link Members} and {@link Semantics} already know, and the same calls
 * are what the diagnostics report through, so a suggestion that would be marked as an error cannot
 * come out of this class without changing one of them.
 *
 * <p>The candidates are the server's own type rather than {@code CompletionItem}s: this is layer
 * five, and the protocol objects belong to layer six, which is also where offsets become positions.
 */
public final class Completions {

    /** What a candidate is, which is how a client picks its icon and groups its list. */
    public enum Kind {
        /** A node type or a PROTO instance, at a place a node statement may begin. */
        NODE,
        /** A field a node body may be given a value for. */
        FIELD,
        /** One end of a ROUTE: an event, or the {@code set_}/{@code _changed} of an exposedField. */
        EVENT,
        /** A value, as its type's template spells it. */
        VALUE,
        /** A word the grammar reserves: an access type, {@code TRUE}, {@code NULL}. */
        KEYWORD,
        /** One of the 21 field types. */
        TYPE,
        /** A name this file already introduced, or one its PROTO interface declares. */
        NAME,
        /** A file next to this one, for an {@code EXTERNPROTO}'s URI list. */
        FILE
    }

    /**
     * One suggestion.
     *
     * @param insertText what lands in the document; a snippet when {@code snippet} says so
     * @param snippet whether {@link #insertText} still holds {@code $} placeholders, which the
     *        caller has already settled with the client
     * @param detail one line about what this is, shown next to the label
     * @param documentation markdown shown in the expand panel; empty when there is nothing to say
     */
    public record Candidate(String label, String insertText, boolean snippet, Kind kind,
                            String detail, String documentation) {
    }

    /** The four access types an interface line may begin with. */
    private static final List<Candidate> ACCESS_TYPES = List.of(
            new Candidate("field", "field", false, Kind.KEYWORD, "access type",
                    "initializeOnly: written once when the file is read, and never again."),
            new Candidate("exposedField", "exposedField", false, Kind.KEYWORD, "access type",
                    "readable and writable: a PROTO exposes a node's exposedField through an"
                            + " exposedField of the same type."),
            new Candidate("eventIn", "eventIn", false, Kind.KEYWORD, "access type",
                    "what a ROUTE may send into this interface."),
            new Candidate("eventOut", "eventOut", false, Kind.KEYWORD, "access type",
                    "what this interface sends out, and what a ROUTE may read."));

    /**
     * What one access type is for, in the same sentence the completion panel shows.
     *
     * <p>Read from {@link #ACCESS_TYPES} rather than repeated: a hover over {@code eventIn} and the
     * suggestion that typed it must not be able to say two different things.
     */
    static String accessMeaning(String word) {
        return ACCESS_TYPES.stream().filter(candidate -> candidate.label().equals(word))
                .map(Candidate::documentation).findFirst().orElse("");
    }

    /** The two words a boolean field takes, spelled as the lexer reads them. */
    private static final List<String> BOOLEANS = List.of("TRUE", "FALSE");

    private final ParseResult parse;
    private final SymbolTable symbols;
    private final Spec spec;
    private final Context ctx;
    private final boolean snippets;
    private final List<String> nearbyUris;

    private Completions(ParseResult parse, SymbolTable symbols, Spec spec, Context ctx,
                        boolean snippets, List<String> nearbyUris) {
        this.parse = parse;
        this.symbols = symbols;
        this.spec = spec;
        this.ctx = ctx;
        this.snippets = snippets;
        this.nearbyUris = nearbyUris;
    }

    /**
     * The candidates for one context, best first.
     *
     * @param snippets whether the client understands snippet placeholders; without it the templates
     *        are rendered to the text they would leave behind
     * @param nearbyUris scene files next to the document, which only the caller can know: this class
     *        reads no file system
     */
    public static List<Candidate> of(ParseResult parse, SymbolTable symbols, Spec spec, Context ctx,
                                     boolean snippets, List<String> nearbyUris) {
        return new Completions(parse, symbols, spec, ctx, snippets, nearbyUris).list();
    }

    private List<Candidate> list() {
        return switch (ctx.kind()) {
            case NODE_STATEMENT -> nodeStatements();
            case FIELD_NAME -> fieldNames();
            case FIELD_VALUE -> fieldValues();
            case DEFINED_NAME -> definedNames();
            case ROUTE_OUTPUT -> routeEnd(true);
            case ROUTE_INPUT -> routeEnd(false);
            case ACCESS_TYPE -> ACCESS_TYPES;
            case FIELD_TYPE -> fieldTypes();
            case INTERFACE_NAME -> interfaceNames();
            case URI -> uris();
            // A name the author invents has nothing to be chosen from, and a slot the grammar has
            // settled has nothing missing: an empty list is the honest answer for both.
            case NEW_NAME, NONE -> List.of();
        };
    }

    // ---- nodes -------------------------------------------------------------------------------

    /**
     * A node statement, at the file root, in a PROTO body, or after {@code DEF name}.
     *
     * <p>What may stand at the root is what a grouping node takes as a child, so that set comes
     * first and the appearance, geometry and texture nodes that cannot - the ones VRL2010 marks -
     * come after rather than not at all: a file being moved around has reason to write one for a
     * moment, and a list that hides it is a list that gets ignored.
     */
    private List<Candidate> nodeStatements() {
        Set<String> displayable = Semantics.sceneChildren(spec);
        List<Candidate> here = new ArrayList<>();
        List<Candidate> elsewhere = new ArrayList<>();
        Set<String> offered = new HashSet<>();
        for (Spec.Node node : spec.nodes()) {
            offered.add(node.name());
            Candidate candidate = new Candidate(node.name(), node.name(), false, Kind.NODE,
                    node.component() + ", level " + node.level(), nodeDoc(node));
            (displayable.contains(node.name()) ? here : elsewhere).add(candidate);
        }
        here.addAll(prototypes(false, here));
        here.addAll(elsewhere);
        return here;
    }

    /**
     * The PROTOs and EXTERNPROTs this scope can see, in declaration order.
     *
     * @param alreadyOffered the candidates being extended, whose labels are not repeated: a PROTO
     *        named for a node the table already has is shadowed by the file, and the author means
     *        the one they wrote
     */
    private List<Candidate> prototypes(boolean asValue, List<Candidate> alreadyOffered) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate offered : alreadyOffered) {
            seen.add(offered.label());
        }
        for (SymbolTable.Prototype prototype : symbols.prototypes(ctx.scope())) {
            if (!seen.add(prototype.name())) {
                continue;
            }
            out.add(new Candidate(prototype.name(), asValue ? withBraces(prototype.name())
                    : prototype.name(), asValue && snippets, Kind.NODE,
                    prototype.external() ? "EXTERNPROTO" : "PROTO", interfaceDoc(prototype)));
        }
        return out;
    }

    /**
     * Node candidates, from the table's names.
     *
     * @param asValue whether a whole node is what is wanted here, which is the one place a
     *        completion may write the braces itself: at a statement position the author may still be
     *        typing a {@code DEF}, so only the name belongs there
     */
    private List<Candidate> nodes(List<String> names, boolean asValue) {
        List<Candidate> out = new ArrayList<>(names.size());
        for (String name : names) {
            Optional<Spec.Node> known = spec.node(name);
            out.add(new Candidate(name, asValue ? withBraces(name) : name, asValue && snippets,
                    Kind.NODE, known.map(node -> node.component() + ", level " + node.level())
                    .orElse("PROTO"), known.map(Completions::nodeDoc).orElse("")));
        }
        return out;
    }

    private String withBraces(String name) {
        return snippets ? name + " {$0}" : name + " {}";
    }

    // ---- fields ------------------------------------------------------------------------------

    /**
     * A field name of the node the cursor stands in.
     *
     * <p>Only the names a body may be given a value for, because the events that come with an
     * {@code exposedField} are not written here - VRL2019 says so - and a {@code Script} body is the
     * one place a declaration competes with an assignment, so its four access types come along too.
     */
    private List<Candidate> fieldNames() {
        List<String> written = Members.written(parse, ctx.node());
        List<Candidate> unwritten = new ArrayList<>();
        List<Candidate> again = new ArrayList<>();
        for (Member member : owner()) {
            if (!member.assignable()) {
                continue;
            }
            boolean set = written.stream().anyMatch(member::answersTo);
            Candidate candidate = new Candidate(member.name(), member.name(), false, Kind.FIELD,
                    member.summary() + (set ? ", already set here" : ""),
                    fieldDoc(member));
            (set ? again : unwritten).add(candidate);
        }
        unwritten.addAll(again);
        if ("Script".equals(ownerType())) {
            unwritten.addAll(ACCESS_TYPES);
        }
        return unwritten;
    }

    /** The value of the field named on the left, which is a node when the field holds nodes. */
    private List<Candidate> fieldValues() {
        Optional<Member> field = wantedMember();
        if (field.isPresent()) {
            return holdsOne(field.get()) ? nodeValues(field.get()) : valueTemplates(field.get());
        }
        // No member to ask: an interface line's own default value, whose type the line declares.
        return declaredTypeValue();
    }

    /**
     * Whether a whole node is what belongs here.
     *
     * <p>The type says so for a built-in node, and a list of accepted kinds says so for anything
     * else - a {@code PROTO} instance's own {@code SFNode} constrains nothing, but it still takes a
     * node rather than a number.
     */
    private static boolean holdsOne(Member field) {
        return field.holdsNodes() || !field.childNodes().isEmpty();
    }

    /**
     * Candidates for a field that holds nodes.
     *
     * <p>The field says which kinds it takes, and those are the list; {@code NULL} is always one of
     * them, because un-setting a node is what its default value means. A field the table leaves
     * unconstrained - a PROTO's own {@code SFNode} - can hold any node at all, so it gets any.
     *
     * <p>A prototype joins the list whatever the constraint says. The field's own restriction is
     * spelled with an X3D abstract type name that no VRML97 file can write, so nothing in the table
     * can tell whether a PROTO declared in this file fills the slot - and a file built out of
     * PROTOs, which is most of them, would be offered a list to choose from that it cannot use.
     */
    private List<Candidate> nodeValues(Member field) {
        List<String> kinds = field.childNodes();
        List<Candidate> out = new ArrayList<>(kinds.isEmpty() ? nodeStatements()
                : nodes(kinds, true));
        out.addAll(prototypes(true, out));
        out.add(new Candidate("NULL", "NULL", false, Kind.KEYWORD, field.type(),
                "no node: what this field holds unless the file says otherwise."));
        return out;
    }

    /**
     * Candidates for a field that holds a number, a word or a string.
     *
     * <p>The words the language allows come first, since choosing one is what makes the line right;
     * the type's template comes after, which is the answer for every field that has no fixed set of
     * words. A multiple value whose opening bracket has not been written yet is given one, because
     * writing the values of an {@code MFVec3f} without them is VRL1006.
     *
     * <p>A boolean field is the exception that stops at the words: the lexer reads {@code TRUE} and
     * {@code FALSE} and nothing else, so the table's {@code ${1|true,false|}} would insert two
     * identifiers where a value belongs - a completion the next keystroke would be reported for.
     */
    private List<Candidate> valueTemplates(Member field) {
        Optional<Spec.ValueType> type = spec.type(field.type());
        if (type.isEmpty()) {
            return List.of();
        }
        boolean wrap = field.type().startsWith("MF") && !bracketed();
        List<String> words = words(field);
        if (words.isEmpty() && isBoolean(field.type())) {
            words = BOOLEANS;
        }
        List<Candidate> out = new ArrayList<>(words.size() + 1);
        for (String word : words) {
            String quoted = isText(field.type()) ? "\"" + word + "\"" : word;
            out.add(new Candidate(word, brackets(quoted, wrap), false, Kind.VALUE,
                    field.type() + " value", fieldDoc(field)));
        }
        if (isBoolean(field.type())) {
            return out;
        }
        String snippet = type.get().snippet();
        String text = brackets(plain(snippet), wrap);
        String insert = brackets(snippet, wrap);
        out.add(new Candidate(text, snippets ? insert : text, snippets && hasPlaceholder(insert),
                Kind.VALUE, field.type() + " " + shape(field.type()), fieldDoc(field)));
        return out;
    }

    /** One candidate for an interface line's default value, from the type the line itself says. */
    private List<Candidate> declaredTypeValue() {
        CstNode element = ctx.container();
        CstNode declared = element == null ? null : element.firstChildOf(CstKind.FIELD_TYPE);
        if (declared == null || declared.kind() == CstKind.MISSING) {
            return List.of();
        }
        Optional<Spec.ValueType> type = spec.type(parse.text(declared));
        if (type.isEmpty()) {
            return List.of();
        }
        String snippet = type.get().snippet();
        String text = plain(snippet);
        return List.of(new Candidate(text, text, false, Kind.VALUE, type.get().name() + " value",
                "the default of the interface line being declared"));
    }

    /** Wrap a value's text in the brackets a multiple value needs. */
    private static String brackets(String text, boolean wrap) {
        return wrap ? "[ " + text + " ]" : text;
    }

    /** The words the spec fixes for this field, which {@code Values} would otherwise complain about. */
    private List<String> words(Member field) {
        return ValueConstraints.enumeratedValues(ownerType(), field.name());
    }

    /** Whether the parser has read the {@code [} of this field's multiple value already. */
    private boolean bracketed() {
        CstNode element = ctx.container();
        if (element == null) {
            return false;
        }
        CstNode value = element.firstChildOf(CstKind.VALUE);
        if (value != null && value.firstChildOf(CstKind.MF_VALUE) != null) {
            return true;
        }
        return element.firstChildOf(CstKind.MF_VALUE) != null;
    }

    // ---- names -------------------------------------------------------------------------------

    /**
     * The names this file has DEF'd.
     *
     * <p>A {@code USE} reads front to back, so a definition below it is VRL2014's problem rather
     * than a candidate; a {@code ROUTE} may be written before the nodes it wires, so every
     * definition in the scope is offered whatever its position.
     */
    private List<Candidate> definedNames() {
        boolean route = ctx.container() != null && ctx.container().kind() == CstKind.ROUTE_DECL;
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SymbolTable.Definition definition : symbols.definitions(ctx.scope())) {
            if (!route && definition.nameNode().start() >= ctx.replaceStart()) {
                continue;
            }
            if (!seen.add(definition.name())) {
                continue;
            }
            out.add(new Candidate(definition.name(), definition.name(), false, Kind.NAME,
                    ": " + definition.nodeType(), "DEF'd as `" + definition.nodeType() + "`"));
        }
        return out;
    }

    /**
     * One end of a ROUTE: what the named node sends, or what it takes.
     *
     * <p>An unknown name gets no list. The node is already marked as nothing having DEF'd it, and a
     * field list read off a guess would be a second mistake built on the first.
     */
    private List<Candidate> routeEnd(boolean source) {
        String subject = ctx.routeSubject();
        if (subject.isEmpty()) {
            return List.of();
        }
        SymbolTable.Definition definition =
                symbols.definition(ctx.scope(), subject, Integer.MAX_VALUE);
        if (definition == null) {
            return List.of();
        }
        List<Member> members = Members.of(parse, spec, symbols, definition.nodeType(),
                definition.node(), definition.scope());
        List<Candidate> out = new ArrayList<>();
        for (Member end : source ? Members.readable(members) : Members.writable(members)) {
            out.add(new Candidate(end.name(), end.name(), false, Kind.EVENT, end.summary(),
                    fieldDoc(end)));
        }
        return out;
    }

    // ---- interfaces --------------------------------------------------------------------------

    /** The 21 field types, for a PROTO or {@code Script} interface line. */
    private List<Candidate> fieldTypes() {
        List<Candidate> out = new ArrayList<>();
        for (Spec.ValueType type : spec.types()) {
            out.add(new Candidate(type.name(), type.name(), false, Kind.TYPE, shape(type.name()),
                    ""));
        }
        return out;
    }

    /**
     * The names of the enclosing PROTO's interface that this {@code IS} may bind to.
     *
     * <p>Filtered by what the field on the body side can do, which is the same test VRL2014 makes:
     * completing an {@code IS} into a binding the next keystroke would be reported for is not
     * completion.
     */
    private List<Candidate> interfaceNames() {
        SymbolTable.Prototype defining = ctx.defining();
        if (defining == null) {
            return List.of();
        }
        String access = bodyAccess();
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SymbolTable.Interface entry : defining.declarations()) {
            if (!access.isEmpty() && !Semantics.exposes(access, entry.access())
                    || !seen.add(entry.name())) {
                continue;
            }
            out.add(new Candidate(entry.name(), entry.name(), false, Kind.NAME,
                    entry.type() + " " + entry.access(),
                    "declared by PROTO `" + defining.name() + "`"));
        }
        return out;
    }

    /** How the name on the body side of the {@code IS} may be used, or "" when it cannot be told. */
    private String bodyAccess() {
        CstNode element = ctx.container();
        CstNode name = element == null ? null : element.firstChildOf(CstKind.FIELD_NAME);
        if (name == null || name.kind() == CstKind.MISSING) {
            return "";
        }
        return Members.find(owner(), parse.text(name)).map(Member::access).orElse("");
    }

    /** The quoted names next to the document, for an {@code EXTERNPROTO}'s URI list. */
    private List<Candidate> uris() {
        List<Candidate> out = new ArrayList<>(nearbyUris.size());
        for (String name : nearbyUris) {
            out.add(new Candidate(name, name, false, Kind.FILE, "file next to this one", ""));
        }
        return out;
    }

    // ---- what the cursor stands in -----------------------------------------------------------

    /** The names of the node the cursor stands in, which is the one whose body or route this is. */
    private List<Member> owner() {
        return Members.of(parse, spec, symbols, ownerType(), ctx.node(), ctx.scope());
    }

    private String ownerType() {
        return ctx.node() == null ? "" : SymbolTable.nodeType(parse, ctx.node());
    }

    /** The field whose value is being written. */
    private Optional<Member> wantedMember() {
        String written = writtenName();
        return written.isEmpty() ? Optional.empty() : Members.find(owner(), written);
    }

    /** The field name on the left of the cursor's slot, or "" when there is none. */
    private String writtenName() {
        CstNode element = ctx.container();
        CstNode name = element == null ? null : element.firstChildOf(CstKind.FIELD_NAME);
        return name == null || name.kind() == CstKind.MISSING ? "" : parse.text(name);
    }

    // ---- words -------------------------------------------------------------------------------

    private static String nodeDoc(Spec.Node node) {
        StringBuilder doc = new StringBuilder(node.summary());
        append(doc, "component", node.component() + " " + node.level());
        append(doc, "specification", node.specUrl());
        return doc.toString();
    }

    private static String fieldDoc(Member field) {
        StringBuilder doc = new StringBuilder(field.doc());
        append(doc, "default", field.defaultValue().isEmpty() ? "nothing"
                : "`" + field.defaultValue() + "`");
        return doc.toString();
    }

    private static String interfaceDoc(SymbolTable.Prototype prototype) {
        StringBuilder doc = new StringBuilder(prototype.external()
                ? "An EXTERNPROTO: the implementation is one of the files listed here."
                : "A PROTO declared in this file.");
        for (SymbolTable.Interface entry : prototype.declarations()) {
            doc.append("\n\n- ").append(entry.access()).append(" ").append(entry.type()).append(" ")
                    .append(entry.name());
        }
        return doc.toString();
    }

    /** One more {@code - key: value} line, unless there was no value to give. */
    private static void append(StringBuilder doc, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (doc.length() > 0) {
            doc.append("\n\n");
        }
        doc.append("- ").append(key).append(": ").append(value);
    }

    /** What writing one value of this type means, in as few words as say it. */
    static String shape(String type) {
        return switch (type) {
            case "SFString", "MFString" -> "quoted text";
            case "SFBool", "MFBool" -> "TRUE or FALSE";
            case "SFNode" -> "a node or NULL";
            case "MFNode" -> "any number of nodes";
            case "SFImage" -> "width height channels, then one hex number per pixel";
            case "SFTime" -> "seconds from the scene's clock";
            case "SFInt32", "MFInt32" -> "whole numbers";
            default -> type.startsWith("MF") ? "repeated numbers" : "numbers";
        };
    }

    private static boolean isBoolean(String type) {
        return type.endsWith("Bool");
    }

    private static boolean isText(String type) {
        return type.equals("SFString") || type.equals("MFString");
    }

    /** Whether inserted text still holds a tab stop a snippet-capable client will consume. */
    private static boolean hasPlaceholder(String text) {
        return text.contains("$");
    }

    /**
     * The text a snippet leaves behind once its placeholders are filled with their own defaults.
     *
     * <p>For a client that never agreed to snippets, which would otherwise show {@code $1} as the
     * completed value, and for a label, which has to read as the value rather than as the template.
     */
    private static String plain(String template) {
        StringBuilder out = new StringBuilder(template.length());
        int at = 0;
        while (at < template.length()) {
            char c = template.charAt(at);
            if (c != '$') {
                out.append(c);
                at++;
                continue;
            }
            if (at + 1 >= template.length()) {
                break;
            }
            char next = template.charAt(at + 1);
            if (next == '{') {
                int close = template.indexOf('}', at);
                if (close < 0) {
                    break;
                }
                out.append(placeholder(template.substring(at + 2, close)));
                at = close + 1;
                continue;
            }
            if (!Character.isDigit(next)) {
                out.append(c);
                at++;
                continue;
            }
            // A bare $0 or $1 is a tab stop, and adds nothing to what the text says.
            while (at < template.length() && template.charAt(at) != ' ' && template.charAt(at) != ','
                    && template.charAt(at) != '\n' && template.charAt(at) != '"') {
                at++;
            }
        }
        return out.toString().strip().replaceAll(",+$", "");
    }

    /** The text of one {@code ${n:...}} or {@code ${n|a,b|}} placeholder, without its markers. */
    private static String placeholder(String body) {
        int bar = body.indexOf('|');
        if (bar > 0 && body.endsWith("|")) {
            int end = body.indexOf('|', bar + 1);
            return end > bar ? body.substring(bar + 1, end) : "";
        }
        int colon = body.indexOf(':');
        return colon > 0 ? body.substring(colon + 1) : "";
    }
}
