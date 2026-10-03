package io.hensu.mcp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Reads a tool's `x-mcp-header` annotations and turns a call's arguments into
/// the `Mcp-Param-{Name}` headers they require.
///
/// Revision 2026-07-28 lets a server mark a tool parameter with `x-mcp-header`.
/// A client on Streamable HTTP **must** then repeat that argument's value in a
/// request header, so a load balancer or gateway can route on it without
/// parsing the body. The server checks the header against the body and answers
/// `-32020` (`HeaderMismatch`) when they disagree or the header is missing.
///
/// Two halves, run at two different times:
///
/// - **At listing** – {@link #read} checks every annotation against the spec's
///   constraints. A tool with even one invalid annotation must be left out of
///   the catalog. The spec asks for exactly that rather than failing the whole
///   server, so one malformed definition does not take its neighbours with it.
/// - **At call time** – {@link #headers} reads each annotated argument at its
///   exact property path, converts it to text and encodes it.
///
/// ### Encoding
/// A header value may carry only visible ASCII, space and tab, so a value that
/// does not fit is sent as `=?base64?{UTF-8 bytes, Base64}?=`. The same form
/// carries an `Mcp-Name` that is not header-safe, which is why {@link #encode}
/// is shared with the standard headers. Tab is always encoded rather than sent
/// bare. The spec permits a bare tab, but an intermediary may fold or trim
/// whitespace, while a Base64 value decodes to the same string on the server
/// either way.
///
/// @implNote **Immutable.** Constants and pure functions; safe to use from any thread.
/// @see StreamableHttpMcpConnection for where the headers are attached
/// @see McpEra.Modern for the only era that mirrors parameters
final class McpParameterHeaders {

    /// Schema keyword a server marks a mirrored parameter with.
    static final String ANNOTATION = "x-mcp-header";

    /// Prefix every mirrored header name carries.
    static final String HEADER_PREFIX = "Mcp-Param-";

    /// Largest integer every conforming implementation can represent exactly.
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private static final String SENTINEL_OPEN = "=?base64?";
    private static final String SENTINEL_CLOSE = "?=";

    private static final Set<String> MIRRORABLE_TYPES = Set.of("string", "integer", "boolean");

    /// RFC 9110 `tchar`, less the letters and digits.
    private static final String TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~";

    /// Keywords whose value maps names to subschemas, not a schema itself.
    private static final Set<String> NAME_KEYED =
            Set.of("properties", "patternProperties", "$defs", "definitions", "dependentSchemas");

    /// Keywords whose value is instance data, where a key spelled like the
    /// annotation is not an annotation.
    private static final Set<String> INSTANCE_VALUED =
            Set.of("enum", "const", "default", "examples", "required");

    private McpParameterHeaders() {
        // Utility class
    }

    /// One parameter a server asked to see mirrored into a header.
    ///
    /// @param name the annotation's value, the `{Name}` in `Mcp-Param-{Name}`, not null
    /// @param path the chain of `properties` keys leading to the parameter, not empty
    record Mirror(String name, List<String> path) {

        /// Returns the full header name this parameter is sent under.
        ///
        /// @return `Mcp-Param-` followed by the annotation's value, never null
        String header() {
            return HEADER_PREFIX + name;
        }

        /// Returns the path as an operator reads it.
        ///
        /// @return the property names joined with dots, never null
        String dotted() {
            return String.join(".", path);
        }
    }

    /// What a tool's annotations amount to.
    ///
    /// ### Permitted Subtypes
    /// - {@link Accepted} – every annotation is valid, possibly none at all
    /// - {@link Rejected} – at least one annotation breaks a constraint, so the
    ///   tool must not be offered
    sealed interface Designation permits Accepted, Rejected {}

    /// Every annotation on the tool is valid.
    ///
    /// @param mirrors the parameters to mirror, never null (empty for most tools)
    record Accepted(List<Mirror> mirrors) implements Designation {

        /// A tool with nothing to mirror.
        static final Accepted NONE = new Accepted(List.of());
    }

    /// The tool breaks at least one annotation constraint.
    ///
    /// @param reason the first violation found, naming the property, not null
    record Rejected(String reason) implements Designation {}

    /// Checks a tool's input schema against every `x-mcp-header` constraint.
    ///
    /// ### Contracts
    /// - **Postcondition**: an {@link Accepted} result names each annotated
    ///   property once, by its exact `properties` path
    ///
    /// @param inputSchema the tool's input schema, may be null or empty
    /// @return the parameters to mirror, or the reason the tool must be left out,
    ///     never null
    static Designation read(Map<String, Object> inputSchema) {
        if (inputSchema == null || inputSchema.isEmpty()) {
            return Accepted.NONE;
        }
        List<Mirror> mirrors = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        reachable(inputSchema, List.of(), mirrors, violations);
        if (!violations.isEmpty()) {
            return new Rejected(violations.getFirst());
        }

        Set<String> seen = new HashSet<>();
        for (Mirror mirror : mirrors) {
            if (!seen.add(mirror.name().toLowerCase(Locale.ROOT))) {
                return new Rejected(
                        ANNOTATION
                                + " '"
                                + mirror.name()
                                + "' is declared on more than one property; header names"
                                + " compare case-insensitively, so each must be unique");
            }
        }
        return mirrors.isEmpty() ? Accepted.NONE : new Accepted(List.copyOf(mirrors));
    }

    /// Builds the headers one call's arguments require.
    ///
    /// An argument that is absent, or present as JSON `null`, sends no header.
    /// The spec requires both to be omitted, and a server must not expect them.
    ///
    /// @param mirrors the tool's accepted mirrors, not null
    /// @param arguments the agent's arguments, not null (may be empty)
    /// @return header name to encoded value, in declaration order, never null
    /// @throws McpInvalidArgumentException if an annotated argument is present
    ///     but has no header form: an object, a list, a number with a fraction,
    ///     or an integer outside the IEEE 754 safe range
    static Map<String, String> headers(List<Mirror> mirrors, Map<String, Object> arguments) {
        if (mirrors.isEmpty()) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (Mirror mirror : mirrors) {
            Object value = valueAt(arguments, mirror.path());
            if (value != null) {
                headers.put(mirror.header(), encode(textOf(value, mirror)));
            }
        }
        return headers;
    }

    /// Encodes a value for an `Mcp-Param-{Name}` or `Mcp-Name` header.
    ///
    /// A value is sent unchanged when every character is printable ASCII, it
    /// neither starts nor ends with a space, and it does not itself look like
    /// the Base64 sentinel. Anything else is sent in the sentinel form. That
    /// includes a literal `=?base64?…?=`, which would otherwise decode on the
    /// server into something the agent never sent.
    ///
    /// @param value the text to carry, not null
    /// @return the header value, never null
    static String encode(String value) {
        return isPlain(value)
                ? value
                : SENTINEL_OPEN
                        + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
                        + SENTINEL_CLOSE;
    }

    // --------------------------------------------------------------- reading

    /// Walks a schema node reached from the root through `properties` alone.
    private static void reachable(
            Map<?, ?> node, List<String> path, List<Mirror> mirrors, List<String> violations) {
        if (node.containsKey(ANNOTATION)) {
            if (path.isEmpty()) {
                violations.add(ANNOTATION + " annotates the schema root rather than a property");
            } else {
                annotation(node, path)
                        .ifPresentOrElse(
                                violations::add,
                                () ->
                                        mirrors.add(
                                                new Mirror(
                                                        (String) node.get(ANNOTATION),
                                                        List.copyOf(path))));
            }
        }
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            if (!(entry.getKey() instanceof String keyword) || ANNOTATION.equals(keyword)) {
                continue;
            }
            if ("properties".equals(keyword) && entry.getValue() instanceof Map<?, ?> properties) {
                for (Map.Entry<?, ?> property : properties.entrySet()) {
                    if (property.getKey() instanceof String name
                            && property.getValue() instanceof Map<?, ?> child) {
                        List<String> deeper = new ArrayList<>(path);
                        deeper.add(name);
                        reachable(child, deeper, mirrors, violations);
                    }
                }
                continue;
            }
            unreachable(keyword, entry.getValue(), keyword, path, violations);
        }
    }

    /// Scans a keyword's value that the static-reachability rule places out of bounds.
    ///
    /// Anything under `items`, a composition or conditional keyword, `$defs` and
    /// the like is not reachable through `properties` alone, so an annotation
    /// found there is invalid wherever it sits. The spec rejects the tool rather
    /// than ignoring the annotation. `origin` is the keyword that first left the
    /// reachable chain, because that is the one an operator has to change – not
    /// whichever `properties` happens to sit nearest the annotation.
    private static void unreachable(
            String keyword,
            Object value,
            String origin,
            List<String> path,
            List<String> violations) {
        if (INSTANCE_VALUED.contains(keyword)) {
            return;
        }
        if (NAME_KEYED.contains(keyword) && value instanceof Map<?, ?> named) {
            for (Object child : named.values()) {
                if (child instanceof Map<?, ?> schema) {
                    outOfBounds(schema, origin, path, violations);
                }
            }
            return;
        }
        if (value instanceof Map<?, ?> schema) {
            outOfBounds(schema, origin, path, violations);
        } else if (value instanceof List<?> schemas) {
            for (Object child : schemas) {
                if (child instanceof Map<?, ?> schema) {
                    outOfBounds(schema, origin, path, violations);
                }
            }
        }
    }

    private static void outOfBounds(
            Map<?, ?> schema, String origin, List<String> path, List<String> violations) {
        if (schema.containsKey(ANNOTATION)) {
            violations.add(
                    ANNOTATION
                            + " '"
                            + printable(String.valueOf(schema.get(ANNOTATION)))
                            + "' sits under '"
                            + origin
                            + "'"
                            + (path.isEmpty() ? "" : " of '" + String.join(".", path) + "'")
                            + "; only a property reached through 'properties' alone may be"
                            + " mirrored into a header");
            return;
        }
        for (Map.Entry<?, ?> entry : schema.entrySet()) {
            if (entry.getKey() instanceof String nested) {
                unreachable(nested, entry.getValue(), origin, path, violations);
            }
        }
    }

    /// Returns why one annotation is invalid, or empty when it is valid.
    private static Optional<String> annotation(Map<?, ?> node, List<String> path) {
        String property = String.join(".", path);
        if (!(node.get(ANNOTATION) instanceof String name)) {
            return Optional.of(ANNOTATION + " on '" + property + "' is not a string");
        }
        if (name.isEmpty()) {
            return Optional.of(ANNOTATION + " on '" + property + "' is empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!isTokenChar(c)) {
                return Optional.of(
                        ANNOTATION
                                + " '"
                                + printable(name)
                                + "' on '"
                                + property
                                + "' is not an HTTP field name: "
                                + (c < 0x20 || c == 0x7F
                                        ? "it contains a control character"
                                        : "'" + c + "' is not allowed in one"));
            }
        }
        Optional<String> type = mirrorableType(node.get("type"));
        if (type.isEmpty()) {
            return Optional.of(
                    "'"
                            + property
                            + "' is annotated with "
                            + ANNOTATION
                            + " but its type is "
                            + (node.containsKey("type") ? node.get("type") : "undeclared")
                            + "; only string, integer and boolean parameters may be mirrored"
                            + " into a header");
        }
        return Optional.empty();
    }

    /// Accepts `"string"`, `"integer"`, `"boolean"`, or a list naming exactly
    /// one of them alongside `"null"`. A `null` value sends no header, so a
    /// nullable primitive has a header form whenever it has a value.
    private static Optional<String> mirrorableType(Object type) {
        if (type instanceof String single) {
            return MIRRORABLE_TYPES.contains(single) ? Optional.of(single) : Optional.empty();
        }
        if (type instanceof List<?> union) {
            List<?> nonNull = union.stream().filter(entry -> !"null".equals(entry)).toList();
            if (nonNull.size() == 1 && nonNull.getFirst() instanceof String single) {
                return mirrorableType(single);
            }
        }
        return Optional.empty();
    }

    private static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || TOKEN_SYMBOLS.indexOf(c) >= 0;
    }

    /// Renders an operator-facing copy of a name, so a control character in it
    /// cannot rewrite the line the notice is printed on.
    private static String printable(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c < 0x20 || c == 0x7F ? String.format("\\u%04x", (int) c) : c);
        }
        return out.toString();
    }

    // --------------------------------------------------------------- calling

    private static Object valueAt(Map<String, Object> arguments, List<String> path) {
        Object current = arguments;
        for (String step : path) {
            if (!(current instanceof Map<?, ?> object)) {
                return null;
            }
            current = object.get(step);
        }
        return current;
    }

    private static String textOf(Object value, Mirror mirror) {
        return switch (value) {
            case String text -> text;
            case Boolean flag -> flag.toString();
            case Number number ->
                    integral(number)
                            .orElseThrow(() -> unmirrorable(mirror, describe(number, true)));
            default -> throw unmirrorable(mirror, describe(value, false));
        };
    }

    /// Returns the decimal form of an integral number inside the safe range.
    ///
    /// A JSON parser may hand `42` back as a `Double`, so the test is the value,
    /// not the Java type. The server compares integers numerically, which is
    /// what lets `42.0` in the body agree with `42` in the header.
    private static Optional<String> integral(Number number) {
        BigDecimal exact =
                switch (number) {
                    case BigDecimal decimal -> decimal;
                    case BigInteger integer -> new BigDecimal(integer);
                    case Double d when d.isNaN() || d.isInfinite() -> null;
                    case Float f when f.isNaN() || f.isInfinite() -> null;
                    case Double d -> BigDecimal.valueOf(d);
                    case Float f -> BigDecimal.valueOf(f.doubleValue());
                    default -> BigDecimal.valueOf(number.longValue());
                };
        if (exact == null || exact.signum() != 0 && exact.stripTrailingZeros().scale() > 0) {
            return Optional.empty();
        }
        BigInteger whole = exact.toBigIntegerExact();
        if (whole.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) > 0) {
            return Optional.empty();
        }
        return Optional.of(whole.toString());
    }

    private static String describe(Object value, boolean numeric) {
        if (numeric) {
            return "the number " + value + ", which is not an integer within ±" + MAX_SAFE_INTEGER;
        }
        return switch (value) {
            case Map<?, ?> ignored -> "an object";
            case List<?> ignored -> "a list";
            default -> "a " + value.getClass().getSimpleName();
        };
    }

    private static McpInvalidArgumentException unmirrorable(Mirror mirror, String got) {
        return new McpInvalidArgumentException(
                "Argument '"
                        + mirror.dotted()
                        + "' must be a string, a boolean or an integer: the server mirrors it"
                        + " into the "
                        + mirror.header()
                        + " header, and "
                        + got
                        + " has no header form");
    }

    private static boolean isPlain(String value) {
        if (value.startsWith(SENTINEL_OPEN) && value.endsWith(SENTINEL_CLOSE)) {
            return false;
        }
        if (!value.isEmpty()
                && (value.charAt(0) == ' ' || value.charAt(value.length() - 1) == ' ')) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
