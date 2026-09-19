package io.hensu.server.execution;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolResultEvent;
import io.hensu.core.util.LogSanitizer;
import org.jboss.logging.Logger;

/// Logs agent input/output to the server log at INFO level.
///
/// Emits structured log entries for every agent invocation, providing full
/// prompt and response visibility without requiring an external trace sink.
///
/// Designed to be composed with a checkpoint listener via
/// {@link io.hensu.core.execution.CompositeExecutionListener} and enabled by the
/// `hensu.verbose.enabled` configuration property.
///
/// Tool invocations are logged too, forming the operator-visible half of the
/// audit trail: the request names the tool and its argument keys, never the
/// argument values, so a prompt injection or a large payload cannot flood or
/// poison the log. Values reach no sink at all when the parameter is declared
/// sensitive – the tool loop has already redacted them.
///
/// ### Log Format
/// ```
/// [nodeId] → agentId  INPUT:
///   <prompt lines>
///
/// [nodeId] ← agentId  OUTPUT (OK|ERROR):
///   <response lines>
///
/// [nodeId] ⚒ agentId  TOOL search  args=[query]
/// [nodeId] ⚒ agentId  TOOL search  SUCCESS in 412ms (exit=0)
/// ```
///
/// @apiNote **Side effects**: writes to the JBoss log category
/// `io.hensu.server.execution.LoggingExecutionListener` at INFO level.
///
/// @implNote **Not thread-safe**. Log statements may interleave if used with
/// parallel node execution. Use a thread-safe logger appender for production.
///
/// @see io.hensu.core.execution.CompositeExecutionListener
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
    public void onToolCall(ToolCallEvent event) {
        LOG.infov(
                "[{0}] ⚒ {1}  TOOL {2}  args={3}",
                LogSanitizer.sanitize(event.nodeId()),
                LogSanitizer.sanitize(event.agentId()),
                LogSanitizer.sanitize(event.toolName()),
                event.arguments().keySet());
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        String detail =
                event.status()
                        + " in "
                        + event.durationMs()
                        + "ms"
                        + (event.exitCode() != null ? " (exit=" + event.exitCode() + ")" : "");

        if (event.status() == ToolCallStatus.SUCCESS) {
            LOG.infov(
                    "[{0}] ⚒ {1}  TOOL {2}  {3}",
                    LogSanitizer.sanitize(event.nodeId()),
                    LogSanitizer.sanitize(event.agentId()),
                    LogSanitizer.sanitize(event.toolName()),
                    detail);
        } else {
            LOG.warnv(
                    "[{0}] ⚒ {1}  TOOL {2}  {3}: {4}",
                    LogSanitizer.sanitize(event.nodeId()),
                    LogSanitizer.sanitize(event.agentId()),
                    LogSanitizer.sanitize(event.toolName()),
                    detail,
                    LogSanitizer.sanitize(event.error()));
        }
    }

    @Override
    public void onTransitionWarning(String nodeId, String message) {
        LOG.warnv(
                "[{0}]  TRANSITION WARNING: {1}",
                LogSanitizer.sanitize(nodeId), LogSanitizer.sanitize(message));
    }
}
