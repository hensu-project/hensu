package io.hensu.core.workflow.node;

import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;

/// Join node for awaiting and merging forked execution paths.
///
/// A {@link JoinNode} waits for specified forked executions to complete, then merges their
/// outputs according to the configured strategy.
///
/// ### Example usage in DSL
/// {@snippet lang=kotlin:
///  join("merge-results") {
///      await("parallel-processing")  // Reference to ForkNode ID
///      mergeStrategy = MergeStrategy.COLLECT_ALL
///      writes("merged_results")
///      onSuccess goto "process-merged"
///      onFailure goto "handle-error"
///  }
/// }
///
/// The merged output is stored in context under the variable(s) declared by {@code writes()}.
/// For single-output strategies (COLLECT_ALL, FIRST_SUCCESSFUL, CONCATENATE), exactly one
/// variable is required. For MERGE_MAPS, multiple variables are allowed – each branch export
/// is spread into its own parent state variable.
///
/// @see ForkNode
/// @see MergeStrategy
public final class JoinNode extends Node {

    private final NodeType nodeType = NodeType.JOIN;
    private final List<String> awaitTargets;
    private final MergeStrategy mergeStrategy;
    private final List<String> writes;
    private final List<String> exports;
    private final long timeoutMs;
    private final boolean failOnAnyError;
    private final List<TransitionRule> transitionRules;

    private JoinNode(Builder builder) {
        super(builder.id);
        this.awaitTargets = List.copyOf(builder.awaitTargets);
        this.mergeStrategy = builder.mergeStrategy;
        this.writes = List.copyOf(builder.writes);
        this.exports = builder.exports != null ? List.copyOf(builder.exports) : List.of();
        this.timeoutMs = builder.timeoutMs;
        this.failOnAnyError = builder.failOnAnyError;
        this.transitionRules =
                builder.transitionRules != null ? List.copyOf(builder.transitionRules) : List.of();
    }

    @Override
    public NodeType getNodeType() {
        return nodeType;
    }

    @Override
    public String getRubricId() {
        return null;
    }

    /// Get the list of ForkNode IDs to await.
    public List<String> getAwaitTargets() {
        return awaitTargets;
    }

    /// Get the strategy for merging outputs from forked paths.
    public MergeStrategy getMergeStrategy() {
        return mergeStrategy;
    }

    /// Get the state variable names where merged output will be stored.
    /// For single-output strategies: exactly one entry. For MERGE_MAPS: one or more entries.
    public List<String> getWrites() {
        return writes;
    }

    /// Get the whitelist of branch variable names allowed to cross the join boundary.
    /// If empty, all diffed variables are included (backward-compatible default).
    public List<String> getExports() {
        return exports;
    }

    /// Get the timeout in milliseconds for waiting on forked executions. 0 means no timeout (wait
    /// indefinitely).
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /// Whether to fail the join if any forked execution fails. If false, failed executions are
    /// included in results with error status.
    public boolean isFailOnAnyError() {
        return failOnAnyError;
    }

    /// Get transition rules for after join completes.
    public List<TransitionRule> getTransitionRules() {
        return transitionRules;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private List<String> awaitTargets = List.of();
        private MergeStrategy mergeStrategy = MergeStrategy.COLLECT_ALL;
        private List<String> writes = List.of();
        private List<String> exports = List.of();
        private long timeoutMs = 0;
        private boolean failOnAnyError = true;
        private List<TransitionRule> transitionRules;

        private Builder(String id) {
            this.id = id;
        }

        public Builder awaitTargets(List<String> targets) {
            this.awaitTargets = targets;
            return this;
        }

        public Builder awaitTargets(String... targets) {
            this.awaitTargets = List.of(targets);
            return this;
        }

        public Builder mergeStrategy(MergeStrategy strategy) {
            this.mergeStrategy = strategy;
            return this;
        }

        public Builder writes(List<String> writes) {
            this.writes = writes;
            return this;
        }

        public Builder writes(String... writes) {
            this.writes = List.of(writes);
            return this;
        }

        public Builder exports(List<String> exports) {
            this.exports = exports;
            return this;
        }

        public Builder exports(String... exports) {
            this.exports = List.of(exports);
            return this;
        }

        public Builder timeoutMs(long timeout) {
            this.timeoutMs = timeout;
            return this;
        }

        public Builder failOnAnyError(boolean fail) {
            this.failOnAnyError = fail;
            return this;
        }

        public Builder transitionRules(List<TransitionRule> rules) {
            this.transitionRules = rules;
            return this;
        }

        public JoinNode build() {
            if (awaitTargets.isEmpty()) {
                throw new IllegalStateException("JoinNode must have at least one await target");
            }
            return new JoinNode(this);
        }
    }
}
