package io.hensu.core.state;

import io.hensu.core.execution.result.BacktrackEvent;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/// Mutable workflow execution state container.
///
/// Tracks the current position, context variables, execution history, and
/// evaluation results during workflow execution. Provides both mutable
/// setters for in-place updates and immutable `with*` methods for
/// creating modified copies.
///
/// ### State Components
/// - **Immutable**: `executionId`, `workflowId` (set at construction)
/// - **Mutable context**: `context` map for variable storage
/// - **Mutable position**: `currentNode`, `loopBreakTarget`
/// - **Mutable evaluation**: `rubricEvaluation`, `retryCount`
/// - **Append-only**: `history` for execution tracking
///
/// ### Thread Safety
/// @implNote **Not thread-safe**. State should only be modified by a single
/// workflow executor thread. For parallel node execution, branch-specific
/// state copies should be created.
///
/// @see HensuSnapshot for immutable state snapshots
/// @see ExecutionHistory for step and backtrack tracking
public final class HensuState {

    // Immutable fields (set once at construction)
    private final String workflowId;
    private final Map<String, Object> context;
    private final ExecutionHistory history;
    private final String executionId;

    // Mutable execution state (modified during workflow execution)
    private RubricEvaluation rubricEvaluation;
    private String currentNode;
    private int retryCount;
    private String loopBreakTarget;

    public HensuState(Builder builder) {
        this.executionId = Objects.requireNonNull(builder.executionId);
        this.workflowId = Objects.requireNonNull(builder.workflowId);
        this.currentNode = Objects.requireNonNull(builder.currentNode);
        this.context = Map.copyOf(builder.context);
        this.history = builder.history;
        this.rubricEvaluation = builder.rubricEvaluation;
        this.retryCount = builder.retryCount;
    }

    public HensuState(
            HashMap<String, Object> context,
            String workflowId,
            String currentNode,
            ExecutionHistory history) {
        this.executionId = UUID.randomUUID().toString();
        this.context = context;
        this.workflowId = workflowId;
        this.currentNode = currentNode;
        this.history = history;
    }

    // Getters
    public String getExecutionId() {
        return executionId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public ExecutionHistory getHistory() {
        return history;
    }

    public RubricEvaluation getRubricEvaluation() {
        return rubricEvaluation;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getCurrentNode() {
        return currentNode;
    }

    public void setCurrentNode(String currentNode) {
        this.currentNode = currentNode;
    }

    public void incrementRetryCount() {
        ++retryCount;
    }

    public void setRubricEvaluation(RubricEvaluation rubricEvaluation) {
        this.rubricEvaluation = rubricEvaluation;
    }

    public String getLoopBreakTarget() {
        return loopBreakTarget;
    }

    public void setLoopBreakTarget(String loopBreakTarget) {
        this.loopBreakTarget = loopBreakTarget;
    }

    /// Create initial state.
    public static HensuState create(
            String executionId, String workflowId, Map<String, Object> context) {

        return new Builder()
                .executionId(executionId)
                .workflowId(workflowId)
                .context(context)
                .history(new ExecutionHistory())
                .build();
    }

    /// Add step to history (immutable).
    public HensuState withAddedStep(ExecutionStep step) {
        ExecutionHistory newHistory = history.addStep(step);

        return toBuilder().history(newHistory).build();
    }

    /// Update rubric evaluation (immutable).
    public HensuState withRubricEvaluation(RubricEvaluation evaluation) {
        return toBuilder().rubricEvaluation(evaluation).build();
    }

    /// Add backtrack event (immutable).
    public HensuState withBacktrack(BacktrackEvent backtrack) {
        ExecutionHistory newHistory = history.addBacktrack(backtrack);

        return toBuilder().history(newHistory).retryCount(retryCount + 1).build();
    }

    /// Create snapshot for checkpointing.
    public HensuSnapshot snapshot() {
        return new HensuSnapshot().from(this);
    }

    private Builder toBuilder() {
        return new Builder()
                .executionId(executionId)
                .workflowId(workflowId)
                .context(context)
                .history(history)
                .rubricEvaluation(rubricEvaluation)
                .retryCount(retryCount);
    }

    public static final class Builder {
        private String executionId;
        private String workflowId;
        public String currentNode;
        private Map<String, Object> context = Map.of();
        private ExecutionHistory history = new ExecutionHistory();
        private RubricEvaluation rubricEvaluation;
        private int retryCount = 0;

        Builder() {}

        public Builder executionId(String executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder workflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public Builder currentNode(String currentNode) {
            this.currentNode = currentNode;
            return this;
        }

        public Builder context(Map<String, Object> context) {
            this.context = new HashMap<>(context);
            return this;
        }

        public Builder history(ExecutionHistory history) {
            this.history = history;
            return this;
        }

        public Builder rubricEvaluation(RubricEvaluation evaluation) {
            this.rubricEvaluation = evaluation;
            return this;
        }

        public Builder retryCount(int retryCount) {
            this.retryCount = retryCount;
            return this;
        }

        public HensuState build() {
            return new HensuState(this);
        }
    }
}
