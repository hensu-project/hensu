package io.hensu.core.execution.parallel;

import io.hensu.core.execution.executor.NodeResult;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/// Result of executing a single branch in parallel execution.
///
/// Captures the outcome of one branch's agent execution together with
/// its extracted structured output ({@code yields}) and configured vote
/// weight. The yields map contains both domain variables declared via
/// {@link Branch#getYields()} and engine variables ({@code score},
/// {@code approved}, {@code recommendation}) extracted by the processor
/// pipeline.
///
/// @param branchId identifier of the branch that produced this result, not null
/// @param result the node execution result containing status and raw output, not null
/// @param yields extracted structured output awaiting promotion to shared state, never null
/// @param weight vote weight copied from {@link Branch#getWeight()}, positive
///
/// @see Branch for branch definition
/// @see io.hensu.core.execution.executor.ParallelNodeExecutor for execution
public record BranchResult(
        String branchId, NodeResult result, Map<String, Object> yields, double weight) {

    /// Canonical constructor – defensively copies yields map.
    ///
    /// Uses {@link Collections#unmodifiableMap} rather than {@link Map#copyOf}
    /// because extracted values may include JSON nulls.
    @SuppressWarnings("Java9CollectionFactory")
    public BranchResult {
        yields = yields != null ? Collections.unmodifiableMap(new HashMap<>(yields)) : Map.of();
    }

    /// Creates a result with no yields and default weight.
    ///
    /// @param branchId identifier of the branch, not null
    /// @param result the node execution result, not null
    public BranchResult(String branchId, NodeResult result) {
        this(branchId, result, Map.of(), 1.0);
    }

    /// Returns the identifier of the branch that produced this result.
    ///
    /// @return the branch ID, not null
    public String getBranchId() {
        return branchId;
    }

    /// Returns the execution result for this branch.
    ///
    /// @return the node result containing status and raw output, not null
    public NodeResult getResult() {
        return result;
    }
}
