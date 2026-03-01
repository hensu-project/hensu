package io.hensu.core.plan;

import java.util.Map;
import java.util.Optional;

/// Registry for {@link StepHandler} instances, keyed by action type.
///
/// Mirrors {@link io.hensu.core.execution.executor.NodeExecutorRegistry} at
/// the plan-step level. Handlers are registered once at startup and looked
/// up by the concrete {@link PlanStepAction} subtype at execution time.
///
/// ### Permitted Subtypes
/// - {@link DefaultStepHandlerRegistry} — default mutable implementation
///
/// @implNote Thread-safety is implementation-specific. See individual
/// implementations for their concurrency guarantees.
///
/// @see StepHandler for the handler contract
/// @see DefaultStepHandlerRegistry for the default implementation
/// @see PlanExecutor for how dispatch is used during execution
public interface StepHandlerRegistry {

    /// Registers a handler for its declared action type.
    ///
    /// @apiNote **Side effects**: overwrites any previously registered handler
    /// for the same action type.
    ///
    /// @param <A>     the action type, inferred from the handler
    /// @param handler the handler to register, not null
    <A extends PlanStepAction> void register(StepHandler<A> handler);

    /// Returns the handler registered for the given action type.
    ///
    /// @param <A>        the action type
    /// @param actionType the class token, not null
    /// @return the registered handler, or empty if none registered
    <A extends PlanStepAction> Optional<StepHandler<A>> getHandler(Class<A> actionType);

    /// Dispatches a plan step to the appropriate handler.
    ///
    /// Resolves the handler for `step.action().getClass()` and invokes
    /// {@link StepHandler#handle(PlannedStep, PlanStepAction, Map)}.
    /// Returns a failure result if no handler is registered for the action type.
    ///
    /// @param step    the step to dispatch, not null
    /// @param context mutable execution context, not null
    /// @return the step result, never null
    StepResult dispatch(PlannedStep step, Map<String, Object> context);
}
