package io.hensu.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Parser for the small YAML subset Hensu uses for its configuration files.
///
/// Hensu configuration is read by `hensu-core`, which carries no third-party
/// dependencies and must stay reflection-free for the GraalVM native image, so
/// a real YAML library is not available. Rather than let every loader invent its
/// own line matching, the supported grammar is specified here once and both
/// `commands.yaml` and `mcp.yaml` are read through it.
///
/// ### Supported grammar
/// - Block mappings nested at most {@link #MAX_DEPTH} levels below the document
///   root, indented in steps of exactly two spaces. Tabs are rejected.
/// - Keys matching `[A-Za-z0-9_.-]+`, unique within their mapping.
/// - Scalars, either plain (`timeout: 5000`) or quoted (`"a: b"`, `'it''s'`).
///   Double-quoted scalars honour `\\`, `\"`, `\n` and `\t`; single-quoted
///   scalars honour the doubled-quote escape.
/// - Lists of scalars, block form (`- item` at the key's own indentation or one
///   level deeper) and flow form (`["a", "b"]`).
/// - Flow mappings (`{ type: string, enum: ["a", "b"] }`) as a leaf value, whose
///   own values are scalars or flow lists. A flow mapping is a leaf, so it does
///   not count toward the depth limit – it is how a schema entry stays
///   declarable inside the limit.
/// - Comments introduced by `#` at the start of a line or after whitespace,
///   outside quotes.
///
/// Anything else – anchors, aliases, multi-document streams, block scalars,
/// lists of mappings – is rejected with the offending line number rather than
/// silently misread.
///
/// ### Contracts
/// - **Precondition**: `content` is a complete document, not a fragment
/// - **Postcondition**: the returned tree is immutable and every node carries
///   the source line it began on
///
/// ### Usage
/// {@snippet :
/// MiniYaml.Mapping root = MiniYaml.parse(Files.readString(path));
/// MiniYaml.Mapping commands = root.require("commands").asMapping();
/// for (String id : commands.keys()) {
///     long timeout = commands.require(id).asMapping().require("timeout").asLong();
/// }
/// }
///
/// @implNote **Immutable after construction.** The parser holds no state between
/// calls and the value tree is deeply unmodifiable, so results are safe to share
/// across Virtual Threads.
/// @see MiniYamlException for the failure carrying the source line
public final class MiniYaml {

    /// Deepest block mapping the grammar accepts, counted from the document root.
    ///
    /// The root mapping is level zero, so the `commands` / command id / `tool` /
    /// `params` chain of a command catalog sits exactly at the limit. Anything
    /// below it – a parameter schema, an MCP server's options – is written as a
    /// flow mapping, which is a leaf and therefore costs no depth. The limit is
    /// what keeps a runaway indentation mistake from being parsed as a plausible
    /// but wrong document.
    public static final int MAX_DEPTH = 4;

    private static final Pattern KEY_LINE = Pattern.compile("^([A-Za-z0-9_.\\-]+):(?:\\s+(.*))?$");
    private static final int INDENT_STEP = 2;

    private final List<SourceLine> lines;
    private int cursor;

    private MiniYaml(List<SourceLine> lines) {
        this.lines = lines;
    }

    /// Parses a document into a mapping tree.
    ///
    /// @param content the complete document text, not null
    /// @return the root mapping, never null (empty when the document holds no keys)
    /// @throws MiniYamlException if the document leaves the supported grammar
    /// @throws NullPointerException if content is null
    public static Mapping parse(String content) {
        MiniYaml parser = new MiniYaml(scan(content));
        Mapping root = parser.readMapping(0, 0);
        if (parser.cursor < parser.lines.size()) {
            SourceLine leftover = parser.lines.get(parser.cursor);
            throw new MiniYamlException(
                    "unexpected content '" + leftover.text() + "' at the document root",
                    leftover.number());
        }
        return root;
    }

    // ---------------------------------------------------------------- scanning

    private static List<SourceLine> scan(String content) {
        String[] raw = content.split("\n", -1);
        List<SourceLine> scanned = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            int number = i + 1;
            String line = stripTrailingCarriageReturn(raw[i]);
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            if (indent < line.length() && line.charAt(indent) == '\t') {
                throw new MiniYamlException("tabs are not permitted in indentation", number);
            }
            String text = stripComment(line.substring(indent)).stripTrailing();
            if (text.isEmpty()) {
                continue;
            }
            if (indent % INDENT_STEP != 0) {
                throw new MiniYamlException(
                        "indentation must be a multiple of "
                                + INDENT_STEP
                                + " spaces, found "
                                + indent,
                        number);
            }
            scanned.add(new SourceLine(indent, text, number));
        }
        return scanned;
    }

    private static String stripTrailingCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static String stripComment(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    // ----------------------------------------------------------------- parsing

    private Mapping readMapping(int indent, int depth) {
        if (depth > MAX_DEPTH) {
            SourceLine here = lines.get(cursor);
            throw new MiniYamlException(
                    "mapping nests deeper than the supported depth of " + MAX_DEPTH, here.number());
        }
        int startLine = cursor < lines.size() ? lines.get(cursor).number() : 1;
        Map<String, Value> entries = new LinkedHashMap<>();
        while (cursor < lines.size() && lines.get(cursor).indent() == indent) {
            SourceLine line = lines.get(cursor);
            Matcher matcher = KEY_LINE.matcher(line.text());
            if (!matcher.matches()) {
                throw new MiniYamlException(
                        "expected 'key: value' or 'key:', found '" + line.text() + "'",
                        line.number());
            }
            String key = matcher.group(1);
            if (entries.containsKey(key)) {
                throw new MiniYamlException("duplicate key '" + key + "'", line.number());
            }
            cursor++;
            String inline = matcher.group(2);
            entries.put(
                    key,
                    inline != null && !inline.isBlank()
                            ? readInline(inline.strip(), line.number())
                            : readBlock(indent, depth, line.number()));
        }
        if (cursor < lines.size() && lines.get(cursor).indent() > indent) {
            SourceLine stray = lines.get(cursor);
            throw new MiniYamlException(
                    "unexpected indentation; '" + stray.text() + "' has no parent key",
                    stray.number());
        }
        return new Mapping(entries, startLine);
    }

    private Value readBlock(int indent, int depth, int keyLine) {
        if (cursor >= lines.size()) {
            return new Mapping(Map.of(), keyLine);
        }
        SourceLine next = lines.get(cursor);
        if (next.indent() == indent && next.text().startsWith("-")) {
            return readSequence(indent);
        }
        if (next.indent() == indent + INDENT_STEP) {
            return next.text().startsWith("-")
                    ? readSequence(indent + INDENT_STEP)
                    : readMapping(indent + INDENT_STEP, depth + 1);
        }
        if (next.indent() > indent) {
            throw new MiniYamlException(
                    "expected an indentation of "
                            + (indent + INDENT_STEP)
                            + " spaces, found "
                            + next.indent(),
                    next.number());
        }
        return new Mapping(Map.of(), keyLine);
    }

    private Seq readSequence(int indent) {
        int startLine = lines.get(cursor).number();
        List<Value> items = new ArrayList<>();
        while (cursor < lines.size()
                && lines.get(cursor).indent() == indent
                && lines.get(cursor).text().startsWith("-")) {
            SourceLine line = lines.get(cursor);
            String text = line.text().substring(1);
            if (!text.isEmpty() && !text.startsWith(" ")) {
                throw new MiniYamlException(
                        "list item '" + line.text() + "' must be written as '- value'",
                        line.number());
            }
            String item = text.strip();
            if (item.isEmpty()) {
                throw new MiniYamlException("list item has no value", line.number());
            }
            items.add(readInline(item, line.number()));
            cursor++;
        }
        return new Seq(items, startLine);
    }

    private Value readInline(String text, int line) {
        if (text.startsWith("[")) {
            if (!text.endsWith("]")) {
                throw new MiniYamlException("unterminated flow list '" + text + "'", line);
            }
            List<Value> items = new ArrayList<>();
            for (String element : splitFlow(text.substring(1, text.length() - 1), line)) {
                items.add(readInline(element, line));
            }
            return new Seq(items, line);
        }
        if (text.startsWith("{")) {
            if (!text.endsWith("}")) {
                throw new MiniYamlException("unterminated flow mapping '" + text + "'", line);
            }
            Map<String, Value> entries = new LinkedHashMap<>();
            for (String element : splitFlow(text.substring(1, text.length() - 1), line)) {
                int colon = indexOfUnquoted(element);
                if (colon < 0) {
                    throw new MiniYamlException(
                            "flow mapping entry '" + element + "' is not 'key: value'", line);
                }
                String key = element.substring(0, colon).strip();
                if (!key.matches("[A-Za-z0-9_.\\-]+")) {
                    throw new MiniYamlException(
                            "flow mapping key '" + key + "' is not a simple key", line);
                }
                if (entries.containsKey(key)) {
                    throw new MiniYamlException("duplicate key '" + key + "'", line);
                }
                entries.put(key, readInline(element.substring(colon + 1).strip(), line));
            }
            return new Mapping(entries, line);
        }
        return new Scalar(unquote(text, line), line);
    }

    private static List<String> splitFlow(String body, int line) {
        List<String> parts = new ArrayList<>();
        if (body.isBlank()) {
            return parts;
        }
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int nesting = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            switch (c) {
                case '"', '\'' -> {
                    quote = c;
                    current.append(c);
                }
                case '[', '{' -> {
                    nesting++;
                    current.append(c);
                }
                case ']', '}' -> {
                    if (--nesting < 0) {
                        throw new MiniYamlException("unbalanced flow collection", line);
                    }
                    current.append(c);
                }
                case ',' -> {
                    if (nesting > 0) {
                        current.append(c);
                    } else {
                        parts.add(current.toString().strip());
                        current.setLength(0);
                    }
                }
                default -> current.append(c);
            }
        }
        if (quote != 0) {
            throw new MiniYamlException("unterminated quoted scalar in flow collection", line);
        }
        if (nesting != 0) {
            throw new MiniYamlException("unbalanced flow collection", line);
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return parts;
    }

    private static int indexOfUnquoted(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':') {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String text, int line) {
        if (text.length() >= 2 && text.charAt(0) == '"' && text.endsWith("\"")) {
            return unescapeDouble(text.substring(1, text.length() - 1), line);
        }
        if (text.length() >= 2 && text.charAt(0) == '\'' && text.endsWith("'")) {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        if (text.startsWith("\"") || text.startsWith("'")) {
            throw new MiniYamlException("unterminated quoted scalar " + text, line);
        }
        return text;
    }

    private static String unescapeDouble(String text, int line) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i + 1 >= text.length()) {
                throw new MiniYamlException("dangling escape in quoted scalar", line);
            }
            char escaped = text.charAt(++i);
            out.append(
                    switch (escaped) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case '"' -> '"';
                        case '\\' -> '\\';
                        default ->
                                throw new MiniYamlException(
                                        "unsupported escape '\\" + escaped + "'", line);
                    });
        }
        return out.toString();
    }

    private record SourceLine(int indent, String text, int number) {}

    // ------------------------------------------------------------- value model

    /// A parsed node, tagged with the source line it began on.
    ///
    /// ### Permitted Subtypes
    /// - {@link Scalar} – a single value
    /// - {@link Seq} – a list of values
    /// - {@link Mapping} – a set of keyed values
    ///
    /// The `as…` methods are the reading half of the contract: each either
    /// returns the requested shape or throws a {@link MiniYamlException} naming
    /// the line, so a loader never has to write its own type-mismatch message.
    public sealed interface Value permits Scalar, Seq, Mapping {

        /// Returns the one-based source line this node began on.
        ///
        /// @return the source line number
        int line();

        /// Reads this node as a mapping.
        ///
        /// @return this node as a mapping, never null
        /// @throws MiniYamlException if the node is not a mapping
        default Mapping asMapping() {
            if (this instanceof Mapping mapping) {
                return mapping;
            }
            throw new MiniYamlException("expected a mapping", line());
        }

        /// Reads this node as a list.
        ///
        /// @return this node as a list, never null
        /// @throws MiniYamlException if the node is not a list
        default Seq asSeq() {
            if (this instanceof Seq seq) {
                return seq;
            }
            throw new MiniYamlException("expected a list", line());
        }

        /// Reads this node as scalar text.
        ///
        /// @return the scalar text, never null
        /// @throws MiniYamlException if the node is not a scalar
        default String asString() {
            if (this instanceof Scalar scalar) {
                return scalar.text();
            }
            throw new MiniYamlException("expected a scalar value", line());
        }

        /// Reads this node as a whole number.
        ///
        /// @return the parsed value
        /// @throws MiniYamlException if the node is not a scalar holding an integer
        default long asLong() {
            String text = asString();
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new MiniYamlException("expected a number, found '" + text + "'", line());
            }
        }

        /// Reads this node as a boolean, accepting only `true` and `false`.
        ///
        /// @return the parsed value
        /// @throws MiniYamlException if the node is not a scalar holding a boolean
        default boolean asBoolean() {
            String text = asString();
            return switch (text) {
                case "true" -> true;
                case "false" -> false;
                default ->
                        throw new MiniYamlException(
                                "expected true or false, found '" + text + "'", line());
            };
        }

        /// Reads this node as a list of scalar strings.
        ///
        /// @return the list contents, never null (may be empty)
        /// @throws MiniYamlException if the node is not a list, or holds a non-scalar item
        default List<String> asStringList() {
            List<String> values = new ArrayList<>();
            for (Value item : asSeq().items()) {
                values.add(item.asString());
            }
            return Collections.unmodifiableList(values);
        }
    }

    /// A single parsed value, already unquoted and comment-stripped.
    ///
    /// @param text the scalar text, not null
    /// @param line one-based source line, positive
    public record Scalar(String text, int line) implements Value {

        /// Compact constructor enforcing the immutability contract.
        public Scalar {
            if (text == null) {
                throw new NullPointerException("text must not be null");
            }
        }
    }

    /// A parsed list of values.
    ///
    /// @param items the list contents, not null (may be empty)
    /// @param line one-based source line the list began on, positive
    public record Seq(List<Value> items, int line) implements Value {

        /// Compact constructor taking a defensive copy.
        public Seq {
            items = List.copyOf(items);
        }
    }

    /// A parsed mapping, preserving document order.
    ///
    /// @param entries the keyed values in document order, not null (may be empty)
    /// @param line one-based source line the mapping began on, positive
    public record Mapping(Map<String, Value> entries, int line) implements Value {

        /// Compact constructor taking an order-preserving defensive copy.
        public Mapping {
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }

        /// Returns the keys in document order.
        ///
        /// @return the key set in document order, never null
        public Set<String> keys() {
            return entries.keySet();
        }

        /// Returns the value for a key, or null when the key is absent.
        ///
        /// @param key the key to look up, not null
        /// @return the value, or null when absent
        public Value get(String key) {
            return entries.get(key);
        }

        /// Returns whether a key is present.
        ///
        /// @param key the key to look up, not null
        /// @return true if the mapping holds the key
        public boolean has(String key) {
            return entries.containsKey(key);
        }

        /// Returns the value for a key, failing when it is absent.
        ///
        /// @param key the key to look up, not null
        /// @return the value, never null
        /// @throws MiniYamlException if the key is absent
        public Value require(String key) {
            Value value = entries.get(key);
            if (value == null) {
                throw new MiniYamlException("missing required key '" + key + "'", line);
            }
            return value;
        }
    }
}
