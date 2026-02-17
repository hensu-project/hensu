package io.hensu.core.execution.result;

import java.util.Map;

/// Instruction for automatic backtracking triggered by rubric evaluation.
///
/// When a node's output fails rubric evaluation, the executor determines
/// an appropriate backtrack target based on the score severity. This record
/// encapsulates the target node and any context updates to inject.
///
/// @param targetNode identifier of the node to backtrack to, not null
/// @param contextUpdates map of context variables to update before re-execution, not null
///
/// @see io.hensu.core.execution.WorkflowExecutor for backtrack logic
/// @see BacktrackEvent for recording backtrack history
public record AutoBacktrack(String targetNode, Map<String, Object> contextUpdates) {

    /// Returns the identifier of the node to backtrack to.
    ///
    /// @return the target node ID, not null
    public String getTargetNode() {
        return targetNode;
    }

    /// Returns the context updates to apply before re-execution.
    ///
    /// These updates typically include:
    /// - `backtrack_reason`: explanation of why backtrack occurred
    /// - `failed_criteria`: list of rubric criteria that failed
    /// - `improvement_suggestions`: hints for the re-executed node
    /// - `recommendations`: combined self-evaluation and rubric suggestions
    ///
    /// @return map of context key-value pairs, not null
    public Map<String, Object> getContextUpdates() {
        return contextUpdates;
    }
}
