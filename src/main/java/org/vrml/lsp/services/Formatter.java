package org.vrml.lsp.services;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.vrml.lsp.cst.CstKind;
import org.vrml.lsp.cst.CstNode;
import org.vrml.lsp.lexer.Token;
import org.vrml.lsp.lexer.TokenType;
import org.vrml.lsp.parser.ParseResult;

/**
 * Whitespace-only reflow of a parsed document.
 *
 * <p>The formatter never re-derives the file from the tree, which is the difference between a
 * reformat and a rewrite: it walks the token stream and replaces only the trivia lying between two
 * significant tokens, appending each significant token verbatim. A comma, a comment, a string, a
 * {@code DEF} name - all of them keep their bytes, so the worst this can do to a document is move
 * whitespace around. That holds even when the tree is wrong, since the tree is consulted only to
 * decide where lines begin.
 *
 * <p>Two facts about VRML make that decision small. Braces and brackets nest exactly, so how deep a
 * line sits is a count of the pairs open over it; and only the grammar knows that
 * {@code rotation 0 0 0 1 translation 0 0 0} is two statements, so which tokens start a line comes
 * from the tree. Comments stay where they stood - after the token they explain, or on a line of
 * their own - and a blank line between two statements survives as one blank line, because an author
 * who separated two paragraphs meant it.
 *
 * <p>A value list is kept on one line while it fits the width it was given and folded at its
 * elements otherwise; a {@code Coordinate} with four hundred points is why the option exists. Every
 * decision is a function of the token sequence and of the tree, never of the whitespace it replaces,
 * so running the formatter a second time produces no edits at all - which is what makes the result
 * safe to apply to a buffer the user is still typing in.
 */
public final class Formatter {

    /** How the reflow should look. The server reads both numbers from the client's settings. */
    public record Options(int indent, int maxColumn) {

        /** Two spaces and an 80-column line, which is how the corpus is mostly written already. */
        public static final Options DEFAULTS = new Options(2, 80);

        /**
         * Settings come from a client, so they are clamped rather than trusted: a negative indent
         * would repeat an illegal string, and a line length below one token's width would fold
         * everything. Public because the server layer reads settings off the wire and wants to show
         * the user the layout it will actually get, not the one it was asked for.
         */
        public static Options clamp(Options options) {
            int indent = Math.max(0, Math.min(16, options.indent()));
            int maxColumn = Math.max(20, Math.min(400, options.maxColumn()));
            return indent == options.indent() && maxColumn == options.maxColumn() ? options
                    : new Options(indent, maxColumn);
        }
    }

    /**
     * One replacement of a stretch of trivia.
     *
     * @param start offset of the trivia being replaced, inclusive
     * @param end offset of the trivia being replaced, exclusive
     * @param text what to put there instead
     */
    public record Edit(int start, int end, String text) {
    }

    /** The nodes whose every child is a statement, and so begins a line of its own. */
    private static final Set<CstKind> STATEMENT_CONTAINERS = EnumSet.of(CstKind.SCENE,
            CstKind.NODE_BODY, CstKind.PROTO_BODY, CstKind.SCRIPT_BODY, CstKind.NODE_LIST);

    /** The kinds a value list may hold; a list of nodes is broken by rule, a list of literals by width. */
    private static final Set<CstKind> LITERAL_ARRAYS = EnumSet.of(CstKind.NUMBER_ARRAY,
            CstKind.STRING_ARRAY);

    private final CharSequence source;
    private final Options options;
    private final List<Token> tokens;
    private final String newline;
    /** Per token: the depth its line sits at, and whether the layout puts it at a line's start. */
    private final int[] depth;
    private final boolean[] breakBefore;
    private final StringBuilder out;
    private final List<Edit> edits = new ArrayList<>();
    /** How far the text emitted so far reaches into its line, and whether anything significant is on it. */
    private int column;
    private boolean lineOpen;
    private boolean walked;
    private int last = -1;

    private Formatter(ParseResult parse, Options options) {
        this.source = parse.source();
        this.options = Options.clamp(options);
        this.tokens = parse.tokens();
        this.newline = detectNewline(source);
        this.depth = new int[tokens.size()];
        this.breakBefore = new boolean[tokens.size()];
        this.out = new StringBuilder(Math.max(16, source.length()));
        countDepths();
        markLayout(parse.root());
        foldWideLists(parse.root());
    }

    /**
     * The whitespace replacements that turn {@code parse} into a reformatted document.
     *
     * <p>Offsets refer to the document as parsed, and no two of them overlap, so a client may apply
     * them in any order.
     */
    public static List<Edit> edits(ParseResult parse, Options options) {
        Formatter formatter = new Formatter(parse, options);
        formatter.walk();
        return formatter.edits;
    }

    /** The same edits, restricted to the ones lying wholly inside {@code from} .. {@code to}. */
    public static List<Edit> edits(ParseResult parse, Options options, int from, int to) {
        List<Edit> all = edits(parse, options);
        if (from <= 0 && to >= parse.source().length()) {
            return all;
        }
        List<Edit> inside = new ArrayList<>();
        for (Edit edit : all) {
            if (edit.start() >= from && edit.end() <= to) {
                inside.add(edit);
            }
        }
        return inside;
    }

    /** The document as the reflow would write it, which is what {@link #edits} applied to it reads as. */
    public static String format(ParseResult parse, Options options) {
        Formatter formatter = new Formatter(parse, options);
        formatter.walk();
        return formatter.out.toString();
    }

    // ---- the layout --------------------------------------------------------------------------

    /**
     * The depth of every significant token, from the braces and brackets standing open over it.
     *
     * <p>A closer sits one level above what it closed, which is why the count is read before the
     * decrement rather than after. Brackets are counted with braces because they nest the same way:
     * a folded value list therefore lines up under the statement that opened it for free.
     */
    private void countDepths() {
        int level = 0;
        for (int i = 0; i < tokens.size(); i++) {
            TokenType type = tokens.get(i).type;
            if (type.isTrivia() || type == TokenType.EOF) {
                continue;
            }
            boolean closer = type == TokenType.RBRACE || type == TokenType.RBRACKET;
            depth[i] = Math.max(0, level - (closer ? 1 : 0));
            if (closer) {
                level = Math.max(0, level - 1);
            } else if (type == TokenType.LBRACE || type == TokenType.LBRACKET) {
                level++;
            }
        }
    }

    /**
     * The breaks the grammar asks for, wherever the tree says a statement begins.
     *
     * <p>A {@code PROTO} header is not a statement container: its children are the name, the
     * brackets and the body, and only the interface entries between the brackets are statements. Its
     * opening brace still starts a line, because that is how every file in the corpus writes it,
     * while a node's does not, because {@code Transform {} } is written with the body attached.
     */
    private void markLayout(CstNode node) {
        CstKind kind = node.kind();
        if (STATEMENT_CONTAINERS.contains(kind)) {
            for (CstNode child : node.children()) {
                markLine(child);
            }
        } else if (kind == CstKind.PROTO_DECL || kind == CstKind.EXTERN_PROTO_DECL) {
            for (CstNode child : node.children()) {
                if (child.kind() == CstKind.INTERFACE_DECL || child.kind() == CstKind.URI_LIST
                        || isBrace(child) || isBracket(child, TokenType.RBRACKET)) {
                    markLine(child);
                }
            }
        } else if (kind == CstKind.NODE) {
            for (CstNode child : node.children()) {
                if (isBracket(child, TokenType.RBRACE)) {
                    markLine(child);
                }
            }
        } else if (kind == CstKind.MF_VALUE) {
            CstNode contents = contents(node);
            if (contents != null && contents.kind() == CstKind.NODE_LIST) {
                markLine(node.child(node.childCount() - 1));
            }
        }
        for (CstNode child : node.children()) {
            markLayout(child);
        }
    }

    /**
     * The value lists that will not fit the line they belong to, broken at their elements.
     *
     * <p>Folding moves the line boundary, so a list inside a list can only be judged once the list
     * around it has been: the pass repeats until nothing new folds, which is at most once per nested
     * pair. A list that stays too wide after being folded is then packed greedily, one element per
     * line at most until the line is full.
     */
    private void foldWideLists(CstNode node) {
        List<CstNode> lists = new ArrayList<>();
        collectLists(node, lists);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CstNode list : lists) {
                List<Integer> items = contentTokens(list);
                if (items.isEmpty() || broken(items.get(0)) || !tooWide(firstToken(list))) {
                    continue;
                }
                markIndex(items.get(0));
                CstNode closer = closer(list);
                if (closer != null) {
                    markLine(closer);
                }
                changed = true;
            }
        }
        for (CstNode list : lists) {
            List<Integer> items = contentTokens(list);
            if (!items.isEmpty() && broken(items.get(0))) {
                pack(items);
            }
        }
    }

    /**
     * A bracketed list of literals, which is the only thing worth breaking on width: a list of
     * nodes is broken by rule, and a {@code PROTO} interface is a statement per line already.
     */
    private boolean isList(CstNode node) {
        if (node.kind() == CstKind.URI_LIST) {
            return true;
        }
        CstNode contents = node.kind() == CstKind.MF_VALUE ? contents(node) : null;
        return contents != null && LITERAL_ARRAYS.contains(contents.kind());
    }

    private void collectLists(CstNode node, List<CstNode> into) {
        if (isList(node)) {
            into.add(node);
        }
        for (CstNode child : node.children()) {
            collectLists(child, into);
        }
    }

    /** Every token a list holds, its brackets left out. */
    private List<Integer> contentTokens(CstNode list) {
        List<Integer> found = new ArrayList<>();
        for (CstNode child : list.children()) {
            if (child.kind() == CstKind.PUNCT) {
                continue;
            }
            found.addAll(tokensOf(child));
        }
        return found;
    }

    private CstNode closer(CstNode list) {
        CstNode last = list.child(list.childCount() - 1);
        return isBracket(last, TokenType.RBRACKET) ? last : null;
    }

    /** The list a value holds, or null for an empty one, which is written {@code []} on the line. */
    private CstNode contents(CstNode list) {
        for (CstNode child : list.children()) {
            if (child.kind() != CstKind.PUNCT) {
                return child;
            }
        }
        return null;
    }

    /** Whether the layout already puts {@code index} at a line's start. */
    private boolean broken(int index) {
        return index >= 0 && breakBefore[index];
    }

    /** Whether the line carrying {@code index} would run past the width it was given. */
    private boolean tooWide(int index) {
        return lineWidth(index) > options.maxColumn();
    }

    /**
     * How wide {@code index} looks on the line it shares, with nothing broken and every token
     * beside it a single space apart.
     *
     * <p>The line is found by walking back to the nearest break the layout asked for, so the answer
     * depends on the tokens alone and not on how the file happens to be wrapped today. That is what
     * makes folding a folded document fold the same way twice.
     */
    private int lineWidth(int index) {
        if (index < 0) {
            return 0;
        }
        int start = index;
        while (start > 0 && !breakBefore[start]) {
            start--;
        }
        int width = depth[start] * options.indent();
        for (int i = start; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type.isTrivia() || token.type == TokenType.EOF) {
                continue;
            }
            if (i > start && breakBefore[i]) {
                break;
            }
            width += token.length() + commasAfter(i) + (i == start ? 0 : 1);
        }
        return width;
    }

    /**
     * How many commas sit between {@code index} and the token after it, which is how much the line
     * grows when it is kept together: the formatter re-emits each of them against the token that
     * preceded it, so a number list separated by commas costs a character per element.
     */
    private int commasAfter(int index) {
        int commas = 0;
        for (int i = index + 1; i < tokens.size(); i++) {
            Token trivia = tokens.get(i);
            if (!trivia.type.isTrivia()) {
                break;
            }
            if (trivia.type == TokenType.COMMENT) {
                continue;
            }
            String run = slice(trivia);
            for (int c = 0; c < run.length(); c++) {
                if (run.charAt(c) == ',') {
                    commas++;
                }
            }
        }
        return commas;
    }

    /** One element per line until the line is full, for a folded list that is still too wide. */
    private void pack(List<Integer> items) {
        if (items.size() < 2) {
            return;
        }
        int indent = depth[items.get(0)] * options.indent();
        int filled = indent;
        for (int i = 0; i < items.size(); i++) {
            int index = items.get(i);
            int width = tokens.get(index).length() + commasAfter(index);
            if (i == 0) {
                filled += width;
                continue;
            }
            if (filled + 1 + width > options.maxColumn()) {
                breakBefore[index] = true;
                filled = indent + width;
            } else {
                filled += 1 + width;
            }
        }
    }

    // ---- the reflow --------------------------------------------------------------------------

    /** Walks the tokens, replacing the trivia between them and keeping every token's own bytes. */
    private void walk() {
        if (walked) {
            return;
        }
        walked = true;
        int gapStart = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type == TokenType.EOF) {
                break;
            }
            if (token.type.isTrivia()) {
                continue;
            }
            gap(gapStart, token.start, last, i);
            appendToken(token);
            last = i;
            gapStart = token.end;
        }
        tail(gapStart);
    }

    /**
     * What lies between two significant tokens: the commas and comments that were there, then the
     * break or the space the layout asks for.
     *
     * <p>Commas are held back until the line they belong to is closed, because VRML counts them as
     * whitespace: a file that separated two points with {@code 0 0 0,\n1 0 0} keeps its comma at the
     * end of the first line, and one that wrote {@code 0 0 0 ,\n 1 0 0} keeps the same two characters.
     */
    private void gap(int from, int to, int previous, int next) {
        int mark = out.length();
        int pending = 0;
        int commas = 0;
        int comments = 0;
        int indent = depth[next] * options.indent();
        for (int i = previous + 1; i < next; i++) {
            Token trivia = tokens.get(i);
            if (trivia.type != TokenType.COMMENT) {
                String run = slice(trivia);
                for (int c = 0; c < run.length(); c++) {
                    char at = run.charAt(c);
                    if (at == ',') {
                        commas++;
                    } else if (at == '\n') {
                        pending++;
                    } else if (at == '\r') {
                        pending++;
                        if (c + 1 < run.length() && run.charAt(c + 1) == '\n') {
                            c++;
                        }
                    }
                }
                continue;
            }
            writeCommas(commas);
            commas = 0;
            comments++;
            placeComment(trivia, indent, pending);
            pending = 0;
        }
        writeCommas(commas);
        if (previous < 0) {
            // The document's own header comments; breakLine drops a blank line before any of them.
            if (comments > 0) {
                breakLine(indent, pending);
            }
        } else if (!lineOpen || breakBefore[next] && !emptyPair(previous, next)) {
            breakLine(indent, pending);
        } else if (needsSpace(previous, next)) {
            emit(" ");
        }
        record(from, to, mark);
    }

    /** Everything after the last token: the comments that trail the file, and one newline to end on. */
    private void tail(int from) {
        int mark = out.length();
        int pending = 0;
        int indent = last < 0 ? 0 : depth[last] * options.indent();
        for (int i = last + 1; i < tokens.size(); i++) {
            Token trivia = tokens.get(i);
            if (trivia.type == TokenType.EOF) {
                break;
            }
            if (trivia.type == TokenType.COMMENT) {
                placeComment(trivia, indent, pending);
                pending = 0;
            } else {
                String run = slice(trivia);
                for (int c = 0; c < run.length(); c++) {
                    if (run.charAt(c) == '\n') {
                        pending++;
                    } else if (run.charAt(c) == '\r') {
                        pending++;
                        if (c + 1 < run.length() && run.charAt(c + 1) == '\n') {
                            c++;
                        }
                    }
                }
            }
        }
        if (lineOpen) {
            emit(newline);
        }
        record(from, source.length(), mark);
    }

    /** One comment, kept verbatim, on the line it stood: after its token, or alone with the code it documents. */
    private void placeComment(Token trivia, int indent, int pending) {
        if (pending > 0 || !lineOpen) {
            breakLine(indent, pending);
        } else {
            emit(" ");
        }
        emit(commentBody(trivia));
        emit(newline);
    }

    /**
     * Starts the line {@code indent} columns deep, keeping at most one of the blank lines the author
     * wrote between the two tokens.
     */
    private void breakLine(int indent, int pending) {
        if (out.length() == 0) {
            pending = 0;
        }
        if (lineOpen) {
            emit(newline);
            if (pending >= 2) {
                emit(newline);
            }
        } else if (pending >= 1) {
            emit(newline);
        }
        if (column == 0 && indent > 0) {
            emit(" ".repeat(indent));
        }
    }

    private void writeCommas(int commas) {
        if (commas > 0) {
            emit(",".repeat(commas));
        }
    }

    /** Whether the two tokens are an empty {@code {}} or {@code []}, which stays as tight as it reads. */
    private boolean emptyPair(int previous, int next) {
        TokenType opened = tokens.get(previous).type;
        TokenType closed = tokens.get(next).type;
        return opened == TokenType.LBRACE && closed == TokenType.RBRACE
                || opened == TokenType.LBRACKET && closed == TokenType.RBRACKET;
    }

    /**
     * Whether a space separates two tokens that share a line.
     *
     * <p>{@code ROUTE a.b TO c.d} has no room around its dots and {@code Box {}} has no room inside
     * its braces; everything else in the language is words, and words need separating.
     */
    private boolean needsSpace(int previous, int next) {
        TokenType before = tokens.get(previous).type;
        TokenType after = tokens.get(next).type;
        if (before == TokenType.DOT || after == TokenType.DOT) {
            return false;
        }
        boolean open = before == TokenType.LBRACE || before == TokenType.LBRACKET;
        boolean close = after == TokenType.RBRACE || after == TokenType.RBRACKET;
        return !(open && close);
    }

    // ---- writing -----------------------------------------------------------------------------

    /** Records what the trivia was replaced with, if it changed at all. */
    private void record(int from, int to, int mark) {
        String replacement = out.substring(mark);
        if (!replacement.contentEquals(source.subSequence(from, to))) {
            edits.add(new Edit(from, to, replacement));
        }
    }

    private void emit(String text) {
        out.append(text);
        int line = text.lastIndexOf('\n');
        if (line < 0) {
            column += text.length();
        } else {
            column = text.length() - line - 1;
            lineOpen = false;
        }
    }

    /** Copies a token into the output untouched, which is the whole reason this cannot lose text. */
    private void appendToken(Token token) {
        out.append(source, token.start, token.end);
        int line = -1;
        for (int i = token.end - 1; i >= token.start; i--) {
            if (source.charAt(i) == '\n') {
                line = i;
                break;
            }
        }
        column = line < 0 ? column + token.length() : token.end - line - 1;
        lineOpen = true;
    }

    // ---- reading -----------------------------------------------------------------------------

    /** Index of the first significant token under {@code node}, or -1 for a composite with nothing in it. */
    private int firstToken(CstNode node) {
        if (node == null) {
            return -1;
        }
        if (node.isLeaf()) {
            return node.tokenIndex();
        }
        for (CstNode child : node.children()) {
            int index = firstToken(child);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    private void markLine(CstNode node) {
        markIndex(firstToken(node));
    }

    private void markIndex(int index) {
        if (index >= 0) {
            breakBefore[index] = true;
        }
    }

    /** Every significant token under {@code node}, in the order the file spells them. */
    private List<Integer> tokensOf(CstNode node) {
        List<Integer> found = new ArrayList<>();
        collectTokens(node, found);
        return found;
    }

    private void collectTokens(CstNode node, List<Integer> into) {
        if (node.isLeaf()) {
            into.add(node.tokenIndex());
            return;
        }
        for (CstNode child : node.children()) {
            collectTokens(child, into);
        }
    }

    private boolean isBrace(CstNode node) {
        return isBracket(node, TokenType.RBRACE) || isBracket(node, TokenType.LBRACE);
    }

    private boolean isBracket(CstNode node, TokenType type) {
        if (node.kind() != CstKind.PUNCT || !node.isLeaf()) {
            return false;
        }
        int index = node.tokenIndex();
        return index >= 0 && index < tokens.size() && tokens.get(index).type == type;
    }

    private String slice(Token token) {
        return source.subSequence(token.start, token.end).toString();
    }

    /** A comment without the line break it was lexed with, which the formatter re-adds itself. */
    private String commentBody(Token trivia) {
        String written = slice(trivia);
        int end = written.length();
        while (end > 0 && Character.isWhitespace(written.charAt(end - 1))) {
            end--;
        }
        return written.substring(0, end);
    }

    /**
     * The line ending to write with, which is the one the document itself mostly uses.
     *
     * <p>Counted rather than read off the first line, in either direction: a file whose author pasted
     * one line in from elsewhere should not have its other two thousand rewritten to match it, and a
     * file that is Windows-written bar its header should not have the whole of it converted to Unix.
     * Rewriting every line of a document because of how one of them ends is the unasked-for diff this
     * formatter exists to avoid, whichever way the stray came from. Ties go to the Unix ending, and so
     * does a document with no break at all - or only lone {@code \r}s, an ending dead long enough that
     * normalising it is worth more than what the column counting would have to learn for it.
     */
    private static String detectNewline(CharSequence text) {
        int windows = 0;
        int unix = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\n') {
                continue;
            }
            if (i > 0 && text.charAt(i - 1) == '\r') {
                windows++;
            } else {
                unix++;
            }
        }
        return windows > unix ? "\r\n" : "\n";
    }
}
