package io.hensu.core.execution.parallel;

import java.util.Comparator;
import java.util.Map;

/// Result of consensus evaluation across parallel branch executions.
///
/// Contains the consensus outcome, voting details, and the selected final output.
/// Provides utility methods for analyzing vote distributions.
///
/// @param consensusReached true if consensus was achieved according to the strategy
/// @param strategyUsed the strategy that was applied, not null
/// @param winningBranchId identifier of the branch with the winning output, may be null
/// @param finalOutput the selected output to use as the result, may be null
/// @param votes map of branch ID to vote details, not null
/// @param reasoning human-readable explanation of the consensus decision, may be null
///
/// @see ConsensusStrategy for available strategies
/// @see ConsensusEvaluator for evaluation logic
public record ConsensusResult(
        boolean consensusReached,
        ConsensusStrategy strategyUsed,
        String winningBranchId,
        Object finalOutput,
        Map<String, Vote> votes,
        String reasoning) {

    /// Represents a single vote from a branch execution.
    ///
    /// @param branchId identifier of the voting branch, not null
    /// @param agentId identifier of the agent that produced the vote, not null
    /// @param voteType the type of vote cast, not null
    /// @param score numerical score associated with the vote
    /// @param weight vote weight for weighted strategies
    /// @param output the branch's output that was evaluated, may be null
    public record Vote(
            String branchId,
            String agentId,
            VoteType voteType,
            double score,
            double weight,
            String output) {

        /// Checks if this is an approval vote.
        ///
        /// @return true if vote type is APPROVE
        public boolean isApprove() {
            return voteType == VoteType.APPROVE;
        }

        /// Checks if this is a rejection vote.
        ///
        /// @return true if vote type is REJECT
        public boolean isReject() {
            return voteType == VoteType.REJECT;
        }

        /// Checks if this is an abstention vote.
        ///
        /// @return true if vote type is ABSTAIN
        public boolean isAbstain() {
            return voteType == VoteType.ABSTAIN;
        }
    }

    /// Type of vote cast by a branch in consensus evaluation.
    public enum VoteType {
        /// Branch approves the decision
        APPROVE,
        /// Branch rejects the decision
        REJECT,
        /// Branch abstains from voting
        ABSTAIN
    }

    /// Counts the number of approval votes.
    ///
    /// @return count of votes with type APPROVE, never negative
    public long approveCount() {
        return votes.values().stream().filter(Vote::isApprove).count();
    }

    /// Counts the number of rejection votes.
    ///
    /// @return count of votes with type REJECT, never negative
    public long rejectCount() {
        return votes.values().stream().filter(Vote::isReject).count();
    }

    /// Calculates the total weighted approval score.
    ///
    /// @return sum of (score * weight) for all approval votes
    public double weightedApproveScore() {
        return votes.values().stream()
                .filter(Vote::isApprove)
                .mapToDouble(v -> v.score() * v.weight())
                .sum();
    }

    /// Calculates the total weighted rejection score.
    ///
    /// @return sum of (score * weight) for all rejection votes
    public double weightedRejectScore() {
        return votes.values().stream()
                .filter(Vote::isReject)
                .mapToDouble(v -> v.score() * v.weight())
                .sum();
    }

    /// Calculates the average score across all votes.
    ///
    /// @return arithmetic mean of all vote scores, or 0.0 if no votes
    public double averageScore() {
        return votes.values().stream().mapToDouble(Vote::score).average().orElse(0.0);
    }

    /// Returns the vote with the highest score.
    ///
    /// @return the highest-scoring vote, or null if no votes exist
    public Vote getHighestScoringVote() {
        return votes.values().stream().max(Comparator.comparingDouble(Vote::score)).orElse(null);
    }

    /// Creates a new builder for constructing ConsensusResult instances.
    ///
    /// @return a new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Builder for constructing {@link ConsensusResult} instances.
    public static final class Builder {
        private boolean consensusReached;
        private ConsensusStrategy strategyUsed;
        private String winningBranchId;
        private Object finalOutput;
        private Map<String, Vote> votes = Map.of();
        private String reasoning;

        /// Sets whether consensus was reached.
        ///
        /// @param reached true if consensus achieved
        /// @return this builder for chaining
        public Builder consensusReached(boolean reached) {
            this.consensusReached = reached;
            return this;
        }

        /// Sets the strategy that was used.
        ///
        /// @param strategy the consensus strategy, not null
        /// @return this builder for chaining
        public Builder strategyUsed(ConsensusStrategy strategy) {
            this.strategyUsed = strategy;
            return this;
        }

        /// Sets the winning branch identifier.
        ///
        /// @param branchId the winning branch ID, may be null
        /// @return this builder for chaining
        public Builder winningBranchId(String branchId) {
            this.winningBranchId = branchId;
            return this;
        }

        /// Sets the final output.
        ///
        /// @param output the selected output, may be null
        /// @return this builder for chaining
        public Builder finalOutput(Object output) {
            this.finalOutput = output;
            return this;
        }

        /// Sets the vote map.
        ///
        /// @param votes map of branch ID to vote, not null
        /// @return this builder for chaining
        public Builder votes(Map<String, Vote> votes) {
            this.votes = votes;
            return this;
        }

        /// Sets the reasoning explanation.
        ///
        /// @param reasoning human-readable explanation, may be null
        /// @return this builder for chaining
        public Builder reasoning(String reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        /// Builds the ConsensusResult instance.
        ///
        /// @return a new ConsensusResult, never null
        public ConsensusResult build() {
            return new ConsensusResult(
                    consensusReached, strategyUsed, winningBranchId, finalOutput, votes, reasoning);
        }
    }
}
