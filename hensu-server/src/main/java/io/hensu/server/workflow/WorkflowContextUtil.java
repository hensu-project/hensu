package io.hensu.server.workflow;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/// Shared utility for filtering internal keys from workflow execution context.
///
/// Internal keys are prefixed with underscore ({@code _}) and carry system metadata
/// such as tenant ID, execution ID, and routing state. This utility strips them to
/// produce a public-facing context safe for API responses and SSE events.
final class WorkflowContextUtil {

    private WorkflowContextUtil() {}

    /// Filters internal underscore-prefixed keys from the given context map.
    ///
    /// Returns a new map containing only entries whose keys do not start with {@code _}.
    /// Tolerates null values in the source map (Jackson may deserialize JSON {@code null}
    /// into Java {@code null}).
    ///
    /// @param context the full execution context, not null
    /// @return filtered context with only public keys, never null
    static Map<String, Object> publicContext(Map<String, Object> context) {
        var result = new HashMap<String, Object>();
        for (var entry : context.entrySet()) {
            if (!entry.getKey().startsWith("_")) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
