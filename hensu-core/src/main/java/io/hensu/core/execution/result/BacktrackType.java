package io.hensu.core.execution.result;

/// Type of backtrack event in workflow execution.
///
/// Distinguishes between different causes of backtracking, allowing
/// for analysis of workflow behavior and debugging.
///
/// @see BacktrackEvent for backtrack recording
/// @see ExecutionHistory for history tracking
public enum BacktrackType {

    /// Backtrack initiated by human reviewer during review checkpoint.
    MANUAL,

    /// Backtrack triggered automatically by rubric evaluation failure.
    AUTOMATIC,

    /// Explicit jump defined in the workflow DSL (not triggered by failure).
    JUMP
}
