package io.hensu.core.execution.result;

import io.hensu.core.workflow.node.EndNode;

/// Workflow execution outcome type for end nodes.
///
/// Indicates how the workflow ended when reaching an end node.
///
/// @see EndNode for usage
/// @see io.hensu.core.execution.result.ExecutionResult for result handling
public enum ExitStatus {

    /// Workflow completed successfully, achieving its goal.
    SUCCESS,

    /// Workflow failed due to an unrecoverable error or rejection.
    FAILURE,

    /// Workflow was canceled before completion (user-initiated or timeout).
    CANCEL
}
