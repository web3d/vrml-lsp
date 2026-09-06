package org.vrml.lsp.diagnostics;

/**
 * Issue severities, named after what the LSP wire format expects.
 */
public enum Severity {
    ERROR,
    WARNING,
    INFORMATION,
    HINT;

    /** Map to LSP4J's enum without making the analysis layer depend on the protocol. */
    public org.eclipse.lsp4j.DiagnosticSeverity toLsp() {
        return switch (this) {
            case ERROR -> org.eclipse.lsp4j.DiagnosticSeverity.Error;
            case WARNING -> org.eclipse.lsp4j.DiagnosticSeverity.Warning;
            case INFORMATION -> org.eclipse.lsp4j.DiagnosticSeverity.Information;
            case HINT -> org.eclipse.lsp4j.DiagnosticSeverity.Hint;
        };
    }
}
