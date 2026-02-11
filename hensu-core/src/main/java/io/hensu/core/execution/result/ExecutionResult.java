package io.hensu.core.execution.result;

import io.hensu.core.state.HensuState;

/// Represents the outcome of a workflow execution.
///
/// ### Permitted Subtypes
/// - {@link Completed} - Workflow reached an end node successfully
/// - {@link Paused} - Workflow paused at a node returning {@link ResultStatus#PENDING}
/// - {@link Rejected} - Workflow was rejected during human review
/// - {@link Failure} - Workflow failed due to an unrecoverable error
/// - {@link Success} - Intermediate success state (internal use)
///
/// @see io.hensu.core.execution.WorkflowExecutor for execution logic
public sealed interface ExecutionResult {

    /// Workflow completed by reaching an end node.
    ///
    /// @param finalState the workflow state at completion, not null
    /// @param exitStatus the status of end reached (SUCCESS, FAILURE, CANCEL), not null
    record Completed(HensuState finalState, ExitStatus exitStatus) implements ExecutionResult {

        /// Returns the final workflow state containing context and history.
        ///
        /// @return the final state, not null
        public HensuState getFinalState() {
            return finalState;
        }

        /// Returns the exit status indicating how the workflow ended.
        ///
        /// @return the exit status, not null
        public ExitStatus getExitStatus() {
            return exitStatus;
        }
    }

    /// Workflow paused at a node that returned {@link ResultStatus#PENDING}.
    ///
    /// The execution state is preserved and can be resumed later via
    /// {@link io.hensu.core.execution.WorkflowExecutor#executeFrom}.
    ///
    /// @param state the workflow state at pause point, not null
    record Paused(HensuState state) implements ExecutionResult {}

    /// Workflow was rejected during human review.
    ///
    /// @param reason explanation for the rejection, not null
    /// @param state the workflow state at rejection, not null
    record Rejected(String reason, HensuState state) implements ExecutionResult {

        /// Returns the reason for workflow rejection.
        ///
        /// @return the rejection reason, not null
        public String getReason() {
            return reason;
        }
    }

    /// Workflow failed due to an unrecoverable error.
    ///
    /// @param currentState the workflow state when failure occurred, not null
    /// @param e the exception that caused the failure, not null
    record Failure(HensuState currentState, IllegalStateException e) implements ExecutionResult {}

    /// Intermediate success state for internal processing.
    ///
    /// @param currentState the current workflow state, not null
    record Success(HensuState currentState) implements ExecutionResult {}
}
