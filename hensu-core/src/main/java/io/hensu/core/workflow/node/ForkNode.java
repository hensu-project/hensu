package io.hensu.core.workflow.node;

import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import java.util.Map;

/// Fork node for spawning parallel execution paths.
///
/// A ForkNode executes multiple target nodes concurrently using virtual threads. Each target
/// can be a node ID or subworkflow ID. The fork creates separate execution contexts for each target
/// and stores futures for the JoinNode to await.
///
/// ### Example usage in DSL
/// {@snippet lang=kotlin:
///  fork("parallel-processing") {
///      targets("process-a", "process-b", "process-c")
///      onComplete goto "join-results"
///  }
/// }
///
/// Fork outputs are stored in context as "{forkNodeId}_futures" for JoinNode to consume.
///
/// @see JoinNode
public final class ForkNode extends Node {

    private final NodeType nodeType = NodeType.FORK;
    private final List<String> targets;
    private final Map<String, Object> targetConfigs;
    private final List<TransitionRule> transitionRules;
    private final boolean waitForAll;

    private ForkNode(Builder builder) {
        super(builder.id);
        this.targets = List.copyOf(builder.targets);
        this.targetConfigs =
                builder.targetConfigs != null ? Map.copyOf(builder.targetConfigs) : Map.of();
        this.transitionRules =
                builder.transitionRules != null ? List.copyOf(builder.transitionRules) : List.of();
        this.waitForAll = builder.waitForAll;
    }

    @Override
    public NodeType getNodeType() {
        return nodeType;
    }

    @Override
    public String getRubricId() {
        return null;
    }

    /// Get the list of target node IDs to execute in parallel.
    public List<String> getTargets() {
        return targets;
    }

    /// Get optional per-target configuration overrides. Key is target node ID, value is config map.
    public Map<String, Object> getTargetConfigs() {
        return targetConfigs;
    }

    /// Get transition rules for after fork completes spawning.
    public List<TransitionRule> getTransitionRules() {
        return transitionRules;
    }

    /// Whether the fork should wait for all targets to complete before transitioning. If false,
    /// transitions immediately after spawning (fire-and-forget pattern). Default is false - use
    /// {@link JoinNode} to wait for completion.
    public boolean isWaitForAll() {
        return waitForAll;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private List<String> targets = List.of();
        private Map<String, Object> targetConfigs;
        private List<TransitionRule> transitionRules;
        private boolean waitForAll = false;

        private Builder(String id) {
            this.id = id;
        }

        public Builder targets(List<String> targets) {
            this.targets = targets;
            return this;
        }

        public Builder targets(String... targets) {
            this.targets = List.of(targets);
            return this;
        }

        public Builder targetConfigs(Map<String, Object> configs) {
            this.targetConfigs = configs;
            return this;
        }

        public Builder transitionRules(List<TransitionRule> rules) {
            this.transitionRules = rules;
            return this;
        }

        public Builder waitForAll(boolean wait) {
            this.waitForAll = wait;
            return this;
        }

        public ForkNode build() {
            if (targets.isEmpty()) {
                throw new IllegalStateException("ForkNode must have at least one target");
            }
            return new ForkNode(this);
        }
    }
}
