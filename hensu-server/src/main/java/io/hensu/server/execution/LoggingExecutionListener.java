package io.hensu.server.execution;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.plan.PlannedStep;
import java.util.List;
import org.jboss.logging.Logger;

/// Logs agent and planner input/output to the server log at INFO level.
///
/// Emits structured log entries for every agent invocation and planning
/// cycle, providing full prompt and response visibility without requiring
/// an external trace sink.
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
///
/// [nodeId]  PLANNER INPUT:
///   <planning prompt lines>
///
/// [nodeId]  PLANNER OUTPUT: N steps
///   0. toolName: description
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
                    case AgentResponse.PlanProposal p -> "Plan: " + p.steps().size() + " steps";
                    case AgentResponse.Error e -> e.message();
                };
        LOG.infov("[{0}] ← {1}  OUTPUT ({2}):\n{3}", nodeId, agentId, status, output);
    }

    @Override
    public void onPlannerStart(String nodeId, String planningPrompt) {
        LOG.infov("[{0}]  PLANNER INPUT:\n{1}", nodeId, planningPrompt);
    }

    @Override
    public void onPlannerComplete(String nodeId, List<PlannedStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (PlannedStep step : steps) {
            String label = step.isSynthesize() ? "_synthesize" : step.toolName();
            sb.append("  ")
                    .append(step.index())
                    .append(". ")
                    .append(label)
                    .append(": ")
                    .append(step.description())
                    .append('\n');
        }
        LOG.infov("[{0}]  PLANNER OUTPUT: {1} steps\n{2}", nodeId, steps.size(), sb);
    }
}
