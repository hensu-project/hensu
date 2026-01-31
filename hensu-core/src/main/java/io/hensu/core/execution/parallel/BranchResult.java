package io.hensu.core.execution.parallel;

import io.hensu.core.execution.executor.NodeResult;

/// Result of executing a single branch in parallel execution.
///
/// Captures the outcome of one branch's agent execution, including the
/// branch identifier for correlation with the original branch definition.
///
/// @param branchId identifier of the branch that produced this result, not null
/// @param result the node execution result containing status and output, not null
///
/// @see Branch for branch definition
/// @see io.hensu.core.execution.executor.ParallelNodeExecutor for execution
public record BranchResult(String branchId, NodeResult result) {

    /// Returns the identifier of the branch that produced this result.
    ///
    /// @return the branch ID, not null
    public String getBranchId() {
        return branchId;
    }

    /// Returns the execution result for this branch.
    ///
    /// @return the node result containing status and output, not null
    public NodeResult getResult() {
        return result;
    }
}
