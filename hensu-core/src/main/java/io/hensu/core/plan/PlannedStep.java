package io.hensu.core.plan;

import java.util.Map;
import java.util.Objects;

/// A single step within an execution {@link Plan}.
///
/// Each step pairs a zero-based index with a {@link PlanStepAction} that
/// describes what should happen: invoke a tool ({@link PlanStepAction.ToolCall})
/// or synthesise collected results ({@link PlanStepAction.Synthesize}).
///
/// ### Contracts
/// - **Precondition**: `index >= 0`; `action` must not be null
/// - **Postcondition**: all fields immutable after construction; `description`
///   normalised to empty string if null; `status` defaults to
///   {@link StepStatus#PENDING} if null
///
/// ### Usage
/// {@snippet :
/// PlannedStep fetch  = PlannedStep.pending(
///                         0, "get_order", Map.of("id", "{orderId}"), "Fetch order");
/// PlannedStep review = PlannedStep.synthesize(1, null, "Summarise all collected data");
/// }
///
/// @param index       zero-based position within the plan, must be >= 0
/// @param action      the action to perform, not null
/// @param description human-readable explanation of this step, never null after construction
/// @param status      current lifecycle status, never null after construction
/// @see PlanStepAction for the action hierarchy
/// @see Plan for the containing plan
public record PlannedStep(int index, PlanStepAction action, String description, StepStatus status) {

    /// Lifecycle status of a single plan step.
    public enum StepStatus {
        /// Step has not yet started.
        PENDING,
        /// Step is currently executing.
        EXECUTING,
        /// Step completed successfully.
        COMPLETED,
        /// Step failed with an error.
        FAILED,
        /// Step was skipped (e.g. after plan revision).
        SKIPPED
    }

    public PlannedStep {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        Objects.requireNonNull(action, "action must not be null");
        description = description != null ? description : "";
        status = status != null ? status : StepStatus.PENDING;
    }

    // -----------------------------------------------------------------------
    // Static factories
    // -----------------------------------------------------------------------

    /// Creates a pending tool-call step.
    ///
    /// @param index       zero-based step position, must be >= 0
    /// @param toolName    registered tool identifier, not null or blank
    /// @param arguments   tool parameters, may be null (treated as empty)
    /// @param description human-readable reason for this step, may be null
    /// @return new {@link StepStatus#PENDING} step, never null
    public static PlannedStep pending(
            int index, String toolName, Map<String, Object> arguments, String description) {
        return new PlannedStep(
                index,
                new PlanStepAction.ToolCall(toolName, arguments),
                description,
                StepStatus.PENDING);
    }

    /// Creates a pending tool-call step with no arguments.
    ///
    /// @param index       zero-based step position, must be >= 0
    /// @param toolName    registered tool identifier, not null or blank
    /// @param description human-readable reason for this step, may be null
    /// @return new {@link StepStatus#PENDING} step, never null
    public static PlannedStep simple(int index, String toolName, String description) {
        return pending(index, toolName, Map.of(), description);
    }

    /// Creates a pending synthesis step.
    ///
    /// The {@code agentId} is typically {@code null} at plan-creation time and
    /// enriched by the executor before execution begins.
    ///
    /// @param index   zero-based step position, must be >= 0
    /// @param agentId the agent to call for synthesis; may be {@code null} until enriched
    /// @param prompt  synthesis instruction, not null
    /// @return new {@link StepStatus#PENDING} step, never null
    public static PlannedStep synthesize(int index, String agentId, String prompt) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        return new PlannedStep(
                index, new PlanStepAction.Synthesize(agentId, prompt), prompt, StepStatus.PENDING);
    }

    // -----------------------------------------------------------------------
    // Convenience accessors (backward-compat for ToolCall steps)
    // -----------------------------------------------------------------------

    /// Returns the tool name for a {@link PlanStepAction.ToolCall} step.
    ///
    /// @return tool name, not null
    /// @throws IllegalStateException if this step's action is not a {@link PlanStepAction.ToolCall}
    public String toolName() {
        if (action instanceof PlanStepAction.ToolCall tc) {
            return tc.toolName();
        }
        throw new IllegalStateException("toolName() called on non-ToolCall step at index " + index);
    }

    /// Returns the argument map for a {@link PlanStepAction.ToolCall} step.
    ///
    /// @return immutable argument map, not null
    /// @throws IllegalStateException if this step's action is not a {@link PlanStepAction.ToolCall}
    public Map<String, Object> arguments() {
        if (action instanceof PlanStepAction.ToolCall tc) {
            return tc.arguments();
        }
        throw new IllegalStateException(
                "arguments() called on non-ToolCall step at index " + index);
    }

    /// Returns whether this step performs a tool call.
    ///
    /// @return {@code true} if {@link #action()} is a {@link PlanStepAction.ToolCall}
    public boolean isToolCall() {
        return action instanceof PlanStepAction.ToolCall;
    }

    /// Returns whether this step performs an agent synthesis.
    ///
    /// @return {@code true} if {@link #action()} is a {@link PlanStepAction.Synthesize}
    public boolean isSynthesize() {
        return action instanceof PlanStepAction.Synthesize;
    }

    // -----------------------------------------------------------------------
    // Status helpers
    // -----------------------------------------------------------------------

    /// Returns whether this step has reached a terminal status.
    ///
    /// @return {@code true} if status is COMPLETED, FAILED, or SKIPPED
    public boolean isFinished() {
        return status == StepStatus.COMPLETED
                || status == StepStatus.FAILED
                || status == StepStatus.SKIPPED;
    }

    /// Returns whether this step completed successfully.
    ///
    /// @return {@code true} if status is {@link StepStatus#COMPLETED}
    public boolean isSuccess() {
        return status == StepStatus.COMPLETED;
    }

    /// Returns a copy of this step with an updated status.
    ///
    /// @param newStatus the new status, not null
    /// @return new {@code PlannedStep} with the updated status, never null
    public PlannedStep withStatus(StepStatus newStatus) {
        return new PlannedStep(index, action, description, newStatus);
    }

    /// Returns a copy of this step with the synthesis agent id filled in.
    ///
    /// No-op for {@link PlanStepAction.ToolCall} steps.
    ///
    /// @param agentId the agent identifier to inject, not null
    /// @return updated step for Synthesize actions; this step unchanged for ToolCall, never null
    public PlannedStep withAgentId(String agentId) {
        if (action instanceof PlanStepAction.Synthesize s) {
            return new PlannedStep(index, s.withAgentId(agentId), description, status);
        }
        return this;
    }
}
