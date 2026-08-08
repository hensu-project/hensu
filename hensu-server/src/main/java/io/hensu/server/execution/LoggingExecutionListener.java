package io.hensu.server.execution;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.util.LogSanitizer;
import org.jboss.logging.Logger;

/// Logs agent input/output to the server log at INFO level.
///
/// Emits structured log entries for every agent invocation, providing full
/// prompt and response visibility without requiring an external trace sink.
///
/// Designed to be composed with a checkpoint listener via
/// {@link CompositeExecutionListener} and enabled by the
/// `hensu.verbose.enabled` configuration property.
///
/// ### Log Format
/// ```
/// [nodeId] → agentId  INPUT:
///   <prompt lines>
///
/// [nodeId] ← agentId  OUTPUT (OK|ERROR):
///   <response lines>
/// ```
///
/// @apiNote **Side effects**: writes to the JBoss log category
/// `io.hensu.server.execution.LoggingExecutionListener` at INFO level.
///
/// @implNote **Not thread-safe**. Log statements may interleave if used with
/// parallel node execution. Use a thread-safe logger appender for production.
///
/// @see CompositeExecutionListener
/// @see io.hensu.server.workflow.WorkflowService
public class LoggingExecutionListener implements ExecutionListener {

    private static final Logger LOG = Logger.getLogger(LoggingExecutionListener.class);

    @Override
    public void onAgentStart(String nodeId, String agentId, String prompt) {
        LOG.infov("[{0}] → {1}  INPUT:\n{2}", nodeId, agentId, prompt);
    }

    @Override
    public void onAgentComplete(String nodeId, String agentId, AgentResponse response) {
        boolean isSuccess = !(response instanceof AgentResponse.Error);
        String status = isSuccess ? "OK" : "ERROR";
        String output =
                switch (response) {
                    case AgentResponse.TextResponse t -> t.content();
                    case AgentResponse.ToolRequest t ->
                            "Tool: " + t.toolName() + " — " + t.reasoning();
                    case AgentResponse.Error e -> e.message();
                };
        LOG.infov("[{0}] ← {1}  OUTPUT ({2}):\n{3}", nodeId, agentId, status, output);
    }

    @Override
    public void onTransitionWarning(String nodeId, String message) {
        LOG.warnv(
                "[{0}]  TRANSITION WARNING: {1}",
                LogSanitizer.sanitize(nodeId), LogSanitizer.sanitize(message));
    }
}
