package io.hensu.core.workflow.node;

import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanningConfig;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import java.util.Objects;

/// Standard workflow node for agent-based execution.
///
/// The most common node type in Hensu workflows. A standard node executes
/// a prompt using a configured agent and transitions based on the result.
/// Optional features include rubric evaluation, human review, and typed
/// state variable output via `writes`.
///
/// ### Execution Flow
/// 1. Resolve prompt template variables from context
/// 2. Execute prompt via the configured agent
/// 3. Optionally evaluate output against a rubric
/// 4. Optionally request human review
/// 5. Route output to declared `writes` variables in context
/// 6. Evaluate transition rules to determine next node
///
/// @implNote **Immutable after construction.** Agent and prompt
/// may be null for nodes using alternative execution strategies.
///
/// @see Node for the base class
/// @see io.hensu.core.execution.executor.StandardNodeExecutor for execution logic
public final class StandardNode extends Node {

    private final NodeType nodeType = NodeType.STANDARD;
    private final String agentId;
    private final String prompt;
    private final String rubricId;
    private final ReviewConfig reviewConfig;
    private final List<TransitionRule> transitionRules;
    private final List<String> writes;

    // Planning support
    private final PlanningConfig planningConfig;
    private final Plan staticPlan; // null if dynamic or disabled
    private final String planFailureTarget; // Node to transition to on plan failure

    private StandardNode(Builder builder) {
        super(builder.id);
        // agentId and prompt can be null for nodes that use other execution strategies
        this.agentId = builder.agentId;
        this.prompt = builder.prompt;
        this.rubricId = builder.rubricId;
        this.reviewConfig = builder.reviewConfig;
        this.transitionRules = List.copyOf(builder.transitionRules);
        this.writes = builder.writes != null ? List.copyOf(builder.writes) : List.of();
        // Planning support
        this.planningConfig =
                builder.planningConfig != null ? builder.planningConfig : PlanningConfig.disabled();
        this.staticPlan = builder.staticPlan;
        this.planFailureTarget = builder.planFailureTarget;
    }

    /// Creates a new node builder.
    ///
    /// @return new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Returns the agent identifier for prompt execution.
    ///
    /// @return agent ID, or null for alternative execution strategies
    public String getAgentId() {
        return agentId;
    }

    /// Returns the prompt template.
    ///
    /// May contain `{variable}` placeholders resolved from context.
    ///
    /// @return prompt template, or null for alternative execution strategies
    public String getPrompt() {
        return prompt;
    }

    /// Returns the rubric ID for quality evaluation.
    ///
    /// @return rubric identifier, or null if no evaluation required
    @Override
    public String getRubricId() {
        return rubricId;
    }

    /// Returns the node type for executor dispatch.
    ///
    /// @return STANDARD, never null
    @Override
    public NodeType getNodeType() {
        return nodeType;
    }

    /// Returns the human review configuration.
    ///
    /// @return review config, or null if no review required
    public ReviewConfig getReviewConfig() {
        return reviewConfig;
    }

    /// Returns the transition rules for next node selection.
    ///
    /// @return unmodifiable list of rules, never null
    @Override
    public List<TransitionRule> getTransitionRules() {
        return transitionRules;
    }

    /// Returns the semantic state variable names this node produces.
    ///
    /// `OutputExtractionPostProcessor` routes output to these context keys:
    /// - Single entry: full text response stored under `writes.get(0)`
    /// - Multiple entries: JSON response parsed, each key stored under its declared name
    ///
    /// @return unmodifiable list of variable names, never null, may be empty
    public List<String> getWrites() {
        return writes;
    }

    /// Returns the planning configuration.
    ///
    /// Determines how this node handles plan generation and execution.
    ///
    /// @return planning config, never null (defaults to DISABLED mode)
    public PlanningConfig getPlanningConfig() {
        return planningConfig;
    }

    /// Returns the static plan defined in DSL.
    ///
    /// Only applicable when planning mode is STATIC.
    ///
    /// @return static plan, or null if dynamic or disabled
    public Plan getStaticPlan() {
        return staticPlan;
    }

    /// Returns the target node for plan failure transitions.
    ///
    /// @return target node ID, or null if not configured
    public String getPlanFailureTarget() {
        return planFailureTarget;
    }

    /// Returns whether this node has planning enabled.
    ///
    /// @return true if planning mode is not DISABLED
    public boolean hasPlanningEnabled() {
        return planningConfig.isEnabled();
    }

    /// Builder for constructing immutable StandardNode instances.
    ///
    /// Required fields: `id`, `transitionRules`
    public static final class Builder {
        private String id;
        private String agentId;
        private String prompt;
        private String rubricId;
        private ReviewConfig reviewConfig;
        private List<TransitionRule> transitionRules;
        private List<String> writes;
        private PlanningConfig planningConfig;
        private Plan staticPlan;
        private String planFailureTarget;

        private Builder() {}

        /// Sets the node identifier (required).
        ///
        /// @param id unique node ID, not null
        /// @return this builder for chaining
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /// Sets the agent for prompt execution.
        ///
        /// @param agentId agent identifier, may be null
        /// @return this builder for chaining
        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        /// Sets the prompt template.
        ///
        /// @param prompt template with optional `{var}` placeholders, may be null
        /// @return this builder for chaining
        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        /// Sets the rubric for quality evaluation.
        ///
        /// @param rubricId rubric identifier, may be null
        /// @return this builder for chaining
        public Builder rubricId(String rubricId) {
            this.rubricId = rubricId;
            return this;
        }

        /// Sets the human review configuration.
        ///
        /// @param reviewConfig review settings, may be null
        /// @return this builder for chaining
        public Builder reviewConfig(ReviewConfig reviewConfig) {
            this.reviewConfig = reviewConfig;
            return this;
        }

        /// Sets the transition rules (required).
        ///
        /// @param transitionRules rules for next node selection, not null
        /// @return this builder for chaining
        public Builder transitionRules(List<TransitionRule> transitionRules) {
            this.transitionRules = transitionRules;
            return this;
        }

        /// Sets the semantic state variable names this node produces.
        ///
        /// @param writes variable names this node writes to, may be null (treated as empty)
        /// @return this builder for chaining
        public Builder writes(List<String> writes) {
            this.writes = writes;
            return this;
        }

        /// Sets the planning configuration.
        ///
        /// @param planningConfig planning settings, may be null (defaults to DISABLED)
        /// @return this builder for chaining
        public Builder planningConfig(PlanningConfig planningConfig) {
            this.planningConfig = planningConfig;
            return this;
        }

        /// Sets the static plan for STATIC planning mode.
        ///
        /// @param staticPlan predefined plan from DSL, may be null
        /// @return this builder for chaining
        public Builder staticPlan(Plan staticPlan) {
            this.staticPlan = staticPlan;
            return this;
        }

        /// Sets the target node for plan failure transitions.
        ///
        /// @param planFailureTarget node ID to transition to on plan failure, may be null
        /// @return this builder for chaining
        public Builder planFailureTarget(String planFailureTarget) {
            this.planFailureTarget = planFailureTarget;
            return this;
        }

        /// Builds the immutable standard node.
        ///
        /// @return new StandardNode instance, never null
        /// @throws IllegalStateException if `id` or `transitionRules` is null
        public StandardNode build() {
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("StandardNode id is required");
            }
            if (transitionRules == null) {
                throw new IllegalStateException("StandardNode transitionRules is required");
            }
            return new StandardNode(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StandardNode node)) return false;
        return Objects.equals(id, node.id) && Objects.equals(agentId, node.agentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, agentId);
    }

    @Override
    public String toString() {
        return "StandardNode{id='" + id + "', agentId='" + agentId + "'}";
    }
}
