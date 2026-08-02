package io.hensu.core.template;

import java.util.HashMap;
import java.util.Map;

/// Resolves template variables in strings. Pure utility, no dependencies.
public interface TemplateResolver {
    String resolve(String template, Map<String, Object> context);

    /// Resolves template variables in all string values of a payload map.
    ///
    /// Non-string values are passed through unchanged.
    ///
    /// @param payload the payload map with potential template placeholders
    /// @param context variable bindings for resolution
    /// @return new map with string values resolved, never null
    default Map<String, Object> resolvePayload(
            Map<String, Object> payload, Map<String, Object> context) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String stringValue) {
                resolved.put(entry.getKey(), resolve(stringValue, context));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }
}
