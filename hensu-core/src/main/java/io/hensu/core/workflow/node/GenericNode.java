package io.hensu.core.workflow.node;

import io.hensu.core.workflow.transition.TransitionRule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// A generic node that allows users to define custom execution logic.
///
/// Unlike StandardNode which invokes an agent, GenericNode delegates to a user-registered
/// executor identified by `executorType`.
///
/// ### This enables:
///
/// - Custom validation logic
/// - Data transformation steps
/// - External service integration
/// - Conditional branching logic
/// - Any arbitrary computation
///
///
/// Multiple GenericNodes can share the same executor (same `executorType`), or each can
/// have a unique executor. The executor is looked up by type from the registry at execution time.
///
/// ### Example DSL usage
/// {@snippet lang=kotlin:
///  generic("validate-input") {
///      executorType = "validator"  // Shared executor for all validators
///      config {
///          "minLength" to 10
///          "maxLength" to 1000
///      }
///      onSuccess("process")
///      onFailure("error")
///  }
///
///  generic("transform-data") {
///      executorType = "json-transformer"  // Different executor
///      config {
///          "outputFormat" to "xml"
///      }
///      onSuccess("next")
///  }
/// }
public class GenericNode extends Node {

    private final String executorType;
    private final Map<String, Object> config;
    private final List<TransitionRule> transitionRules;
    private final String rubricId;

    private GenericNode(Builder builder) {
        super(builder.id);
        this.executorType = builder.executorType;
        this.config = Map.copyOf(builder.config);
        this.transitionRules =
                builder.transitionRules != null ? List.copyOf(builder.transitionRules) : List.of();
        this.rubricId = builder.rubricId;
    }

    /// The executor type identifier used to look up the appropriate executor. Multiple nodes can
    /// share the same executor type.
    public String getExecutorType() {
        return executorType;
    }

    /// Configuration parameters passed to the executor. Executors can use these to customize their
    /// behavior per-node.
    public Map<String, Object> getConfig() {
        return config;
    }

    public List<TransitionRule> getTransitionRules() {
        return transitionRules;
    }

    @Override
    public String getRubricId() {
        return rubricId;
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.GENERIC;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String executorType;
        private Map<String, Object> config = new HashMap<>();
        private List<TransitionRule> transitionRules;
        private String rubricId;

        private Builder() {}

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder executorType(String executorType) {
            this.executorType = executorType;
            return this;
        }

        public Builder config(Map<String, Object> config) {
            this.config = config != null ? new HashMap<>(config) : new HashMap<>();
            return this;
        }

        public Builder configEntry(String key, Object value) {
            this.config.put(key, value);
            return this;
        }

        public Builder transitionRules(List<TransitionRule> transitionRules) {
            this.transitionRules = transitionRules;
            return this;
        }

        public Builder rubricId(String rubricId) {
            this.rubricId = rubricId;
            return this;
        }

        public GenericNode build() {
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("GenericNode id is required");
            }
            if (executorType == null || executorType.isBlank()) {
                throw new IllegalStateException("GenericNode executorType is required");
            }
            return new GenericNode(this);
        }
    }
}
