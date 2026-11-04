package io.hensu.server.execution;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolResultEvent;
import io.hensu.core.workflow.node.Node;
import java.util.Objects;

/// Writes each main-flow checkpoint under the run's claim, then forwards every event to
/// the execution's observers.
///
/// Checkpointing sits outside the `CompositeExecutionListener` that fans events out to
/// observers, because that composite contains whatever a delegate throws, and a
/// checkpoint failure must not be contained: the store answers a superseded claim – or an
/// unreachable database – with {@link io.hensu.core.execution.RunPreemptedException},
/// and the run must stop before its next node attempt. Continuing would let the attempt
/// call tools whose intents its successor would never find (S4 of
/// docs/multi-node-execution-protocol.md).
///
/// @implNote **Mutable. Thread-safe.** Holds the last checkpointed state in a volatile
///     field; one instance per run.
public final class CheckpointingListener implements ExecutionListener {

    private final ExecutionStore store;
    private final RunHandle run;
    private final ExecutionListener observers;
    private volatile HensuState lastCheckpoint;

    /// Creates the listener for one run.
    ///
    /// @param store where checkpoints are written, not null
    /// @param run the run whose claim fences the writes, not null
    /// @param observers receives every event after it is handled here, not null
    public CheckpointingListener(ExecutionStore store, RunHandle run, ExecutionListener observers) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.run = Objects.requireNonNull(run, "run must not be null");
        this.observers = Objects.requireNonNull(observers, "observers must not be null");
    }

    /// Returns the state most recently handed to {@link #onCheckpoint}.
    ///
    /// @return the last checkpointed state, or null if no checkpoint has occurred
    public HensuState lastCheckpoint() {
        return lastCheckpoint;
    }

    /// Persists the checkpoint under the run's claim, then forwards it to the observers.
    ///
    /// @param state the consistent state to persist, not null
    /// @throws io.hensu.core.execution.RunPreemptedException if the claim no longer stands
    ///     or the store cannot be reached
    @Override
    public void onCheckpoint(HensuState state) {
        run.checkLive();
        store.checkpoint(run.claim(), HensuSnapshot.from(state, "checkpoint"));
        lastCheckpoint = state;
        observers.onCheckpoint(state);
    }

    @Override
    public void onAgentStart(String nodeId, String agentId, String prompt) {
        observers.onAgentStart(nodeId, agentId, prompt);
    }

    @Override
    public void onAgentComplete(String nodeId, String agentId, AgentResponse response) {
        observers.onAgentComplete(nodeId, agentId, response);
    }

    @Override
    public void onNodeStart(Node node) {
        observers.onNodeStart(node);
    }

    @Override
    public void onNodeComplete(Node node, NodeResult result) {
        observers.onNodeComplete(node, result);
    }

    @Override
    public void onTransitionWarning(String nodeId, String message) {
        observers.onTransitionWarning(nodeId, message);
    }

    @Override
    public void onToolCall(ToolCallEvent event) {
        observers.onToolCall(event);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        observers.onToolResult(event);
    }
}
