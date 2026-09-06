package org.vrml.lsp.server;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.Location;
import org.vrml.lsp.services.Definitions;

/**
 * Turns a jump into the protocol's {@code Location}.
 *
 * <p>Plain locations rather than {@code LocationLink}s: every target is a range of the document the
 * request came about, so the origin range a link carries would repeat what the client already knows,
 * and a plain list is the one shape every client reads.
 */
final class LocationEncoder {

    private LocationEncoder() {
    }

    static List<? extends Location> encode(List<Definitions.Target> targets, VrmlDocument doc) {
        List<Location> out = new ArrayList<>(targets.size());
        for (Definitions.Target target : targets) {
            out.add(new Location(doc.uri(), doc.rangeOf(target.start(), target.end())));
        }
        return out;
    }
}
