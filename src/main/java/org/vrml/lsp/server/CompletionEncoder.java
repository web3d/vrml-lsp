package org.vrml.lsp.server;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.vrml.lsp.services.Completions;
import org.vrml.lsp.services.Context;

/**
 * Turns completion candidates into the protocol's own objects.
 *
 * <p>The only place in the server that knows what a {@code CompletionItem} looks like, which is why
 * {@link Completions} returns its own candidate type: the ranking, the wording and the table lookups
 * are all testable without a protocol, and only this file has to be believed about JSON.
 */
final class CompletionEncoder {

    /**
     * How many files to offer a URI. A directory of a texture library has hundreds, and a list that
     * long is not a list; a relative path typed by hand still works whatever this says.
     */
    private static final int MAX_URIS = 200;

    private CompletionEncoder() {
    }

    /**
     * Protocol items from candidates, in the order they were found.
     *
     * <p>The order is the ranking, and {@code sortText} is how it survives the client: a four-digit
     * counter says "in the order you were given" whatever a client then does with prefixes and case.
     *
     * @param ctx where the text lands, which every item shares: one slot, one replaced range
     */
    static List<CompletionItem> encode(List<Completions.Candidate> candidates, Context ctx,
                                       VrmlDocument doc) {
        List<CompletionItem> items = new ArrayList<>(candidates.size());
        for (int at = 0; at < candidates.size(); at++) {
            Completions.Candidate candidate = candidates.get(at);
            CompletionItem item = new CompletionItem(candidate.label());
            item.setKind(kind(candidate.kind()));
            item.setDetail(candidate.detail());
            if (!candidate.documentation().isEmpty()) {
                item.setDocumentation(new MarkupContent("markdown", candidate.documentation()));
            }
            item.setSortText(String.format("%04d", at));
            // Left, and never an InsertReplaceEdit: the client was not asked whether it accepts one,
            // and the replaced range here is the word under the cursor, which is the same thing for
            // both kinds of edit.
            item.setTextEdit(Either.forLeft(new TextEdit(
                    doc.rangeOf(ctx.replaceStart(), ctx.replaceEnd()), candidate.insertText())));
            item.setInsertTextFormat(candidate.snippet() ? InsertTextFormat.Snippet
                    : InsertTextFormat.PlainText);
            items.add(item);
        }
        return items;
    }

    private static CompletionItemKind kind(Completions.Kind shape) {
        return switch (shape) {
            case NODE -> CompletionItemKind.Class;
            case FIELD -> CompletionItemKind.Field;
            case EVENT -> CompletionItemKind.Event;
            case VALUE -> CompletionItemKind.Value;
            case KEYWORD -> CompletionItemKind.Keyword;
            case TYPE -> CompletionItemKind.TypeParameter;
            case NAME -> CompletionItemKind.Variable;
            case FILE -> CompletionItemKind.File;
        };
    }

    /**
     * The scene files sitting next to the document, for an {@code EXTERNPROTO}'s URI list.
     *
     * <p>Read from the file system because that is the only place the answer is: a {@code PROTO} is
     * imported by path, and the paths a VRML97 file uses are relative to the file. Nothing else here
     * touches the disk, so this is the one call that can fail on permissions - which is a list of
     * nothing, not an error a user can act on.
     */
    static List<String> siblingScenes(String docUri) {
        Path dir = parentOf(docUri);
        if (dir == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(CompletionEncoder::isScene)
                    .map(child -> child.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .limit(MAX_URIS)
                    .forEach(names::add);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return names;
    }

    private static boolean isScene(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path) && (name.endsWith(".wrl") || name.endsWith(".wrl.gz"));
    }

    /** The directory a document URI names, or null if it is not a file the server can list. */
    private static Path parentOf(String docUri) {
        if (!docUri.startsWith("file:")) {
            return null;
        }
        try {
            Path file = Paths.get(new URI(docUri));
            Path dir = file.getParent();
            return dir != null && Files.isDirectory(dir) ? dir : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
