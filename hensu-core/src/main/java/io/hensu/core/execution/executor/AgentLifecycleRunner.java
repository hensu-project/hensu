package io.hensu.core.execution.executor;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.enricher.EngineVariablePromptEnricher;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.workflow.node.Node;
import java.util.Map;
import java.util.logging.Logger;

/// Stateless runner encapsulating the core agent execution lifecycle.
///
/// Centralizes the shared sequence that both {@link StandardNodeExecutor} and
/// {@link ParallelNodeExecutor} need when invoking an LLM agent:
///
/// 1. Template resolution – replace `{variable}` placeholders in the prompt
/// 2. Prompt enrichment – inject engine variable and yield field requirements
/// 3. Agent lookup – resolve the agent instance from the registry
/// 4. Listener notification – fire {@code onAgentStart} / {@code onAgentComplete}
/// 5. Agent execution – call the LLM
/// 6. Response conversion – map {@link AgentResponse} to {@link NodeResult}
///
/// This class owns **only** the agent call path. Output validation, output
/// extraction (writes / yields), transition resolution, and history recording
/// remain with their respective owners.
///
/// @implNote Package-private, stateless, no instances. Safe to call from any
/// thread including Virtual Threads.
final class AgentLifecycleRunner {

    private static final Logger logger = Logger.getLogger(AgentLifecycleRunner.class.getName());

    private AgentLifecycleRunner() {}

    /// Executes the full agent call lifecycle and returns the raw result.
    ///
    /// @param eventSourceId  identifier used in listener events (node ID or composite branch ID)
    /// @param agentId        identifier of the agent to invoke, not null
    /// @param resolvedPrompt prompt with `{variable}` placeholders already resolved by the caller,
    ///                       may be empty but not null
    /// @param enricherTarget the node passed to prompt enrichers for inspection, not null
    /// @param ctx            execution context carrying state, services, and branch config,
    ///                       not null
    /// @return node result with status and agent output, never null
    static NodeResult execute(
            String eventSourceId,
            String agentId,
            String resolvedPrompt,
            Node enricherTarget,
            ExecutionContext ctx) {

        // 1. Prompt enrichment (engine variables, yield requirements, consensus fields)
        String resolved =
                EngineVariablePromptEnricher.DEFAULT.enrich(resolvedPrompt, enricherTarget, ctx);

        // 2. Agent lookup
        Agent agent =
                ctx.getAgentRegistry()
                        .getAgent(agentId)
                        .orElseThrow(
                                () -> new IllegalStateException("Agent not found: " + agentId));

        logger.info("Executing agent: " + agentId + " for " + eventSourceId);

        // 3. Listener events + execution
        ExecutionListener listener = ctx.getListener();
        listener.onAgentStart(eventSourceId, agentId, resolved);
        AgentResponse response = agent.execute(resolved, ctx.getState().getContext());
        listener.onAgentComplete(eventSourceId, agentId, response);

        // 4. Response conversion
        return toNodeResult(response);
    }

    private static NodeResult toNodeResult(AgentResponse response) {
        return switch (response) {
            case AgentResponse.TextResponse t ->
                    new NodeResult(ResultStatus.SUCCESS, t.content(), t.metadata());
            case AgentResponse.ToolRequest t ->
                    new NodeResult(
                            ResultStatus.SUCCESS,
                            "Tool: " + t.toolName(),
                            Map.of("toolName", t.toolName(), "arguments", t.arguments()));
            case AgentResponse.PlanProposal p ->
                    new NodeResult(
                            ResultStatus.SUCCESS,
                            "Plan with " + p.steps().size() + " steps",
                            Map.of("steps", p.steps()));
            case AgentResponse.Error e ->
                    new NodeResult(
                            ResultStatus.FAILURE,
                            e.message(),
                            Map.of("errorType", e.errorType().name()));
        };
    }
}
