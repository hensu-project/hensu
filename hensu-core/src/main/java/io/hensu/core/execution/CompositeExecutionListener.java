package io.hensu.core.execution;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuState;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolResultEvent;
import io.hensu.core.workflow.node.Node;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Fans out all execution lifecycle events to an ordered set of delegates.
///
/// Allows composing independent listeners — e.g., a checkpoint listener and a
/// logging listener — without modifying the caller. All delegates are invoked
/// in declaration order, and a delegate that throws is logged and skipped so
/// the remaining delegates still receive the event. That containment matters
/// most for the tool audit events: a broken sink must not cost the others their
/// record of what an unattended agent ran.
///
/// ### Usage
/// {@snippet :
/// ExecutionListener composite = new CompositeExecutionListener(
///     checkpointListener,
///     auditListener
/// );
/// workflowExecutor.execute(workflow, initialContext, composite);
/// }
///
/// @implNote Thread-safe if all delegates are thread-safe. Delegates are
/// captured at construction and never mutated.
///
/// It lives in the engine rather than in one runtime because both runtimes compose the
/// same way: the server layers checkpointing, the event stream and the tool audit, and the
/// CLI layers verbose output and its own audit sink. One fan-out with one containment rule
/// is the whole of it.
///
/// @see ExecutionListener
public final class CompositeExecutionListener implements ExecutionListener {

    private static final Logger logger =
            Logger.getLogger(CompositeExecutionListener.class.getName());

    private final ExecutionListener[] delegates;

    /// Creates a composite listener that dispatches to all provided delegates in order.
    ///
    /// @param delegates listeners to notify; must not be null, elements must not be null
    public CompositeExecutionListener(ExecutionListener... delegates) {
        this.delegates = delegates;
    }

    @Override
    public void onAgentStart(String nodeId, String agentId, String prompt) {
        fanOut("onAgentStart", d -> d.onAgentStart(nodeId, agentId, prompt));
    }

    @Override
    public void onAgentComplete(String nodeId, String agentId, AgentResponse response) {
        fanOut("onAgentComplete", d -> d.onAgentComplete(nodeId, agentId, response));
    }

    @Override
    public void onNodeStart(Node node) {
        fanOut("onNodeStart", d -> d.onNodeStart(node));
    }

    @Override
    public void onNodeComplete(Node node, NodeResult result) {
        fanOut("onNodeComplete", d -> d.onNodeComplete(node, result));
    }

    @Override
    public void onTransitionWarning(String nodeId, String message) {
        fanOut("onTransitionWarning", d -> d.onTransitionWarning(nodeId, message));
    }

    @Override
    public void onCheckpoint(HensuState state) {
        fanOut("onCheckpoint", d -> d.onCheckpoint(state));
    }

    @Override
    public void onToolCall(ToolCallEvent event) {
        fanOut("onToolCall", d -> d.onToolCall(event));
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        fanOut("onToolResult", d -> d.onToolResult(event));
    }

    /// Delivers one event to every delegate, containing a delegate that throws.
    ///
    /// @param event name of the callback, used only for the failure log line
    /// @param delivery the callback to apply to each delegate, not null
    private void fanOut(String event, Consumer<ExecutionListener> delivery) {
        for (ExecutionListener delegate : delegates) {
            try {
                delivery.accept(delegate);
            } catch (RuntimeException e) {
                logger.log(
                        Level.WARNING,
                        "Execution listener "
                                + delegate.getClass().getSimpleName()
                                + " failed handling "
                                + event,
                        e);
            }
        }
    }
}
