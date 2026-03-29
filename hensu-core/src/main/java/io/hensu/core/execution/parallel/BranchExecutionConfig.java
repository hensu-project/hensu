package io.hensu.core.execution.parallel;

import java.util.List;

/// Execution metadata for a parallel branch, carried on {@link
/// io.hensu.core.execution.executor.ExecutionContext} rather than in the
/// user-visible state context map.
///
/// This record exists to keep engine-internal flags out of
/// {@code state.getContext()} – the mutable map that flows between nodes and
/// is visible to agents. Without it, flags like "is this a consensus branch?"
/// would pollute the user data bus and leak to the LLM.
///
/// @param consensusBranch true if the parent parallel node has consensus config
/// @param consensusStrategy the consensus strategy in use, null if no consensus
/// @param yields ordered list of yield field names declared by the branch, never null
///
/// @see io.hensu.core.execution.executor.ExecutionContext#getBranchConfig()
/// @see io.hensu.core.execution.enricher.ScoreVariableInjector
/// @see io.hensu.core.execution.enricher.ApprovalVariableInjector
/// @see io.hensu.core.execution.enricher.RecommendationVariableInjector
/// @see io.hensu.core.execution.enricher.YieldsVariableInjector
public record BranchExecutionConfig(
        boolean consensusBranch, ConsensusStrategy consensusStrategy, List<String> yields) {

    /// Canonical constructor – defensively copies yields list.
    public BranchExecutionConfig {
        yields = yields != null ? List.copyOf(yields) : List.of();
    }

    /// Returns true if this branch needs self-scoring engine variables
    /// (score, approved, recommendation). JUDGE_DECIDES branches do not
    /// self-score – the judge agent makes the decision.
    public boolean needsSelfScoring() {
        return consensusBranch && consensusStrategy != ConsensusStrategy.JUDGE_DECIDES;
    }
}
