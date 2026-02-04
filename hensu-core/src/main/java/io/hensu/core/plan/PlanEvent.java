package io.hensu.core.plan;

import io.hensu.core.plan.Plan.PlanSource;
import java.time.Instant;
import java.util.List;

/// Events emitted during plan execution for observability.
///
/// Plan events enable:
/// - Progress monitoring during execution
/// - Audit logging of plan activities
/// - Debugging plan failures
/// - Integration with external monitoring systems
///
/// ### Event Flow
/// ```
/// PlanCreated → StepStarted → StepCompleted → ... → PlanCompleted
///                    ↓
///              (on failure)
///                    ↓
///              PlanRevised → StepStarted → ...
/// ```
///
/// @see PlanObserver for event consumers
/// @see PlanExecutor for event emission
public sealed interface PlanEvent {

    /// Returns the plan identifier.
    ///
    /// @return unique plan ID, never null
    String planId();

    /// Returns when the event occurred.
    ///
    /// @return event timestamp, never null
    Instant timestamp();

    /// Emitted when a plan is created.
    ///
    /// @param planId unique plan identifier
    /// @param nodeId workflow node the plan belongs to
    /// @param source how the plan was created (STATIC or LLM_GENERATED)
    /// @param steps the planned steps
    /// @param timestamp when the plan was created
    record PlanCreated(
            String planId,
            String nodeId,
            PlanSource source,
            List<PlannedStep> steps,
            Instant timestamp)
            implements PlanEvent {

        /// Creates event with current timestamp.
        public static PlanCreated now(Plan plan) {
            return new PlanCreated(
                    plan.id(), plan.nodeId(), plan.source(), plan.steps(), Instant.now());
        }
    }

    /// Emitted when a step begins execution.
    ///
    /// @param planId the plan being executed
    /// @param step the step starting
    /// @param timestamp when execution started
    record StepStarted(String planId, PlannedStep step, Instant timestamp) implements PlanEvent {

        /// Creates event with current timestamp.
        public static StepStarted now(String planId, PlannedStep step) {
            return new StepStarted(planId, step, Instant.now());
        }
    }

    /// Emitted when a step completes (success or failure).
    ///
    /// @param planId the plan being executed
    /// @param result the step execution result
    /// @param timestamp when execution completed
    record StepCompleted(String planId, StepResult result, Instant timestamp) implements PlanEvent {

        /// Creates event with current timestamp.
        public static StepCompleted now(String planId, StepResult result) {
            return new StepCompleted(planId, result, Instant.now());
        }
    }

    /// Emitted when a plan is revised after failure.
    ///
    /// @param planId the plan being revised
    /// @param reason why revision was needed
    /// @param oldSteps the original steps
    /// @param newSteps the revised steps
    /// @param timestamp when revision occurred
    record PlanRevised(
            String planId,
            String reason,
            List<PlannedStep> oldSteps,
            List<PlannedStep> newSteps,
            Instant timestamp)
            implements PlanEvent {

        /// Creates event with current timestamp.
        public static PlanRevised now(
                String planId,
                String reason,
                List<PlannedStep> oldSteps,
                List<PlannedStep> newSteps) {
            return new PlanRevised(planId, reason, oldSteps, newSteps, Instant.now());
        }
    }

    /// Emitted when plan execution completes.
    ///
    /// @param planId the completed plan
    /// @param success whether all steps succeeded
    /// @param output final output from the plan
    /// @param timestamp when execution completed
    record PlanCompleted(String planId, boolean success, String output, Instant timestamp)
            implements PlanEvent {

        /// Creates success event with current timestamp.
        public static PlanCompleted success(String planId, String output) {
            return new PlanCompleted(planId, true, output, Instant.now());
        }

        /// Creates failure event with current timestamp.
        public static PlanCompleted failure(String planId, String error) {
            return new PlanCompleted(planId, false, error, Instant.now());
        }
    }

    /// Emitted when plan execution is paused for checkpointing.
    ///
    /// @param planId the paused plan
    /// @param reason why execution was paused
    /// @param checkpointId identifier for the checkpoint
    /// @param timestamp when pause occurred
    record PlanPaused(String planId, String reason, String checkpointId, Instant timestamp)
            implements PlanEvent {

        /// Creates event with current timestamp.
        public static PlanPaused now(String planId, String reason, String checkpointId) {
            return new PlanPaused(planId, reason, checkpointId, Instant.now());
        }
    }
}
