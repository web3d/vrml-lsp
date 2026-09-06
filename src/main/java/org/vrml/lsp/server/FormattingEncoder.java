package org.vrml.lsp.server;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.TextEdit;
import org.vrml.lsp.services.Formatter;

/**
 * Turns a reflow into the protocol's {@code TextEdit}s.
 *
 * <p>The service layer works in character offsets because that is what the lexer produces and what
 * survives an edit being applied to a document; only here do they become line/character ranges, so
 * the offsets of one snapshot are translated by the line index of that same snapshot. Ranges are
 * disjoint by construction - each one covers the trivia between two significant tokens - which is
 * what lets a client apply the list in any order without rebasing.
 */
final class FormattingEncoder {

    private FormattingEncoder() {
    }

    static List<? extends TextEdit> encode(List<Formatter.Edit> edits, VrmlDocument doc) {
        List<TextEdit> out = new ArrayList<>(edits.size());
        for (Formatter.Edit edit : edits) {
            out.add(new TextEdit(doc.rangeOf(edit.start(), edit.end()), edit.text()));
        }
        return out;
    }
}
