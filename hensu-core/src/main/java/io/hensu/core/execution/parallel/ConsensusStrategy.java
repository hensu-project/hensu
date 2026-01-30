package io.hensu.core.execution.parallel;

/// Strategies for reaching consensus across parallel branch results.
///
/// Each strategy defines different rules for determining whether consensus
/// is reached and which output to select as the final result.
///
/// @see ConsensusConfig for configuration
/// @see ConsensusEvaluator for evaluation implementation
public enum ConsensusStrategy {

    /// Consensus reached when more than half of branches approve.
    ///
    /// The winning output is selected from the highest-scoring approve vote.
    MAJORITY_VOTE,

    /// Consensus reached only when all branches approve.
    ///
    /// A single rejection or abstention prevents consensus.
    UNANIMOUS,

    /// Consensus based on weighted vote scores.
    ///
    /// Each branch's vote is weighted by its configured weight or score.
    /// Consensus is reached when the weighted approval ratio exceeds the threshold.
    WEIGHTED_VOTE,

    /// Consensus determined by a designated judge agent.
    ///
    /// Requires `judgeAgentId` in {@link ConsensusConfig}. The judge reviews
    /// all branch outputs and makes the final decision.
    JUDGE_DECIDES
}
