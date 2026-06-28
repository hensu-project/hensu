package io.hensu.core.state;

import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.plan.Plan;
import io.hensu.core.resume.ResumeInput;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import java.util.Collections;
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
/// - **Mutable evaluation**: `rubricEvaluation`, `retryCounters`
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
    private Plan activePlan;
    private RubricEvaluation rubricEvaluation;
    private String currentNode;
    private final Map<String, Integer> retryCounters;
    private String loopBreakTarget;
    private ExecutionPhase phase = ExecutionPhase.INITIAL;

    // Transient — set before executeFrom(), consumed by post-processors, never persisted.
    private ResumeInput resumeInput;

    // Transient — set by backtrack processors, consumed by TransitionPostProcessor, never
    // persisted.
    private boolean nodeRedirected;

    public HensuState(Builder builder) {
        this.executionId = Objects.requireNonNull(builder.executionId);
        this.workflowId = Objects.requireNonNull(builder.workflowId);
        this.currentNode = Objects.requireNonNull(builder.currentNode);
        this.context = new HashMap<>(builder.context);
        this.history = builder.history;
        this.activePlan = builder.activePlan;
        this.rubricEvaluation = builder.rubricEvaluation;
        this.retryCounters = new HashMap<>(builder.retryCounters);
        this.phase = builder.phase != null ? builder.phase : ExecutionPhase.INITIAL;
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
        this.retryCounters = new HashMap<>();
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

    public Plan getActivePlan() {
        return activePlan;
    }

    public void setActivePlan(Plan activePlan) {
        this.activePlan = activePlan;
    }

    public RubricEvaluation getRubricEvaluation() {
        return rubricEvaluation;
    }

    public String getCurrentNode() {
        return currentNode;
    }

    public void setCurrentNode(String currentNode) {
        this.currentNode = currentNode;
    }

    /// Returns the retry count for the given namespace and node, or 0 if none recorded.
    ///
    /// @param namespace counter namespace isolating budgets per trigger kind
    /// @param nodeId the node the budget applies to
    /// @return current count, never negative
    public int getRetryCount(String namespace, String nodeId) {
        return retryCounters.getOrDefault(namespace + ":" + nodeId, 0);
    }

    /// Increments the retry count for the given namespace and node.
    ///
    /// @param namespace counter namespace isolating budgets per trigger kind
    /// @param nodeId the node the budget applies to
    public void incrementRetryCount(String namespace, String nodeId) {
        retryCounters.merge(namespace + ":" + nodeId, 1, Integer::sum);
    }

    /// Clears counters in all namespaces for the given node — called when the node
    /// transitions forward (any non-revise transition).
    ///
    /// @param nodeId the node whose counters are cleared
    public void resetRetryCounts(String nodeId) {
        retryCounters.keySet().removeIf(k -> k.endsWith(":" + nodeId));
    }

    /// Returns a read-only view of all retry counters keyed by `namespace:nodeId`.
    ///
    /// @return unmodifiable view of the counter map, never null
    public Map<String, Integer> getRetryCounters() {
        return Collections.unmodifiableMap(retryCounters);
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

    /// Returns the current execution phase, never null.
    ///
    /// Defaults to {@link ExecutionPhase#INITIAL} for newly constructed states.
    public ExecutionPhase getPhase() {
        return phase;
    }

    /// Sets the current execution phase. Passing null resets to
    /// {@link ExecutionPhase#INITIAL}.
    public void setPhase(ExecutionPhase phase) {
        this.phase = phase != null ? phase : ExecutionPhase.INITIAL;
    }

    /// Returns the resume input set by the caller before {@code executeFrom()},
    /// or null if this is a fresh execution (not a resume).
    ///
    /// Transient — never persisted or included in snapshots.
    public ResumeInput getResumeInput() {
        return resumeInput;
    }

    /// Sets the resume input to deliver to post-processors on resume.
    ///
    /// @param resumeInput the input from the resume caller, may be null
    public void setResumeInput(ResumeInput resumeInput) {
        this.resumeInput = resumeInput;
    }

    /// Returns whether a backtrack processor redirected execution during this
    /// pipeline iteration.
    ///
    /// Transient — never persisted or included in snapshots. Reset at the
    /// start of each node lifecycle by
    /// {@link io.hensu.core.execution.NodeLifecycleCoordinator}.
    public boolean isNodeRedirected() {
        return nodeRedirected;
    }

    /// Marks that a backtrack processor has redirected execution to a different
    /// (or same) node.
    public void setNodeRedirected(boolean nodeRedirected) {
        this.nodeRedirected = nodeRedirected;
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

    /// Creates an isolated state copy for concurrent branch execution.
    ///
    /// Returns a new `HensuState` with a defensive copy of the context map, positioned
    /// at `branchNode`. Writes to the branch state do not affect the parent or sibling
    /// branches. All other fields (`executionId`, `workflowId`, `history`) are preserved.
    ///
    /// ### Contracts
    /// - **Postcondition**: returned state shares no mutable references with this state
    ///
    /// @param branchNode the node ID where the branch begins, not null
    /// @return new isolated branch state, never null
    public HensuState branch(String branchNode) {
        return new Builder()
                .executionId(executionId)
                .workflowId(workflowId)
                .currentNode(branchNode)
                .context(context)
                .history(history)
                .retryCounters(retryCounters)
                .build();
    }

    /// Creates a snapshot of current state for checkpointing.
    ///
    /// @return immutable snapshot of current state, never null
    public HensuSnapshot snapshot() {
        return HensuSnapshot.from(this);
    }

    /// Creates a snapshot with a reason for checkpointing.
    ///
    /// @param reason why this checkpoint is being created, may be null
    /// @return immutable snapshot of current state, never null
    public HensuSnapshot snapshot(String reason) {
        return HensuSnapshot.from(this, reason);
    }

    /// Restores state from a snapshot.
    ///
    /// @param snapshot the snapshot to restore from, not null
    /// @return restored workflow state, never null
    public static HensuState restore(HensuSnapshot snapshot) {
        return snapshot.toState();
    }

    private Builder toBuilder() {
        return new Builder()
                .executionId(executionId)
                .workflowId(workflowId)
                .context(context)
                .history(history)
                .activePlan(activePlan)
                .rubricEvaluation(rubricEvaluation)
                .retryCounters(retryCounters)
                .phase(phase);
    }

    public static final class Builder {
        private String executionId;
        private String workflowId;
        public String currentNode;
        private Map<String, Object> context = Map.of();
        private ExecutionHistory history = new ExecutionHistory();
        private Plan activePlan;
        private RubricEvaluation rubricEvaluation;
        private Map<String, Integer> retryCounters = new HashMap<>();
        private ExecutionPhase phase = ExecutionPhase.INITIAL;

        public Builder() {}

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

        public Builder activePlan(Plan activePlan) {
            this.activePlan = activePlan;
            return this;
        }

        public Builder rubricEvaluation(RubricEvaluation evaluation) {
            this.rubricEvaluation = evaluation;
            return this;
        }

        public Builder retryCounters(Map<String, Integer> retryCounters) {
            this.retryCounters = new HashMap<>(retryCounters); // defensive copy — B3
            return this;
        }

        public Builder phase(ExecutionPhase phase) {
            this.phase = phase != null ? phase : ExecutionPhase.INITIAL;
            return this;
        }

        public HensuState build() {
            return new HensuState(this);
        }
    }
}
