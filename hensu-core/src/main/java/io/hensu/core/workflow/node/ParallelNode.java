package io.hensu.core.workflow.node;

import io.hensu.core.execution.parallel.Branch;
import io.hensu.core.execution.parallel.ConsensusConfig;
import io.hensu.core.execution.parallel.ConsensusStrategy;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.ArrayList;
import java.util.List;

/// Parallel node for executing multiple branches concurrently.
///
/// ### Branches can be
/// - Different agents executing the same task (for consensus)
/// - Same agent with different prompts (for exploration)
/// - Different agents with different tasks (for parallel work)
///
/// When consensus is configured, branch results are evaluated using the specified strategy to
/// determine the final output.
public final class ParallelNode extends Node {

    private final NodeType nodeType = NodeType.PARALLEL;
    private final List<Branch> branches;
    private final ConsensusConfig consensusConfig;
    private final List<TransitionRule> transitionRules;

    private ParallelNode(Builder builder) {
        super(builder.id);
        this.branches = List.copyOf(builder.branches);
        this.consensusConfig = builder.consensusConfig;
        this.transitionRules =
                builder.transitionRules != null ? List.copyOf(builder.transitionRules) : List.of();
    }

    @Override
    public String getRubricId() {
        return null; // Parallel nodes don't have a direct rubric; each branch may have one
    }

    @Override
    public NodeType getNodeType() {
        return nodeType;
    }

    public ConsensusConfig getConsensusConfig() {
        return consensusConfig;
    }

    public Branch[] getBranches() {
        return branches.toArray(new Branch[0]);
    }

    public List<Branch> getBranchesList() {
        return branches;
    }

    public List<TransitionRule> getTransitionRules() {
        return transitionRules;
    }

    public boolean hasConsensus() {
        return consensusConfig != null;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private final List<Branch> branches = new ArrayList<>();
        private ConsensusConfig consensusConfig;
        private List<TransitionRule> transitionRules;

        private Builder(String id) {
            this.id = id;
        }

        public Builder branch(Branch branch) {
            this.branches.add(branch);
            return this;
        }

        public Builder branch(String branchId, String agentId, String prompt) {
            this.branches.add(new Branch(branchId, agentId, prompt, null));
            return this;
        }

        public Builder branch(String branchId, String agentId, String prompt, String rubricId) {
            this.branches.add(new Branch(branchId, agentId, prompt, rubricId));
            return this;
        }

        public Builder branches(List<Branch> branches) {
            this.branches.addAll(branches);
            return this;
        }

        public Builder consensus(ConsensusConfig config) {
            this.consensusConfig = config;
            return this;
        }

        public Builder consensus(String judgeAgentId, ConsensusStrategy strategy) {
            this.consensusConfig = new ConsensusConfig(judgeAgentId, strategy, null);
            return this;
        }

        public Builder consensus(
                String judgeAgentId, ConsensusStrategy strategy, Double threshold) {
            this.consensusConfig = new ConsensusConfig(judgeAgentId, strategy, threshold);
            return this;
        }

        public Builder transitionRules(List<TransitionRule> rules) {
            this.transitionRules = rules;
            return this;
        }

        public ParallelNode build() {
            if (branches.isEmpty()) {
                throw new IllegalStateException("ParallelNode must have at least one branch");
            }
            return new ParallelNode(this);
        }
    }
}
