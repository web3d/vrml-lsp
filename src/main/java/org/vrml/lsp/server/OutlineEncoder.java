package org.vrml.lsp.server;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.vrml.lsp.services.Symbols;

/**
 * Turns the outline into the protocol's symbols.
 *
 * <p>Two shapes are answered from the same tree. A client that said it can nest gets
 * {@code DocumentSymbol}, which is the shape the scene actually has; one that did not gets the flat
 * {@code SymbolInformation} list with each line's parent named in {@code containerName}, because
 * handing a nested list to a client that cannot read it loses every node but the top ones.
 */
final class OutlineEncoder {

    private OutlineEncoder() {
    }

    /**
     * @param hierarchical whether the client asked for nested symbols, read off its capabilities at
     *        {@code initialize}
     */
    static List<Either<SymbolInformation, DocumentSymbol>> encode(List<Symbols.Symbol> roots,
                                                                  VrmlDocument doc,
                                                                  boolean hierarchical) {
        List<Either<SymbolInformation, DocumentSymbol>> out = new ArrayList<>();
        if (hierarchical) {
            for (DocumentSymbol symbol : nested(roots, doc)) {
                out.add(Either.forRight(symbol));
            }
            return out;
        }
        for (Symbols.Symbol root : roots) {
            flatten(root, "", doc, out);
        }
        return out;
    }

    private static List<DocumentSymbol> nested(List<Symbols.Symbol> symbols, VrmlDocument doc) {
        List<DocumentSymbol> out = new ArrayList<>(symbols.size());
        for (Symbols.Symbol symbol : symbols) {
            Range whole = doc.rangeOf(symbol.start(), symbol.end());
            Range name = doc.rangeOf(symbol.nameStart(), symbol.nameEnd());
            out.add(new DocumentSymbol(symbol.name(), kind(symbol.kind()), whole, name,
                    symbol.detail(), nested(symbol.children(), doc)));
        }
        return out;
    }

    private static void flatten(Symbols.Symbol symbol, String container, VrmlDocument doc,
                               List<Either<SymbolInformation, DocumentSymbol>> into) {
        Location where = new Location(doc.uri(), doc.rangeOf(symbol.nameStart(), symbol.nameEnd()));
        into.add(Either.forLeft(new SymbolInformation(symbol.name(), kind(symbol.kind()), where,
                container)));
        String parent = symbol.detail().isEmpty() ? symbol.name()
                : symbol.name() + ": " + symbol.detail();
        for (Symbols.Symbol child : symbol.children()) {
            flatten(child, parent, doc, into);
        }
    }

    /**
     * A node instance is an object of a type the language or the file declares, which is why the two
     * get different icons: {@code PROTO Lamp} is a declaration of a type and the {@code Lamp} written
     * in a scene is one node of it.
     */
    private static SymbolKind kind(Symbols.Kind shape) {
        return switch (shape) {
            case NODE -> SymbolKind.Object;
            case FIELD -> SymbolKind.Field;
            case PROTO, EXTERN_PROTO -> SymbolKind.Class;
            case INTERFACE -> SymbolKind.Interface;
            case ROUTE -> SymbolKind.Event;
        };
    }
}
