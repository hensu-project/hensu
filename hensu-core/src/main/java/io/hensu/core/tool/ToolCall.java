package io.hensu.core.tool;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/// One tool invocation as the agent loop issues it.
///
/// The key identifies the call for as long as the execution lives: it is the journal
/// position of the node attempt that made it, followed by the call's sequence number
/// within the attempt. A recovered run replays the attempt deterministically, so it
/// issues the same keys in the same order; a runtime that records outcomes uses the
/// key to hand back the original outcome instead of invoking the tool again.
///
/// @param key the call's identity within its execution, not null
/// @param toolName the tool to invoke, not null
/// @param arguments the arguments the agent supplied, never null (may be empty)
public record ToolCall(String key, String toolName, Map<String, Object> arguments) {

    public ToolCall {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        //noinspection Java9CollectionFactory – argument values may be null
        arguments =
                arguments != null
                        ? Collections.unmodifiableMap(new HashMap<>(arguments))
                        : Map.of();
    }

    /// Builds the key of the `sequence`-th call of the attempt at `position`.
    ///
    /// @param position the node attempt's journal position, not null
    /// @param sequence the call's one-based sequence within the attempt
    /// @return the key, never null
    public static String key(String position, int sequence) {
        return position + "#" + sequence;
    }

    /// Qualifies a call key with its execution, giving the call an identity that is
    /// unique across every execution of a tenant and stable across recovery.
    ///
    /// This is the identity every consumer outside the execution sees: audit sinks pair
    /// a request with its outcome on it and record it once, and a runtime that hands
    /// calls to an external executor may give it out as the key that executor
    /// de-duplicates on.
    ///
    /// @param executionId the execution that made the call, not null
    /// @param key the call's key within the execution, not null
    /// @return the call's identity, never null
    public static String id(String executionId, String key) {
        return executionId + "/" + key;
    }
}
