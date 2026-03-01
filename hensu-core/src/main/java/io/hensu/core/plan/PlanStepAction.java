package io.hensu.core.plan;

import java.util.Map;
import java.util.Objects;

/// Sealed hierarchy representing an action within a plan step.
///
/// A step in an execution plan performs one of two actions:
///
/// ### Permitted Subtypes
/// - {@link ToolCall} — invoke a registered tool with arguments
/// - {@link Synthesize} — ask the node's agent to synthesize collected results
///
/// The planner expresses intent via this hierarchy; the
/// {@link StepHandlerRegistry} dispatches execution to the matching handler.
///
/// @implNote Thread-safe. All permitted subtypes are immutable records.
///
/// @see StepHandler for per-action-type execution
/// @see StepHandlerRegistry for dispatch
public sealed interface PlanStepAction permits PlanStepAction.ToolCall, PlanStepAction.Synthesize {

    /// Invokes a registered tool with the supplied arguments.
    ///
    /// @param toolName  the registered tool identifier, not null or blank
    /// @param arguments key-value parameters forwarded to the tool; normalised to
    ///                  an immutable copy on construction, never null
    record ToolCall(String toolName, Map<String, Object> arguments) implements PlanStepAction {

        public ToolCall {
            Objects.requireNonNull(toolName, "toolName must not be null");
            if (toolName.isBlank()) {
                throw new IllegalArgumentException("toolName must not be blank");
            }
            arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
        }
    }

    /// Asks the node's configured agent to synthesize collected tool results.
    ///
    /// The {@code agentId} is left {@code null} by the planner and enriched
    /// by {@link io.hensu.core.execution.executor.AgenticNodeExecutor} after
    /// plan creation, using the node's own agent identifier.
    ///
    /// @param agentId the agent to call for synthesis; may be {@code null} until
    ///                enriched by the executor
    /// @param prompt  the synthesis instruction shown to the agent, not null
    record Synthesize(String agentId, String prompt) implements PlanStepAction {

        /// Returns a copy with the agent identifier filled in.
        ///
        /// @param resolvedAgentId the agent id to attach, not null
        /// @return new {@code Synthesize} with the agent id set, never null
        public Synthesize withAgentId(String resolvedAgentId) {
            Objects.requireNonNull(resolvedAgentId, "resolvedAgentId must not be null");
            return new Synthesize(resolvedAgentId, prompt);
        }
    }
}
