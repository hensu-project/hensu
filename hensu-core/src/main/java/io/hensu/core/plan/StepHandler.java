package io.hensu.core.plan;

import java.util.Map;

/// Handler for a single {@link PlanStepAction} type within plan execution.
///
/// Mirrors the {@link io.hensu.core.execution.executor.NodeExecutor} pattern at
/// the plan-step level. Each handler is responsible for exactly one action type
/// and is registered with a {@link StepHandlerRegistry} that routes dispatches
/// at runtime.
///
/// ### Contracts
/// - **Precondition**: `step` and `action` must not be null; `context` must not be null
/// - **Postcondition**: returns a non-null {@link StepResult}; never throws
///
/// @implNote Thread-safe. Implementations must be stateless or use thread-safe
/// state, as a single handler instance is shared across concurrent plan executions.
///
/// @param <A> the {@link PlanStepAction} subtype this handler processes
/// @see StepHandlerRegistry for registration and dispatch
/// @see PlanExecutor for the execution loop
public interface StepHandler<A extends PlanStepAction> {

    /// Returns the action type this handler processes.
    ///
    /// @return action class token, not null
    Class<A> getActionType();

    /// Executes a single plan step.
    ///
    /// @param step    the full step record providing index and description, not null
    /// @param action  the typed action to perform (matches {@link #getActionType()}), not null
    /// @param context mutable execution context shared across all steps in the plan,
    ///                not null; may be read and written by the handler
    /// @return the step result, never null
    StepResult handle(PlannedStep step, A action, Map<String, Object> context);
}
