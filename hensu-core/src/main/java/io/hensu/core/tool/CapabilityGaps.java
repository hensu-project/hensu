package io.hensu.core.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// The reserved state keys through which a refused tool call reaches the graph.
///
/// The engine already distinguishes a refusal from a failure — {@link ToolCallStatus}
/// carries the vocabulary and the audit hooks already emit it. What was missing is that
/// none of it reached {@link io.hensu.core.state.HensuState}, so a workflow could not
/// route on "this run is blocked on capability" as opposed to "this node did poor work".
/// This class is that channel, and nothing more: the tool loop appends a record per
/// refusal, ordinary transitions read the keys, and no new transition type or decorator
/// is involved.
///
/// ### What counts as a gap
///
/// A gap is a capability the deployment did not grant, not a call that went wrong.
/// {@link ToolCallStatus#FAILURE} and {@link ToolCallStatus#TIMEOUT} are excluded because
/// the tool ran; {@link ToolCallStatus#VALIDATION_FAILED} is excluded because the
/// capability was present and the agent's arguments were wrong;
/// {@link ToolCallStatus#BUDGET_EXHAUSTED} describes a loop that ran long, which is
/// already visible as a node outcome. See {@link #isGap(ToolCallStatus)} for the exact set.
///
/// ### What a record carries
///
/// Tool name, the refusing gate, the node that asked, and the argument **key** names.
/// Values never appear: a parameter declared `secret:` in `commands.yaml` would otherwise
/// leak into a record that survives checkpoint, resume and the run summary, which is the
/// same redaction rule {@link ToolCallEvent#REDACTED} enforces for the audit trail.
///
/// ### Why plain maps
///
/// Records are written into the state context, which serializes through the tree-model
/// path with no reflective binding (see `.claude/rules/20-native-safety.md`). An
/// unmodifiable {@link Map} of strings and lists survives that round trip unchanged; a
/// record class would need a binding the module is not allowed to have.
///
/// @see ToolCallStatus for the taxonomy a gate refuses with
/// @see io.hensu.core.workflow.transition.ConditionTransition for the transition that
///     reads {@link #COUNT_KEY}
public final class CapabilityGaps {

    /// State key holding the appended gap records, in the order they were refused.
    ///
    /// The value is a `List<Map<String, Object>>`. Absent until the first refusal, so a
    /// run with no gaps carries no key rather than an empty list.
    public static final String STATE_KEY = "_capability_gaps";

    /// State key holding the number of records in {@link #STATE_KEY}, as an `Integer`.
    ///
    /// Conditions coerce scalars only, so this is the key a workflow routes on:
    /// `onCondition("_capability_gap_count") { whenValue greaterThanOrEqual 1 goto "review" }`.
    /// It is derived, never authored — {@link #record} keeps the two keys in step.
    public static final String COUNT_KEY = "_capability_gap_count";

    /// Record field holding the tool name the agent asked for.
    public static final String FIELD_TOOL = "tool";

    /// Record field holding the refusing gate, which is the {@link ToolCallStatus}
    /// constant's name.
    public static final String FIELD_GATE = "gate";

    /// Record field holding the id of the node whose agent asked.
    public static final String FIELD_NODE = "node";

    /// Record field holding the argument key names, never their values.
    public static final String FIELD_ARGUMENT_KEYS = "argument_keys";

    /// The keys this class owns, which no other writer may produce or clear.
    ///
    /// The engine writes them during a node's execution, not from the agent's output, so
    /// the post-execution passes that clear and re-extract a node's declared variables
    /// must skip them. Without that, the count would be wiped between being written and
    /// being routed on — and an agent emitting the key in its own JSON could erase the
    /// record of what it was refused.
    public static final Set<String> RESERVED_KEYS = Set.of(STATE_KEY, COUNT_KEY);

    private static final Set<ToolCallStatus> GAP_STATUSES =
            Set.of(
                    ToolCallStatus.UNKNOWN_TOOL,
                    ToolCallStatus.DENIED,
                    ToolCallStatus.SANDBOX_UNAVAILABLE,
                    ToolCallStatus.SANDBOX_REFUSED);

    private CapabilityGaps() {}

    /// Returns true when a status describes a capability the deployment did not grant.
    ///
    /// @param status outcome of a tool call, may be null
    /// @return true if the outcome belongs in {@link #STATE_KEY}
    public static boolean isGap(ToolCallStatus status) {
        return status != null && GAP_STATUSES.contains(status);
    }

    /// Appends one gap record to the context, keeping {@link #COUNT_KEY} in step.
    ///
    /// No-op when the status is not a gap, so callers may hand over every outcome and let
    /// this method decide. The context map is mutated in place because it is the live
    /// state map the workflow routes on.
    ///
    /// @param context live state context, not null
    /// @param nodeId id of the node whose agent asked, not null
    /// @param toolName tool the agent asked for, not null
    /// @param status outcome of the call, may be null
    /// @param argumentKeys argument key names, may be null or empty
    /// @throws NullPointerException if context, nodeId or toolName is null
    public static void record(
            Map<String, Object> context,
            String nodeId,
            String toolName,
            ToolCallStatus status,
            Collection<String> argumentKeys) {

        if (!isGap(status)) {
            return;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(FIELD_TOOL, toolName);
        entry.put(FIELD_GATE, status.name());
        entry.put(FIELD_NODE, nodeId);
        entry.put(
                FIELD_ARGUMENT_KEYS, argumentKeys == null ? List.of() : List.copyOf(argumentKeys));

        List<Object> recorded = new ArrayList<>(existing(context));
        recorded.add(Collections.unmodifiableMap(entry));

        context.put(STATE_KEY, Collections.unmodifiableList(recorded));
        context.put(COUNT_KEY, recorded.size());
    }

    /// Reads the gap records out of a state context.
    ///
    /// @param context state context, may be null
    /// @return the records in refusal order, empty when the run recorded none
    public static List<Map<String, Object>> of(Map<String, Object> context) {
        List<Object> raw = existing(context);
        List<Map<String, Object>> records = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (element instanceof Map<?, ?> map) {
                records.add((Map<String, Object>) map);
            }
        }
        return List.copyOf(records);
    }

    /// Returns the value of a record field as text, or `"unknown"` when it is absent.
    ///
    /// Records survive serialization as plain maps, so a resumed run may carry a field a
    /// newer writer would have set. Rendering a placeholder keeps the run summary honest
    /// instead of printing `null`.
    ///
    /// @param record one gap record, not null
    /// @param field one of the `FIELD_` constants, not null
    /// @return the field's text, never null
    public static String text(Map<String, Object> record, String field) {
        Object value = record.get(field);
        return value == null ? "unknown" : String.valueOf(value);
    }

    private static List<Object> existing(Map<String, Object> context) {
        if (context == null) {
            return List.of();
        }
        return context.get(STATE_KEY) instanceof List<?> list ? (List<Object>) list : List.of();
    }
}
