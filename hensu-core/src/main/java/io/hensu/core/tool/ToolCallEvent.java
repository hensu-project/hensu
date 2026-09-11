package io.hensu.core.tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Audit record emitted immediately before a tool is invoked.
///
/// Paired with a {@link ToolResultEvent} for every outcome, including tools the
/// agent hallucinated and invocations that threw: an unattended run must leave a
/// complete trail of what the agent asked to run.
///
/// The record is the security-relevant half of that trail, so it is bounded and
/// self-contained. Argument values are truncated at {@link #MAX_ARG_CHARS} and
/// copied recursively, meaning a provider that later mutates a nested map or
/// list cannot retroactively edit what the audit says was requested. Values of
/// parameters declared {@linkplain ToolDefinition.ParameterDef#sensitive()
/// sensitive} are replaced with {@link #REDACTED} by the tool loop before the
/// event is constructed, so no sink ever receives the secret.
///
/// @param nodeId identifier of the node whose agent requested the tool, not null
/// @param agentId identifier of the requesting agent, not null
/// @param toolName the tool the agent asked for, not null
/// @param arguments arguments the agent supplied, not null (may be empty, values may be null)
/// @param occurredAt when the request was made, not null
/// @see ToolResultEvent for the matching outcome record
/// @see io.hensu.core.execution.ExecutionListener#onToolCall
public record ToolCallEvent(
        String nodeId,
        String agentId,
        String toolName,
        Map<String, Object> arguments,
        Instant occurredAt) {

    /// Maximum number of characters retained per argument value; longer values are truncated.
    public static final int MAX_ARG_CHARS = 1024;

    /// Replacement written in place of a sensitive parameter's value.
    public static final String REDACTED = "[redacted]";

    private static final String TRUNCATION_MARKER = "… [truncated]";

    /// Compact constructor bounding and deep-copying the arguments.
    ///
    /// @implNote The copy tolerates null values – agent arguments originate from
    /// model-produced JSON, where an explicit null is legal – so `Map.copyOf` is
    /// not usable here.
    public ToolCallEvent {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        arguments = arguments != null ? copyBounded(arguments) : Map.of();
    }

    /// Creates a request record timestamped now.
    ///
    /// @param nodeId identifier of the requesting node, not null
    /// @param agentId identifier of the requesting agent, not null
    /// @param toolName the requested tool, not null
    /// @param arguments arguments the agent supplied, may be null
    /// @return new event, never null
    public static ToolCallEvent now(
            String nodeId, String agentId, String toolName, Map<String, Object> arguments) {
        return new ToolCallEvent(nodeId, agentId, toolName, arguments, Instant.now());
    }

    private static Map<String, Object> copyBounded(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, boundedValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object boundedValue(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> truncate(text);
            case Map<?, ?> map -> {
                Map<Object, Object> copy = new LinkedHashMap<>();
                map.forEach((key, nested) -> copy.put(key, boundedValue(nested)));
                yield Collections.unmodifiableMap(copy);
            }
            case List<?> list -> {
                List<Object> copy = new ArrayList<>(list.size());
                list.forEach(element -> copy.add(boundedValue(element)));
                yield Collections.unmodifiableList(copy);
            }
            default -> value;
        };
    }

    private static String truncate(String text) {
        return text.length() > MAX_ARG_CHARS
                ? text.substring(0, MAX_ARG_CHARS) + TRUNCATION_MARKER
                : text;
    }
}
