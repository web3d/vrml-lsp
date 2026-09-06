package org.vrml.lsp.semantic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.parser.ParseResult;
import org.vrml.lsp.spec.Spec;

/**
 * The names one node accepts, wherever they come from.
 *
 * <p>Three sources, and a node reads at most two of them: {@link Spec} for a built-in node, the
 * interface a {@code Script} declares inside its own body, and the interface of the {@code PROTO}
 * an instance refers to. Nothing here decides what may be <em>done</em> with a name -
 * {@link Member#assignable()} and {@link #readable(List)} say what the language allows, and
 * {@link Semantics} reports through the same rules - this class only assembles the list a body or a
 * {@code ROUTE} is checked against, so that a completion list and a diagnostic cannot disagree
 * about which names exist.
 *
 * <p>{@code Script} is the one node read from both at once: the table gives it {@code url},
 * {@code mustEvaluate} and {@code directOutput}, and everything else it accepts was written by the
 * file. The body's own declarations come first, so a {@code Script} that declares its own
 * {@code url} eventIn is listed as the file says rather than as the table remembers.
 */
public final class Members {

    /**
     * One accepted name.
     *
     * @param defaultValue what the node does when the file writes nothing: the table's text for a
     *        built-in node, or the value the interface line itself carries for a member the file
     *        declared
     * @param doc the description the table carries, empty for a member the file declared, which has
     *        no prose to quote
     * @param childNodes the node kinds a node-holding field takes, empty for every other field and
     *        for an interface's own {@code SFNode}, which constrains nothing
     * @param aliases other spellings the parser accepts for this one field ({@code Switch.choice})
     */
    public record Member(String name, String type, String access, String defaultValue, String doc,
                         List<String> childNodes, List<String> aliases) {

        /** Whether the file may write {@code candidate} to mean this member. */
        public boolean answersTo(String candidate) {
            return name.equals(candidate) || aliases.contains(candidate);
        }

        /** Whether a node body may give this name a value; only a {@code ROUTE} drives an event. */
        public boolean assignable() {
            return access.equals("field") || access.equals("exposedField");
        }

        /** Whether a {@code ROUTE} may read this name out of the node. */
        public boolean readable() {
            return Semantics.carries(access, true);
        }

        /** Whether a {@code ROUTE} may write a value to this name. */
        public boolean writable() {
            return Semantics.carries(access, false);
        }

        /** Whether this name's value is a node rather than a number, a word or a string. */
        public boolean holdsNodes() {
            return type.equals("SFNode") || type.equals("MFNode");
        }

        /** The one line that says what this name is: its type, its name, and how it may be used. */
        public String summary() {
            return type + " " + name + " (" + access + ")";
        }
    }

    private Members() {
    }

    /**
     * Every name a node written as {@code type} accepts.
     *
     * @param node the node's own {@code NODE}, which a {@code Script} has to be read out of; may be
     *        null, in which case a Script is listed as the bare table knows it
     * @param scope where the node stands, since an instance's interface is the {@code PROTO} that
     *        is visible there
     * @return the members in declaration order, empty when nothing in the file or the table says
     *         what this node is - an unknown type has no names to list
     */
    public static List<Member> of(ParseResult parse, Spec spec, SymbolTable symbols, String type,
                                  CstNode node, int scope) {
        if (type.isEmpty()) {
            return List.of();
        }
        List<Member> members = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (type.equals("Script") && node != null) {
            for (SymbolTable.Interface entry : SymbolTable.scriptInterface(parse, node).values()) {
                add(members, seen, declared(parse, entry));
            }
        }
        Optional<Spec.Node> builtIn = spec.node(type);
        if (builtIn.isPresent()) {
            for (Spec.Field field : builtIn.get().fields()) {
                add(members, seen, new Member(field.name(), field.type(), field.access(),
                        field.defaultValue(), field.doc(), field.childNodes(), field.aliases()));
            }
            return members;
        }
        SymbolTable.Prototype prototype = symbols.prototype(scope, type);
        if (prototype == null) {
            return List.of();
        }
        for (SymbolTable.Interface entry : prototype.declarations()) {
            add(members, seen, declared(parse, entry));
        }
        return members;
    }

    private static Member declared(ParseResult parse, SymbolTable.Interface entry) {
        return new Member(entry.name(), entry.type(), entry.access(), writtenDefault(parse, entry),
                "", List.of(), List.of());
    }

    /**
     * The value an interface line carries with it, which is that member's default.
     *
     * <p>An {@code field} or {@code exposedField} line has to give one (rules 6 and 16), so the file
     * does say what the node holds when nothing is written to it, and a hover or a popup that
     * answered "nothing" would be reading the interface as though it were the table. Whitespace is
     * folded because the value is quoted from the source, where it may sit across several lines.
     */
    private static String writtenDefault(ParseResult parse, SymbolTable.Interface entry) {
        CstNode value = entry.decl().firstChildOf(CstKind.VALUE);
        if (value == null || value.start() < 0) {
            return "";
        }
        return parse.text(value).strip().replaceAll("\\s+", " ");
    }

    private static void add(List<Member> members, Set<String> seen, Member member) {
        // First wins: the body's declaration of a name the table also has is the one the file means.
        if (seen.add(member.name())) {
            members.add(member);
        }
    }

    /** The member the file named, whether it used the declared name or one of its aliases. */
    public static Optional<Member> find(List<Member> members, String written) {
        return members.stream().filter(member -> member.answersTo(written)).findFirst();
    }

    /** What a {@code ROUTE} may read from the node: its eventOuts, and what an exposedField sends. */
    public static List<Member> readable(List<Member> members) {
        return ends(members, true);
    }

    /** What a {@code ROUTE} may write to: its eventIns, and what an exposedField receives. */
    public static List<Member> writable(List<Member> members) {
        return ends(members, false);
    }

    /**
     * The two ends of a node, named the way a {@code ROUTE} names them.
     *
     * <p>An {@code exposedField} is routed through events rather than by its own name - {@code
     * set_translation} in, {@code translation_changed} out - and Xj3D generates those instead of
     * declaring them, so no source lists them for the table to carry. The declared events come
     * first and keep the names they were declared with, which is why {@code TimeSensor}'s {@code
     * fraction_changed} is offered once rather than as a derivation of a {@code fraction} that does
     * not exist.
     */
    private static List<Member> ends(List<Member> members, boolean source) {
        List<Member> ends = new ArrayList<>(members.size());
        Set<String> written = new HashSet<>();
        for (Member member : members) {
            boolean carries = source ? member.readable() : member.writable();
            if (carries && written.add(member.name())) {
                ends.add(member);
            }
        }
        for (Member member : members) {
            if (!member.access().equals("exposedField")) {
                continue;
            }
            String event = routedAs(member, source);
            if (written.add(event)) {
                ends.add(new Member(event, member.type(), source ? "eventOut" : "eventIn", "",
                        member.doc(), member.childNodes(), List.of()));
            }
        }
        return ends;
    }

    /**
     * How a {@code ROUTE} names the event an {@code exposedField} is driven through.
     *
     * <p>Public because a hover says this back to the user: the naming rule belongs to this class
     * and cannot be spelled a second way where it is only display text.
     */
    public static String routedAs(Member exposed, boolean source) {
        return source ? exposed.name() + "_changed" : "set_" + exposed.name();
    }

    /**
     * The field a routed event name was made from: {@code set_translation} and {@code
     * translation_changed} both come off {@code translation}.
     *
     * <p>Public because a jump to a definition has to undo this naming to find the line the event
     * belongs to, and only the {@code _changed} of an exposedField is a derived name - the caller
     * still has to ask whether the name it uncovers really is an exposedField, since a declared
     * {@code eventOut fraction_changed} of its own is not.
     *
     * @return the name it was made from, or empty when this one was not made from another
     */
    public static Optional<String> madeFrom(String written) {
        if (written.startsWith("set_") && written.length() > 4) {
            return Optional.of(written.substring(4));
        }
        if (written.endsWith("_changed") && written.length() > "_changed".length()) {
            return Optional.of(written.substring(0, written.length() - "_changed".length()));
        }
        return Optional.empty();
    }

    /**
     * The names a node body has already been given a value for.
     *
     * <p>Read from the node's own body, not from anything nested in it, because {@code Shape}'s
     * {@code geometry} being set says nothing about {@code appearance} - but a second
     * {@code geometry} in the same braces is one the browser silently takes last of.
     */
    public static List<String> written(ParseResult parse, CstNode node) {
        CstNode body = node == null ? null : node.firstChildOf(CstKind.NODE_BODY);
        if (body == null && node != null) {
            body = node.firstChildOf(CstKind.SCRIPT_BODY);
        }
        if (body == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (CstNode element : body.children()) {
            if (element.kind() != CstKind.FIELD_ASSIGNMENT && element.kind() != CstKind.SCRIPT_ELEMENT
                    && element.kind() != CstKind.INTERFACE_DECL) {
                continue;
            }
            CstNode name = element.firstChildOf(CstKind.FIELD_NAME);
            if (name != null && name.kind() != CstKind.MISSING) {
                names.add(parse.text(name));
            }
        }
        return names;
    }
}
