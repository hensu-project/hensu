package io.hensu.core.execution.parallel;

/// Configuration for consensus evaluation in parallel execution.
///
/// Defines how to reach agreement across multiple branch results, including
/// the strategy to use and optional parameters like threshold values.
///
/// @param judgeAgentId agent ID for JUDGE_DECIDES strategy, may be null for other strategies
/// @param strategy the consensus strategy to apply, not null
/// @param threshold score threshold for weighted strategies, may be null (uses defaults)
///
/// @see ConsensusStrategy for available strategies
/// @see ConsensusEvaluator for evaluation logic
public record ConsensusConfig(String judgeAgentId, ConsensusStrategy strategy, Double threshold) {

    /// Returns the judge agent identifier for JUDGE_DECIDES strategy.
    ///
    /// @return the judge agent ID, may be null if using a non-judge strategy
    public String getJudgeAgentId() {
        return judgeAgentId;
    }

    /// Returns the consensus strategy to use.
    ///
    /// @return the strategy, not null
    public ConsensusStrategy getStrategy() {
        return strategy;
    }

    /// Returns the score threshold for consensus determination.
    ///
    /// @return the threshold value, may be null (evaluator uses defaults)
    public Double getThreshold() {
        return threshold;
    }
}
