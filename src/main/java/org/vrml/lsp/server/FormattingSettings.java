package org.vrml.lsp.server;

import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.FormattingOptions;
import org.vrml.lsp.Log;
import org.vrml.lsp.services.Formatter;

/**
 * The formatting choices, as far as anyone has told the server about them.
 *
 * <p>There are two sources and they are not equal. {@code workspace/didChangeConfiguration} carries
 * whatever the user set under {@code vrml.}, which is where the line width lives because no protocol
 * object has a field for it; every formatting request also carries the client's own idea of the tab
 * size of the document being formatted. The request wins, because it describes the editor the user is
 * typing in now while the setting is the one they configured last month.
 *
 * <p>Indent is always spaces. A client that asked for tab characters still gets them: the formatter
 * aligns by counting columns, and a tab is two columns wide until somebody changes the editor setting
 * that says otherwise, so a request that says "indent by four" cannot be answered honestly with a
 * backslash-t.
 */
final class FormattingSettings {

    /** The section this server's settings live under, as clients name it. */
    private static final String SECTION = "vrml";
    private static final String INDENT = "indent";
    private static final String MAX_COLUMN = "maxColumn";

    /** What the user configured, or -1 when they never said; read per request, written on a notification. */
    private volatile int indent = -1;
    private volatile int maxColumn = -1;

    /**
     * The layout to format with.
     *
     * @param requested the client's own formatting options for this document, which may be absent
     */
    Formatter.Options forRequest(FormattingOptions requested) {
        int step = indent > 0 ? indent : Formatter.Options.DEFAULTS.indent();
        int width = maxColumn > 0 ? maxColumn : Formatter.Options.DEFAULTS.maxColumn();
        if (requested != null) {
            Integer tabSize = requested.getTabSize();
            if (tabSize != null && tabSize > 0) {
                step = tabSize;
            }
        }
        return Formatter.Options.clamp(new Formatter.Options(step, width));
    }

    /**
     * Remembers the {@code vrml} settings in a configuration tree, ignoring every other section.
     *
     * <p>Clients send wildly different shapes: the whole tree, the section alone, or keys flattened
     * with a dot. All three are read, and a value that is not a number is left alone rather than
     * guessed at - a typo in a settings file should not silently reformat a project at column 20.
     */
    void apply(Object settings) {
        Integer step = number(settings, INDENT);
        Integer width = number(settings, MAX_COLUMN);
        if (step != null) {
            indent = step;
        }
        if (width != null) {
            maxColumn = width;
        }
        Log.info("formatting settings: indent=" + (step == null ? "unchanged" : step)
                + ", maxColumn=" + (width == null ? "unchanged" : width)
                + "; now " + forRequest(null));
    }

    /** Looks for {@code key} in the {@code vrml} section, then flattened, then at the top. */
    private static Integer number(Object settings, String key) {
        Object section = member(settings, SECTION);
        Integer found = numberIn(section, key);
        if (found == null) {
            found = numberIn(settings, SECTION + "." + key);
        }
        if (found == null) {
            found = numberIn(settings, key);
        }
        return found;
    }

    private static Integer numberIn(Object node, String key) {
        Object value = member(node, key);
        if (value instanceof JsonElement element) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            try {
                return (int) Double.parseDouble(element.getAsString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (value instanceof Number count) {
            return count.intValue();
        }
        if (value instanceof String digits) {
            try {
                return Integer.parseInt(digits.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Object member(Object node, String key) {
        if (node instanceof JsonElement element) {
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject object = element.getAsJsonObject();
            JsonElement child = object.get(key);
            return child == null || child.isJsonNull() ? null : child;
        }
        if (node instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }
}
