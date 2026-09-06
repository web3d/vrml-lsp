package org.vrml.lsp.diagnostics;

/**
 * Diagnostic codes.
 *
 * <p>Ranges are stable API: {@code 1xxx} structural (parser could not place tokens),
 * {@code 2xxx} semantic (well-formed but wrong for VRML97), {@code 3xxx} suggestions.
 * Clients key quick fixes off these numbers, so codes are never reused or renumbered.
 */
public final class Codes {

    private Codes() {
    }

    // ---- 1xxx structural -------------------------------------------------------------
    public static final int UNEXPECTED_TOKEN = 1001;
    public static final int MISSING_LBRACE = 1002;
    public static final int MISSING_RBRACE = 1003;
    public static final int MISSING_LBRACKET = 1004;
    public static final int MISSING_RBRACKET = 1005;
    public static final int MISSING_DOT = 1006;
    public static final int MISSING_KEYWORD = 1007;
    public static final int MISSING_NAME = 1008;
    public static final int MISSING_VALUE = 1009;
    public static final int UNTERMINATED_STRING = 1010;
    public static final int UNEXPECTED_CHAR = 1011;
    public static final int UNCLOSED_NODE = 1012;
    public static final int STATEMENT_UNFINISHED = 1013;
    public static final int DECL_OUTSIDE_PROTO = 1014;
    public static final int EMPTY_VALUE = 1015;
    /** No {@code #VRML V2.0 utf8} header where one has to be. */
    public static final int MISSING_HEADER = 1016;
    /** A header-shaped comment exists but is not the header the standard asks for. */
    public static final int MALFORMED_HEADER = 1017;

    // ---- 2xxx semantic ---------------------------------------------------------------
    //
    // Severity is not uniform here, and it follows Xj3D rather than taste. Its
    // VRML97Reader.convertException() sends InvalidFieldName and InvalidFieldValue to
    // errorHandler.warningReport() and keeps loading the world, so a value the browser can guess
    // its way past is a WARNING here too; what is an ERROR is a name no reader can resolve, or a
    // statement that cannot mean what it says.
    public static final int UNKNOWN_NODE_TYPE = 2001;
    public static final int UNKNOWN_FIELD = 2002;
    public static final int FIELD_VALUE_COUNT = 2003;
    public static final int FIELD_VALUE_FORMAT = 2004;
    // 2005 is deliberately unused: a duplicated DEF name is not a mistake in VRML97. X3D requires
    // DEF names to be unique, VRML97 does not, and error_handling/double_def.wrl says so in its own
    // comment while testing that a reader ignores the redefinition. Both that file and the repeated
    // `DEF TX` in bad_field_name2.wrl are corpus cases that must stay silent, so the later
    // definition simply shadows the earlier one for the uses after it (see SymbolTable).
    public static final int UNDEFINED_USE = 2006;
    public static final int ROUTE_UNKNOWN_NODE = 2007;
    public static final int ROUTE_UNKNOWN_FIELD = 2008;
    public static final int ROUTE_DIRECTION = 2009;
    /** Emitted by the parser (Rule 5), where the empty body is visible without a table. */
    public static final int PROTO_BODY_EMPTY = 2010;
    public static final int FIELD_RANGE = 2011;
    public static final int UNKNOWN_FIELD_TYPE = 2012;
    public static final int DUPLICATE_PROTO_FIELD = 2013;
    public static final int IS_UNKNOWN_FIELD = 2014;
    /** The name is defined, but only below this {@code USE}; VRML97 reads a file front to back. */
    public static final int DEF_AFTER_USE = 2015;
    public static final int PROTO_NAME_CONFLICT = 2017;
    public static final int NODE_NOT_DISPLAYABLE = 2018;
    /** A value written for an {@code eventIn} or {@code eventOut}, which a node body cannot set. */
    public static final int FIELD_NOT_ASSIGNABLE = 2019;
    /** A node in a field that accepts other kinds, like geometry where only an appearance fits. */
    public static final int NODE_KIND_NOT_ACCEPTED = 2020;
    /** {@code IS} outside a PROTO body, where there is no interface for it to bind to. */
    public static final int IS_OUTSIDE_PROTO = 2021;

    // ---- 3xxx suggestions ------------------------------------------------------------
    /** A name nobody knows that is one edit away from one somebody does. */
    public static final int NAME_SUGGESTION = 3001;
    // 3004 is deliberately unused: it was "PROTO declared and never instantiated", and on the corpus
    // it was wrong more often than right. A third of the files that define a PROTO are libraries -
    // exporter/, external/ and vrml97/proto*.wrl declare a prototype for someone else's file to
    // instantiate, because VRML97's only import mechanism is EXTERNPROTO naming a URL. Telling an
    // author a definition is unused needs the whole workspace, which is out of scope here.
    /** The document declares X3D rather than VRML97, which this server does not parse. */
    public static final int X3D_DIALECT = 3005;
}
