package org.vrml.lsp.cst;

/**
 * Syntax node kinds of the VRML CST.
 *
 * <p>Composite kinds mirror the productions of {@code VRML97RelaxedParser.jj}; leaf kinds
 * wrap a single token. {@link #MISSING} is how the parser records "this token should be
 * here and is not" - it has an empty span, so printing a CST still reproduces the file
 * exactly, while every consumer can see the hole instead of silently mis-reading the tree.
 */
public enum CstKind {

    SCENE,
    STATEMENT,
    NODE_STATEMENT,
    DEF_CLAUSE,
    USE_CLAUSE,
    NODE,
    NODE_BODY,
    FIELD_ASSIGNMENT,
    IS_CLAUSE,

    VALUE,
    SF_VALUE,
    MF_VALUE,
    LITERAL_VALUE,
    NUMBER_ARRAY,
    STRING_ARRAY,
    NODE_LIST,

    PROTO_DECL,
    PROTO_BODY,
    PROTO_NAME,
    INTERFACE_DECL,
    EXTERN_PROTO_DECL,
    URI_LIST,

    ROUTE_DECL,
    SCRIPT_BODY,
    SCRIPT_ELEMENT,
    ACCESS_TYPE,
    FIELD_TYPE,
    FIELD_NAME,
    NODE_NAME,
    NODE_TYPE,

    IDENTIFIER,
    KEYWORD,
    PUNCT,
    NUMBER,
    STRING,

    /** Tokens the parser could not place, kept so their text is never lost. */
    ERROR,
    /** Zero-width placeholder for an expected token that was absent. */
    MISSING,
}
