package io.hensu.server.execution;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolResultEvent;
import io.hensu.core.workflow.node.Node;
import io.hensu.server.persistence.LeaseLostException;
import java.util.Objects;
import org.jboss.logging.Logger;

/// Persists every checkpoint of a server execution and forwards all events to
/// the execution's observers.
///
/// Checkpointing sits outside the `CompositeExecutionListener` that fans events
/// out to observers, because that composite contains whatever a delegate
/// throws. One failure must not be contained: a {@link LeaseLostException}
/// means another node has claimed this execution, and letting the run continue
/// here would repeat its tool calls on two nodes at once. That exception
/// propagates out of the checkpoint and stops the run. Any other failure to
/// persist is logged and the run continues, as it did when checkpointing was
/// one observer among the others.
///
/// @implNote **Mutable. Thread-safe.** Holds the last checkpointed state in a
///     volatile field; one instance per execution run.
/// @see LeaseLostException for when a checkpoint is refused
public final class CheckpointingListener implements ExecutionListener {

    private static final Logger LOG = Logger.getLogger(CheckpointingListener.class);

    private final WorkflowStateRepository stateRepository;
    private final String tenantId;
    private final ExecutionListener observers;
    private volatile HensuState lastCheckpoint;

    /// Creates the listener for one execution run.
    ///
    /// @param stateRepository where checkpoints are written, not null
    /// @param tenantId the tenant owning the execution, not null
    /// @param observers receives every event after it is handled here, not null
    public CheckpointingListener(
            WorkflowStateRepository stateRepository, String tenantId, ExecutionListener observers) {
        this.stateRepository =
                Objects.requireNonNull(stateRepository, "stateRepository must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.observers = Objects.requireNonNull(observers, "observers must not be null");
    }

    /// Returns the state most recently handed to {@link #onCheckpoint}.
    ///
    /// @return the last checkpointed state, or null if no checkpoint has occurred
    public HensuState lastCheckpoint() {
        return lastCheckpoint;
    }

    /// Persists the checkpoint, then forwards it to the observers.
    ///
    /// @param state the consistent state to persist, not null
    /// @throws LeaseLostException if another node now holds this execution's lease
    @Override
    public void onCheckpoint(HensuState state) {
        lastCheckpoint = state;
        try {
            stateRepository.save(tenantId, HensuSnapshot.from(state, "checkpoint"));
        } catch (LeaseLostException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warnv(e, "Checkpoint not persisted for execution {0}", state.getExecutionId());
        }
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
