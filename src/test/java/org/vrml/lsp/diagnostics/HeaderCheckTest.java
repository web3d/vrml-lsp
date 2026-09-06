package org.vrml.lsp.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.vrml.lsp.lexer.VrmlLexer;
import org.vrml.lsp.text.LineIndex;

/**
 * The header rules, i.e. everything {@code vrml97/bad_header*.wrl} is about.
 *
 * <p>The corpus pins the five broken files and the 256 good ones; this pins the judgements the
 * corpus cannot: what counts as forgiven formatting, what counts as a different dialect, and when
 * saying nothing is right.
 */
class HeaderCheckTest {

    private static List<Issue> check(String text) {
        return HeaderCheck.check(text, VrmlLexer.tokenize(text));
    }

    /** "VRL1017@2:4" style, so a failure message reads like the CLI's. */
    private static String render(String text, List<Issue> issues) {
        LineIndex index = new LineIndex(text);
        StringBuilder sb = new StringBuilder();
        for (Issue issue : issues) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(issue.codeLabel()).append('@').append(index.describe(issue.start()))
                    .append('-').append(index.describe(issue.end()));
        }
        return sb.toString();
    }

    @Test
    void nothingIsReportedForTheHeaderTheStandardAsksFor() {
        for (String header : List.of("#VRML V2.0 utf8", "#VRML  V2.0   utf8",
                "#VRML V2.0 utf8 CosmoWorlds V1.0", "\uFEFF#VRML V2.0 utf8")) {
            String text = header + "\nBox {\n}\n";
            assertEquals(List.of(), check(text), render(text, check(text)));
        }
    }

    @Test
    void noHeaderAtAllIsAMissingHeaderNotAMalformedOne() {
        String text = "Box {\n}\n";
        assertEquals("VRL1016@1:1-1:4", render(text, check(text)));
    }

    @Test
    void aDocumentWithNothingInItIsNotYetBroken() {
        // A fresh buffer, a deleted line, a file saved empty: complaining about a header nobody
        // has typed yet trains users to ignore the first diagnostic they see.
        assertTrue(check("").isEmpty());
        assertTrue(check("\n\n   \n").isEmpty());
    }

    @Test
    void eachOfTheFiveCorpusMistakesKeepsItsOwnMessage() {
        // Space after '#', dropped 'V', header on line 2, dropped encoding, glued-together fields.
        // Each span covers exactly the line the header should have been.
        assertEquals("VRL1017@1:1-1:17", render("# VRML V2.0 utf8\n", check("# VRML V2.0 utf8\n")));
        assertEquals("VRL1017@1:1-1:15", render("#VRML 2.0 utf8\n", check("#VRML 2.0 utf8\n")));
        assertEquals("VRL1017@1:1-1:2",
                render("#\n#VRML V2.0 utf8\n", check("#\n#VRML V2.0 utf8\n")));
        assertEquals("VRL1017@1:1-1:11", render("#VRML V2.0\n", check("#VRML V2.0\n")));
        assertEquals("VRL1017@1:1-1:14",
                render("#VRMLV2.0utf8\n", check("#VRMLV2.0utf8\n")));
    }

    @Test
    void onlyTheFirstLineCanCarryIt() {
        // A blank line before the header is the mistake exporters make most often, and it is the
        // one a browser refuses.
        String text = "\n#VRML V2.0 utf8\nBox {}\n";
        List<Issue> issues = check(text);
        assertEquals(1, issues.size(), render(text, issues));
        assertEquals(Codes.MISSING_HEADER, issues.get(0).code(),
                "line 1 holds nothing, so the header is not merely malformed, it is absent");
        assertEquals(0, issues.get(0).start());
    }

    @Test
    void anotherVersionIsNotThisGrammar() {
        // The parser implements VRML97 = "V2.0". Saying "V1.0 looks fine" would be a lie that
        // only becomes visible as unrelated errors on every VrmlGeometry node.
        assertEquals("VRL1017@1:1-1:16",
                render("#VRML V1.0 utf8\n", check("#VRML V1.0 utf8\n")));
        assertEquals("VRL1017@1:1-1:16",
                render("#VRML V2.1 utf8\n", check("#VRML V2.1 utf8\n")));
        // The standard spells the encoding lower case and the corpus agrees on all 256 files.
        assertEquals("VRL1017@1:1-1:16",
                render("#VRML V2.0 UTF8\n", check("#VRML V2.0 UTF8\n")));
    }

    @Test
    void anX3DFileIsExplainedOnceAndNotAlsoCalledMissing() {
        String text = "#X3D V3.0 utf8\nPROFILE Immersive\n";
        List<Issue> issues = check(text);
        assertEquals(1, issues.size(), render(text, issues));
        assertEquals(Codes.X3D_DIALECT, issues.get(0).code());
        assertEquals(Severity.INFORMATION, issues.get(0).severity(),
                "the file is not broken, it is a language this server does not speak");
    }

    @Test
    void aLaterX3DCommentIsProseNotADeclaration() {
        String text = "#VRML V2.0 utf8\n#X3D is the successor, for reference\nBox {}\n";
        assertEquals(List.of(), check(text));
    }

    @Test
    void aHeaderInCrlfIsStillAHeader() {
        assertEquals(List.of(), check("#VRML V2.0 utf8\r\nBox {\r\n}\r\n"));
        // ... and a broken one still spans only its own line, not the file.
        assertEquals("VRL1017@1:1-1:11",
                render("#VRML V2.0\r\nBox {}\r\n", check("#VRML V2.0\r\nBox {}\r\n")));
    }
}
