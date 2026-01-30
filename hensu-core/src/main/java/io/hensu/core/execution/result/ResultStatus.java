package io.hensu.core.execution.result;

/// Status of a node execution result.
///
/// Indicates the outcome of executing a single workflow node, used by
/// transition rules to determine the next node in the workflow graph.
///
/// @see io.hensu.core.execution.executor.NodeResult for usage
/// @see io.hensu.core.workflow.transition.TransitionRule for transition logic
public enum ResultStatus {

    /// Node executed successfully and produced valid output.
    SUCCESS,

    /// Node execution failed due to an error or invalid output.
    FAILURE,

    /// Node execution is pending completion (used for async operations).
    PENDING,

    /// Node is ending the workflow execution.
    END
}
