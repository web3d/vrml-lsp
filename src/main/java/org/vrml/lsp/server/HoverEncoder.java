package org.vrml.lsp.server;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.vrml.lsp.services.Hovers;

/**
 * Turns one hover's text into the protocol's object.
 *
 * <p>The content is always offered as markdown, whatever the client said it can render, because the
 * text genuinely is markdown and {@code MarkupContent.kind} is the field that tells a reader how to
 * treat it. Keeping a second, plain rendering of every sentence would only mean two wordings that can
 * drift apart - and a client that cannot render emphasis shows the asterisks, which is a cosmetic
 * failure of the kind the field exists to describe.
 */
final class HoverEncoder {

    private HoverEncoder() {
    }

    /** The tooltip for the token under the cursor, or null when there is nothing to say about it. */
    static Hover encode(Hovers.Hovered hovered, VrmlDocument doc) {
        if (hovered == null) {
            return null;
        }
        return new Hover(new MarkupContent("markdown", hovered.markdown()),
                doc.rangeOf(hovered.start(), hovered.end()));
    }
}
