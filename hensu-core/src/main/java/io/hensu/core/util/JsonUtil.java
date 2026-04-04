package io.hensu.core.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/// JSON extraction utilities without external dependencies.
///
/// Uses a handwritten recursive descent parser for correctness across all JSON value types:
/// strings with escape sequences, numbers, booleans, null, nested objects, and arrays.
/// Nested objects and arrays are returned as structured types ({@code Map} and {@code List})
/// so state stores native data rather than serialized JSON strings.
///
/// @implNote **Dependency-free.** No regex, no external libraries. GraalVM native-image safe.
public final class JsonUtil {

    private JsonUtil() {}

    // Sentinel distinguishing "key present with JSON null" from "key absent".
    static final Object NULL_SENTINEL = new Object();

    // — Serialization ——————————————————————————————————————————————————————————

    /// Serializes a value to a JSON string.
    ///
    /// Handles {@code Map}, {@code Collection}, {@code String}, {@code Number},
    /// {@code Boolean}, and {@code null}. Maps and collections are serialized
    /// recursively. All other types fall back to {@link Object#toString()}.
    ///
    /// @implNote Dependency-free recursive serializer. No external libraries.
    /// GraalVM native-image safe.
    ///
    /// @param value the value to serialize, may be null
    /// @return JSON string representation, never null
    public static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return escapeJsonString(s);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(escapeJsonString(String.valueOf(entry.getKey())));
                sb.append(":");
                sb.append(toJson(entry.getValue()));
            }
            return sb.append("}").toString();
        }
        if (value instanceof Collection<?> coll) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : coll) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(item));
            }
            return sb.append("]").toString();
        }
        return escapeJsonString(value.toString());
    }

    /// Wraps a string in double quotes with JSON escape sequences.
    private static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append("\"").toString();
    }

    // — Public API ————————————————————————————————————————————————————————————

    /// Extracts a string field from a JSON object string.
    ///
    /// @param json      top-level JSON object string, not null
    /// @param fieldName the key to look up, not null
    /// @return the string value, or null if the key is absent or not a string
    public static String extractJsonField(String json, String fieldName) {
        Object value = extractJsonValue(json, fieldName);
        return value instanceof String s ? s : null;
    }

    /// Extracts a numeric field from a JSON object string.
    ///
    /// @param json      top-level JSON object string, not null
    /// @param fieldName the key to look up, not null
    /// @return the value as Double, or null if the key is absent or not a number
    public static Double extractJsonNumber(String json, String fieldName) {
        Object value = extractJsonValue(json, fieldName);
        return value instanceof Double d ? d : null;
    }

    /// Extracts a boolean field from a JSON object string.
    ///
    /// @param json      top-level JSON object string, not null
    /// @param fieldName the key to look up, not null
    /// @return the boolean value, or null if the key is absent or not a boolean
    public static Boolean extractJsonBoolean(String json, String fieldName) {
        Object value = extractJsonValue(json, fieldName);
        return value instanceof Boolean b ? b : null;
    }

    /// Extracts named parameters from an LLM output string and stores them in context.
    ///
    /// Finds the first balanced JSON object in the output, then extracts each named key.
    /// Nested objects are stored as {@code Map<String, Object>}, arrays as {@code List<Object>}.
    ///
    /// @param paramNames names of keys to extract, not null
    /// @param output     raw LLM output, not null
    /// @param context    mutable context map to populate, not null
    /// @param logger     logger for diagnostics, not null
    public static void extractOutputParams(
            List<String> paramNames, String output, Map<String, Object> context, Logger logger) {

        String json = extractJsonFromOutput(output);
        if (json == null) {
            logger.warning("Could not find JSON in output for parameter extraction");
            return;
        }

        for (String param : paramNames) {
            Object value = extractJsonValue(json, param);
            if (value == NULL_SENTINEL) {
                context.put(param, null);
                logger.info("Extracted parameter: " + param + " = null");
            } else if (value != null) {
                context.put(param, value);
                logger.info("Extracted parameter: " + param + " = " + value);
            } else {
                logger.warning("Parameter not found in JSON output: " + param);
            }
        }
    }

    /// Finds the first balanced `{...}` JSON object in text that may contain surrounding content
    /// such as markdown fences or explanatory prose.
    ///
    /// @param output raw text, not null
    /// @return the raw JSON object string, or null if none found
    public static String extractJsonFromOutput(String output) {
        int start = output.indexOf('{');
        if (start == -1) return null;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < output.length(); i++) {
            char c = output.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return output.substring(start, i + 1);
            }
        }
        return null;
    }

    /// Extracts any JSON value for the given key from a JSON object string.
    ///
    /// Return type depends on the JSON value:
    /// - `String`        — JSON string (escape sequences decoded)
    /// - `Double`        — JSON number
    /// - `Boolean`       — JSON boolean
    /// - `NULL_SENTINEL` — JSON null (key present with null value)
    /// - `Map<String, Object>` — nested JSON object (recursive)
    /// - `List<Object>`        — JSON array (recursive)
    /// - `null`          — key not found or parse error
    ///
    /// @param json the JSON object string, not null
    /// @param key  the field name to look up, not null
    /// @return typed value, or null if the key is absent
    public static Object extractJsonValue(String json, String key) {
        try {
            return new Parser(json).parseObject().get(key);
        } catch (Exception e) {
            return null;
        }
    }

    // — Recursive descent parser ——————————————————————————————————————————————

    /// Minimal recursive descent JSON parser.
    ///
    /// Parses a JSON object into `Map<String, Object>`. Value mapping:
    ///
    /// ```
    /// JSON string  ——> String  (escape sequences decoded)
    /// JSON number  ——> Double
    /// JSON boolean ——> Boolean
    /// JSON null    ——> NULL_SENTINEL
    /// JSON object  ——> Map<String, Object>  (recursive)
    /// JSON array   ——> List<Object>         (recursive)
    /// ```
    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        // — Navigation ————————————————————————————————————————————————————————

        void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        char peek() {
            return pos < src.length() ? src.charAt(pos) : 0;
        }

        void expect(char expected) {
            if (pos >= src.length() || src.charAt(pos) != expected)
                throw new IllegalStateException(
                        "Expected '" + expected + "' at pos " + pos + ", got '" + peek() + "'");
            pos++;
        }

        // — Value parsers —————————————————————————————————————————————————————

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                map.put(key, parseValue());
                skipWs();
                char next = peek();
                if (next == '}') {
                    pos++;
                    break;
                }
                if (next == ',') {
                    pos++;
                    continue;
                }
                throw new IllegalStateException("Expected ',' or '}' at pos " + pos);
            }
            return map;
        }

        List<Object> parseList() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(parseValue());
                skipWs();
                char next = peek();
                if (next == ']') {
                    pos++;
                    break;
                }
                if (next == ',') {
                    pos++;
                    continue;
                }
                throw new IllegalStateException("Expected ',' or ']' at pos " + pos);
            }
            return list;
        }

        Object parseValue() {
            skipWs();
            return switch (peek()) {
                case '"' -> parseString();
                case '{' -> parseObject();
                case '[' -> parseList();
                case 't', 'f' -> parseBoolean();
                case 'n' -> {
                    expectNull();
                    yield NULL_SENTINEL;
                }
                default -> {
                    char c = peek();
                    if (c == '-' || Character.isDigit(c)) yield parseNumber();
                    throw new IllegalStateException("Unexpected char '" + c + "' at pos " + pos);
                }
            };
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length())
                    throw new IllegalStateException("Unterminated string at pos " + pos);
                char c = src.charAt(pos++);
                if (c == '"') break;
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(esc);
                }
            }
            return sb.toString();
        }

        Double parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                do {
                    pos++;
                } while (pos < src.length() && Character.isDigit(src.charAt(pos)));
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            return Double.parseDouble(src.substring(start, pos));
        }

        Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalStateException("Expected boolean at pos " + pos);
        }

        void expectNull() {
            if (!src.startsWith("null", pos))
                throw new IllegalStateException("Expected 'null' at pos " + pos);
            pos += 4;
        }
    }
}
