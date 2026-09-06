package org.vrml.spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The fields each VRML97 node actually has, read out of Xj3D's own node classes.
 *
 * <p>Xj3D describes a node's interface as static data in {@code Base<NodeName>.java}: an array of
 * {@code VRMLFieldDeclaration} entries built in a static block. That array is what the parser
 * consults when a scene file names a field, so it is the closest thing to ground truth for "which
 * field names are legal here" - closer than the X3D-oriented UOM, which renames things VRML97
 * spelled differently.
 *
 * <p>Declarations are matched by their constructor call rather than by the array slot they fill.
 * The slot would identify a constant such as {@code FIELD_COORDINDEX}, but the constant is not the
 * field: the strings inside the constructor are, and the file already says which node owns them.
 * Matching on the constructor also keeps working where Xj3D deviates from the array form -
 * {@code BaseScript} assigns to a local and calls {@code appendField(field)} instead, because a
 * Script's interface grows at runtime.
 *
 * <p>{@code fieldDecl} is not the whole set of names a {@code .wrl} file can use. Each class also
 * fills a {@code fieldMap} of names to slot indexes, and that map holds the names X3D renamed away:
 * {@code LOD { level [...] }} and {@code Switch { choice [...] }} are valid VRML97 and appear
 * nowhere in a declaration, only in {@code fieldMap}. Those entries are read too, as aliases of the
 * field they point at; the {@code set_} and {@code _changed} forms in the same map are left out
 * because they follow from a field's access rather than adding information.
 */
final class Xj3dFieldReader {

    /**
     * One entry of a node's {@code fieldDecl} array, with the extra names that field answers to.
     *
     * <p>The list is mutable while a file is being read and copied at the end of {@link #parse}, so
     * nothing outside this class ever sees a half-filled one.
     */
    record Decl(String access, String type, String name, List<String> aliases) {
    }

    /**
     * A node class as read.
     *
     * @param decls           the declared fields, in source order
     * @param unexplained     names {@code fieldMap} offers that no declaration could be found for;
     *                        reported rather than dropped, since it means this reader met a form it
     *                        does not understand
     */
    record Fields(List<Decl> decls, List<String> unexplained) {
    }

    /**
     * Every call of the constructor, with its argument text and - where the file uses the array form
     * - the slot constant it assigns to, which is what ties a {@code fieldMap} alias to a field.
     * Each argument list is then taken apart by hand, so a shape this reader has not seen is
     * reported by name instead of being skipped, which is what {@code BaseBackground} turned out to
     * be on the first run of this generator.
     */
    private static final Pattern DECLARATION = Pattern.compile(
            "(?:fieldDecl\\[(\\w+)\\]\\s*=\\s*)?new\\s+VRMLFieldDeclaration\\(([^)]*)\\)",
            Pattern.DOTALL);

    /**
     * Three literals in the order Xj3D always writes them: access constant, type, field name. The
     * name allows underscores - {@code set_bind}, {@code add_children} and {@code remove_children}
     * are events, and an event whose name did not parse is exactly the silent field loss this
     * class exists to prevent.
     */
    private static final Pattern ARGUMENTS = Pattern.compile(
            "\\s*FieldConstants\\.(FIELD|EXPOSEDFIELD|EVENTIN|EVENTOUT)\\s*,"
                    + "\\s*\"([A-Za-z][A-Za-z0-9]*)\"\\s*,\\s*\"([A-Za-z][A-Za-z0-9_]*)\"\\s*");

    /** Counts constructor calls, so an argument containing ')' cannot pass uncounted. */
    private static final Pattern ANY_DECLARATION = Pattern.compile("new\\s+VRMLFieldDeclaration\\(");

    /**
     * {@code fieldMap.put("level", idx)} and {@code fieldMap.put("center", FIELD_CENTER)} - the two
     * ways Xj3D maps a name to a slot. The index constants are all called {@code FIELD_*}, which is
     * what tells the two apart without a table of which local variable names are in use.
     */
    private static final Pattern FIELD_NAME_ENTRY = Pattern.compile(
            "fieldMap\\s*\\.\\s*put\\(\\s*\"([^\"]+)\"\\s*,\\s*([A-Za-z_]\\w*)\\s*\\)");

    private static final String ACCESS_PREFIX = "Base";

    private Xj3dFieldReader() {
    }

    /**
     * Finds the class that declares {@code nodeName} and returns its fields in declaration order.
     *
     * @throws SpecGenException if the class is missing, appears in more than one package, or holds
     *         a declaration this reader could not parse
     */
    static Fields read(Path nodesDir, String nodeName) throws SpecGenException {
        List<Path> candidates = findCandidates(nodesDir, nodeName);
        if (candidates.isEmpty()) {
            throw new SpecGenException(nodesDir + ": no " + ACCESS_PREFIX + nodeName
                    + ".java declares node " + nodeName + ", so its field list cannot be read");
        }
        if (candidates.size() > 1) {
            throw new SpecGenException(nodesDir + ": " + candidates.size() + " files are called "
                    + ACCESS_PREFIX + nodeName + ".java (" + candidates.stream().map(
                    nodesDir::relativize).sorted().toList()
                    + "). Two packages claiming the same VRML97 node name is a question for a"
                    + " person, not a tie-breaker for this generator");
        }
        return parse(candidates.get(0), nodeName);
    }

    /**
     * Every {@code Base<NodeName>.java} below the nodes tree. The name is matched as a whole file
     * name rather than as a suffix, which is what keeps {@code BaseText} from also pulling in
     * {@code BaseText2D}: an X3D node that is not in the VRML97 profile at all.
     */
    private static List<Path> findCandidates(Path nodesDir, String nodeName)
            throws SpecGenException {
        String wanted = ACCESS_PREFIX + nodeName + ".java";
        try (Stream<Path> walk = Files.walk(nodesDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> wanted.equals(path.getFileName().toString()))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            throw new SpecGenException("cannot search " + nodesDir + ": " + e.getMessage(), e);
        }
    }

    private static Fields parse(Path file, String nodeName) throws SpecGenException {
        // Decoded as ISO-8859-1 on purpose: some of these files carry accented names in their
        // javadoc, the byte sequence is not always valid UTF-8, and everything this reader looks
        // at is ASCII.
        String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new SpecGenException("cannot read " + file + ": " + e.getMessage(), e);
        }

        List<Decl> decls = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        Map<String, Integer> slotByConstant = new HashMap<>();
        Matcher declarations = DECLARATION.matcher(text);
        while (declarations.find()) {
            Matcher arguments = ARGUMENTS.matcher(declarations.group(2));
            if (arguments.matches()) {
                decls.add(new Decl(accessOf(arguments.group(1)), arguments.group(2),
                        arguments.group(3), new ArrayList<>()));
                if (declarations.group(1) != null) {
                    slotByConstant.put(declarations.group(1), decls.size() - 1);
                }
            } else {
                unreadable.add(flatten(declarations.group(2)));
            }
        }
        int total = 0;
        Matcher any = ANY_DECLARATION.matcher(text);
        while (any.find()) {
            total++;
        }
        if (!unreadable.isEmpty() || total != decls.size()) {
            throw new SpecGenException(file + " (node " + nodeName + "): " + total
                    + " VRMLFieldDeclaration constructors, " + decls.size()
                    + " readable, unparseable arguments " + unreadable
                    + ". The extraction pattern is out of date and the spec table would silently"
                    + " lose fields");
        }
        if (decls.isEmpty()) {
            throw new SpecGenException(file + ": node " + nodeName
                    + " declares no fields at all, which cannot be right");
        }
        List<String> unexplained = attachAliases(text, decls, slotByConstant);
        return new Fields(decls.stream()
                .map(decl -> new Decl(decl.access(), decl.type(), decl.name(),
                        List.copyOf(decl.aliases())))
                .toList(), List.copyOf(unexplained));
    }

    /**
     * Every {@code fieldMap} name that is not itself a declared field, hung on the field it points
     * at.
     *
     * <p>The names this leaves out are the interesting ones: VRML97 called {@code LOD.children}
     * {@code level} and {@code Switch.children} {@code choice}, X3D renamed both, and Xj3D keeps the
     * old spellings alive only in this map.
     */
    private static List<String> attachAliases(String text, List<Decl> decls,
                                              Map<String, Integer> slotByConstant) {
        Set<String> declared = decls.stream().map(Decl::name).collect(Collectors.toSet());
        List<String> unexplained = new ArrayList<>();
        Matcher entries = FIELD_NAME_ENTRY.matcher(text);
        while (entries.find()) {
            String name = entries.group(1);
            if (declared.contains(name) || name.startsWith("set_") || name.endsWith("_changed")) {
                continue;
            }
            Integer slot = slotByConstant.get(slotConstant(text, entries.group(2), entries.start()));
            if (slot == null) {
                unexplained.add(name);
                continue;
            }
            List<String> aliases = decls.get(slot).aliases();
            if (!aliases.contains(name)) {
                aliases.add(name);
            }
        }
        return unexplained;
    }

    /**
     * The index constant a {@code fieldMap} entry names, either directly or through the local
     * variable it was written as - in which case it is the closest assignment above the entry, which
     * is how the static block reads.
     */
    private static String slotConstant(String text, String target, int entryStart) {
        if (target.startsWith("FIELD_")) {
            return target;
        }
        Matcher assignments = Pattern.compile(assignmentOf(target)).matcher(text);
        String constant = null;
        while (assignments.find() && assignments.start() < entryStart) {
            constant = assignments.group(1);
        }
        return constant;
    }

    /** Where a name is bound to a slot: {@code Integer idx = FIELD_CHILDREN;} and its reassignments. */
    private static String assignmentOf(String variable) {
        return "\\b" + Pattern.quote(variable) + "\\s*=\\s*(FIELD_\\w+)\\s*;";
    }

    /** An argument list as one line, for a message that has to fit on one line. */
    private static String flatten(String arguments) {
        return arguments.replaceAll("\\s+", " ").trim();
    }

    /** FieldConstants' spelling of the access mode, in the words a scene file uses. */
    private static String accessOf(String constant) {
        return switch (constant) {
            case "FIELD" -> "field";
            case "EXPOSEDFIELD" -> "exposedField";
            case "EVENTIN" -> "eventIn";
            case "EVENTOUT" -> "eventOut";
            default -> throw new IllegalStateException(constant);
        };
    }
}
