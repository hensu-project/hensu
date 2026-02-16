package io.hensu.core.util;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Simple JSON extraction utilities without external dependencies.
///
/// Note: This is intentionally minimal to keep hensu-core dependency-free. For complex JSON
/// handling, use the langchain4j adapter or add a JSON library.
public final class JsonUtil {

    private JsonUtil() {}

    private static final Object NULL_SENTINEL = new Object();

    /// Extract a string field value from JSON. Handles escaped quotes within the value.
    public static String extractJsonField(String json, String fieldName) {
        // Pattern handles escaped quotes: "field": "value with \" inside"
        Pattern pattern =
                Pattern.compile(
                        "\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            // Unescape the value
            return unescapeJson(matcher.group(1));
        }
        return null;
    }

    /// Extract a numeric field value from JSON.
    public static Double extractJsonNumber(String json, String fieldName) {
        Pattern pattern =
                Pattern.compile(
                        "\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /// Extract a boolean field value from JSON.
    public static Boolean extractJsonBoolean(String json, String fieldName) {
        Pattern pattern =
                Pattern.compile(
                        "\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(true|false)",
                        Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Boolean.parseBoolean(matcher.group(1));
        }
        return null;
    }

    /// Extract specific parameters from JSON output and store them in context.
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

    /// Extract JSON object from output that may contain surrounding text. Handles nested braces
    /// correctly.
    public static String extractJsonFromOutput(String output) {
        int start = output.indexOf('{');
        if (start == -1) {
            return null;
        }

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
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return output.substring(start, i + 1);
                    }
                }
            }
        }

        return null;
    }

    /// Extract any JSON value (string, number, boolean, null). Returns the appropriate type.
    public static Object extractJsonValue(String json, String key) {
        // Try string first
        String stringValue = extractJsonField(json, key);
        if (stringValue != null) {
            return stringValue;
        }

        // Try number
        Double numberValue = extractJsonNumber(json, key);
        if (numberValue != null) {
            return numberValue;
        }

        // Try boolean
        Boolean boolValue = extractJsonBoolean(json, key);
        if (boolValue != null) {
            return boolValue;
        }

        // Try null
        Pattern nullPattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*null");
        if (nullPattern.matcher(json).find()) {
            return NULL_SENTINEL;
        }

        return null;
    }

    /// Unescape JSON string value.
    private static String unescapeJson(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
