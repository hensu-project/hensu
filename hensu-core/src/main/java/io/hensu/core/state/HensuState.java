package io.hensu.core.state;

import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.resume.ResumeInput;
import io.hensu.core.review.ReviewVerdict;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
/// - **Mutable position**: `currentNode`
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
    private RubricEvaluation rubricEvaluation;
    private String currentNode;
    private final Map<String, Integer> retryCounters;
    private ExecutionPhase phase;

    // Transient — set before executeFrom(), consumed by post-processors, never persisted.
    private ResumeInput resumeInput;

    // Transient — set by backtrack processors, consumed by TransitionPostProcessor, never
    // persisted.
    private boolean nodeRedirected;

    // Transient — set by ReviewPostProcessor, consumed by TransitionPostProcessor, never
    // persisted and never propagated to fork branches.
    private ReviewVerdict reviewVerdict;

    // Persisted: node attempts this flow has completed. It names the journal position of
    // the next attempt, so a run recovered from a checkpoint reaches the same position.
    private long step;

    // Transient: the journal scope of a nested flow, empty for an execution's main flow.
    // Set by whichever executor starts the nested flow; never persisted with main state.
    private final String scope;

    public HensuState(Builder builder) {
        this.executionId = Objects.requireNonNull(builder.executionId);
        this.workflowId = Objects.requireNonNull(builder.workflowId);
        this.currentNode = Objects.requireNonNull(builder.currentNode);
        this.context = new HashMap<>(builder.context);
        this.history = builder.history;
        this.rubricEvaluation = builder.rubricEvaluation;
        this.retryCounters = new HashMap<>(builder.retryCounters);
        this.phase = builder.phase != null ? builder.phase : ExecutionPhase.INITIAL;
        this.step = builder.step;
        this.scope = builder.scope;
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

    /// Returns the human reviewer's verdict for the node just executed, or null when no
    /// reviewer decided on it.
    ///
    /// Transient — never persisted, never included in snapshots, and never carried into a
    /// branch state. Set by
    /// {@link io.hensu.core.execution.pipeline.ReviewPostProcessor} and cleared by
    /// {@link io.hensu.core.execution.pipeline.TransitionPostProcessor} once routing is
    /// resolved.
    ///
    /// @return the reviewer's verdict, or null when the node was not reviewed by a human
    public ReviewVerdict getReviewVerdict() {
        return reviewVerdict;
    }

    /// Records the human reviewer's verdict for the node just executed.
    ///
    /// @param reviewVerdict the reviewer's verdict, or null to clear a consumed one
    public void setReviewVerdict(ReviewVerdict reviewVerdict) {
        this.reviewVerdict = reviewVerdict;
    }

    /// Returns how many node attempts this flow has completed.
    ///
    /// Persisted with the main flow's checkpoint and advanced by the executor each time
    /// a node completes and the flow moves on, so the attempt a checkpoint opens and the
    /// attempt a recovery re-enters carry the same number. Nested flows count from zero.
    ///
    /// @return the completed node attempts, never negative
    public long getStep() {
        return step;
    }

    /// Records that the current node attempt completed and the flow moved on.
    public void advanceStep() {
        step++;
    }

    /// Returns the journal scope of this flow.
    ///
    /// Empty for an execution's main flow, whose checkpoints are the execution's own
    /// state. A nested flow – a fork branch, a parallel branch, a sub-workflow – has a
    /// scope naming the parent attempt that started it, and records its checkpoints in
    /// the execution journal under that scope instead; see
    /// {@link io.hensu.core.execution.pipeline.CheckpointPreProcessor}.
    ///
    /// @return the scope, empty for the main flow, never null
    public String getScope() {
        return scope;
    }

    /// Returns whether this state belongs to a nested flow rather than the main flow.
    ///
    /// @return true for fork, parallel and sub-workflow flows
    public boolean isNested() {
        return !scope.isEmpty();
    }

    /// Returns the journal position of the node attempt this state is at.
    ///
    /// The position is the scope followed by the step, and identifies the attempt for
    /// as long as the execution lives: tool calls and model turns of the attempt are
    /// journaled under it, and a recovered run reproduces it.
    ///
    /// @return the position, never null
    public String position() {
        return scope + step;
    }

    /// Creates an isolated state copy for a nested flow.
    ///
    /// Returns a new `HensuState` with defensive copies of the context map, the retry counters,
    /// and the execution history, positioned at `branchNode`, counting steps from zero under
    /// `scope`. Writes to the branch state do not affect the parent or sibling branches, which
    /// matters because every branch runs on its own virtual thread and {@link ExecutionHistory}
    /// is backed by plain `ArrayList`s.
    ///
    /// The caller is responsible for merging each branch's new history entries back into the
    /// parent history in a deterministic order once all branches have joined; see
    /// {@link io.hensu.core.execution.executor.ForkNodeExecutor}.
    ///
    /// Transient fields are not carried over: the returned state has no resume input, no review
    /// verdict, and no redirect flag.
    ///
    /// ### Contracts
    /// - **Postcondition**: returned state shares no mutable references with this state
    ///
    /// @param branchNode the node ID where the branch begins, not null
    /// @param scope the journal scope of the nested flow, not empty
    /// @return new isolated branch state, never null
    public HensuState branch(String branchNode, String scope) {
        if (Objects.requireNonNull(scope, "scope must not be null").isEmpty()) {
            throw new IllegalArgumentException("A nested flow needs a scope");
        }
        return new Builder()
                .executionId(executionId)
                .workflowId(workflowId)
                .currentNode(branchNode)
                .context(context)
                .history(history.copy())
                .retryCounters(retryCounters)
                .scope(scope)
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

    public static final class Builder {
        private String executionId;
        private String workflowId;
        private String currentNode;
        private Map<String, Object> context = Map.of();
        private ExecutionHistory history = new ExecutionHistory();
        private RubricEvaluation rubricEvaluation;
        private Map<String, Integer> retryCounters = new HashMap<>();
        private ExecutionPhase phase = ExecutionPhase.INITIAL;
        private long step;
        private String scope = "";

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

        public Builder step(long step) {
            this.step = step;
            return this;
        }

        public Builder scope(String scope) {
            this.scope = Objects.requireNonNull(scope, "scope must not be null");
            return this;
        }

        public HensuState build() {
            return new HensuState(this);
        }
    }
}
