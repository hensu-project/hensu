package io.hensu.core.plan;

/// Observer for plan execution events.
///
/// Implement this interface to receive notifications during plan execution.
/// Common uses:
/// - Logging and audit trails
/// - Progress reporting to users
/// - Metrics collection
/// - Integration with monitoring systems
///
/// ### Thread Safety
/// @implNote Implementations must be thread-safe as events may be delivered
/// from multiple executor threads in parallel execution scenarios.
///
/// ### Usage
/// {@snippet :
/// PlanObserver logger = event -> {
///     switch (event) {
///         case PlanEvent.StepStarted s -> log.info("Starting step: " + s.step().toolName());
///         case PlanEvent.StepCompleted c -> log.info("Completed step: " + c.result().success());
///         case PlanEvent.PlanCompleted p -> log.info("Plan finished: " + p.success());
///         default -> { }
///     }
/// };
///
/// executor.addObserver(logger);
/// }
///
/// @see PlanEvent for event types
/// @see PlanExecutor for event emission
@FunctionalInterface
public interface PlanObserver {

    /// Called when a plan event occurs.
    ///
    /// Implementations should handle events quickly to avoid blocking
    /// plan execution. For expensive operations, consider queuing events
    /// for asynchronous processing.
    ///
    /// @param event the plan event, never null
    void onEvent(PlanEvent event);
}
