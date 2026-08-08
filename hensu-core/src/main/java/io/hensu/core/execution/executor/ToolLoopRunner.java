package io.hensu.core.execution.executor;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.agent.ToolCapable;
import io.hensu.core.agent.ToolSession;
import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/// Stateless driver for the agent-native tool execution loop.
///
/// Dispatched from {@link AgentLifecycleRunner} when an agent declares tools
/// and implements {@link ToolCapable}. Resolves tools from the
/// {@link ToolRegistry}, opens a {@link ToolSession}, and iterates
/// tool-request/tool-result rounds until the agent emits a terminal response
/// or the tool call budget is exhausted.
///
/// @implNote Package-private, stateless, no instances. Safe to call from any
/// thread including Virtual Threads.
final class ToolLoopRunner {

    private static final Logger logger = Logger.getLogger(ToolLoopRunner.class.getName());
    private static final int DEFAULT_MAX_TOOL_CALLS = 10;

    private ToolLoopRunner() {}

    /// Executes the full tool loop for an agent.
    ///
    /// @param eventSourceId  identifier for listener events
    /// @param agentId        identifier of the agent
    /// @param resolvedPrompt prompt with placeholders resolved and enriched
    /// @param agent          the resolved agent instance
    /// @param ctx            execution context carrying state and services
    /// @return node result, never null
    static NodeResult execute(
            String eventSourceId,
            String agentId,
            String resolvedPrompt,
            Agent agent,
            ExecutionContext ctx) {

        if (!(agent instanceof ToolCapable toolCapable)) {
            return new NodeResult(
                    ResultStatus.FAILURE,
                    "Agent '" + agentId + "' declares tools but does not implement ToolCapable",
                    Map.of());
        }

        ActionExecutor actionExecutor = ctx.getActionExecutor();
        if (actionExecutor == null) {
            return new NodeResult(
                    ResultStatus.FAILURE,
                    "Agent '" + agentId + "' declares tools but no ActionExecutor is configured",
                    Map.of());
        }

        List<ToolDefinition> availableTools = resolveTools(agent, ctx);
        if (availableTools == null) {
            List<String> declared = agent.getConfig().getTools();
            ToolRegistry registry = ctx.getToolRegistry();
            List<String> available =
                    registry != null
                            ? registry.all().stream().map(ToolDefinition::name).toList()
                            : List.of();
            return new NodeResult(
                    ResultStatus.FAILURE,
                    "Unresolvable tools for agent '"
                            + agentId
                            + "': declared="
                            + declared
                            + ", available="
                            + available,
                    Map.of());
        }

        int maxToolCalls =
                agent.getConfig().getMaxToolCalls() != null
                        ? agent.getConfig().getMaxToolCalls()
                        : DEFAULT_MAX_TOOL_CALLS;

        ToolSession session =
                toolCapable.openToolSession(
                        resolvedPrompt, ctx.getState().getContext(), availableTools);

        try {
            AgentResponse response = session.start();
            int toolCallCount = 0;

            while (response instanceof AgentResponse.ToolRequest toolRequest) {
                // Budget check — count EXECUTED tool calls, not rounds
                if (toolCallCount >= maxToolCalls) {
                    session.compact();
                    ToolCallResult exhaustion =
                            ToolCallResult.failure(
                                    toolRequest.toolName(),
                                    "Tool call budget exhausted ("
                                            + toolCallCount
                                            + "/"
                                            + maxToolCalls
                                            + "). Provide your final answer based on the tool results received so far.");
                    response = session.submit(exhaustion);

                    if (response instanceof AgentResponse.ToolRequest) {
                        return new NodeResult(
                                ResultStatus.FAILURE,
                                "Agent continued requesting tools after budget exhaustion ("
                                        + maxToolCalls
                                        + "/"
                                        + maxToolCalls
                                        + ")",
                                Map.of());
                    }
                    break;
                }

                ToolCallResult result =
                        executeTool(toolRequest, availableTools, actionExecutor, ctx);
                toolCallCount++;
                response = session.submit(result);
            }

            return toNodeResult(response);

        } finally {
            session.close();
        }
    }

    private static List<ToolDefinition> resolveTools(Agent agent, ExecutionContext ctx) {
        List<String> declaredNames = agent.getConfig().getTools();
        ToolRegistry registry = ctx.getToolRegistry();

        if (registry == null) {
            return null;
        }

        List<ToolDefinition> allTools = registry.all();
        Map<String, ToolDefinition> byName =
                allTools.stream()
                        .collect(Collectors.toMap(ToolDefinition::name, t -> t, (a, _) -> a));

        List<ToolDefinition> resolved = declaredNames.stream().map(byName::get).toList();

        if (resolved.stream().anyMatch(Objects::isNull)) {
            return null;
        }

        return resolved;
    }

    private static ToolCallResult executeTool(
            AgentResponse.ToolRequest toolRequest,
            List<ToolDefinition> availableTools,
            ActionExecutor actionExecutor,
            ExecutionContext ctx) {

        String toolName = toolRequest.toolName();

        // Unknown tool (hallucination) — feed back, don't hard-fail
        boolean known = availableTools.stream().anyMatch(t -> t.name().equals(toolName));
        if (!known) {
            List<String> available = availableTools.stream().map(ToolDefinition::name).toList();
            logger.warning(
                    "Agent requested unknown tool '" + toolName + "', available: " + available);
            return ToolCallResult.failure(
                    toolName, "Unknown tool '" + toolName + "', available: " + available);
        }

        try {
            Map<String, Object> arguments =
                    (Map<String, Object>) (Map<?, ?>) toolRequest.arguments();
            Action.Send send = new Action.Send(toolName, arguments, true);
            ActionExecutor.ActionResult actionResult =
                    actionExecutor.execute(send, ctx.getState().getContext());

            if (actionResult.success()) {
                return ToolCallResult.success(
                        toolName,
                        actionResult.output() != null ? actionResult.output().toString() : "");
            } else {
                return ToolCallResult.failure(toolName, actionResult.message());
            }
        } catch (Exception e) {
            logger.warning("Tool execution failed for '" + toolName + "': " + e.getMessage());
            return ToolCallResult.failure(toolName, "Execution error: " + e.getMessage());
        }
    }

    private static NodeResult toNodeResult(AgentResponse response) {
        return switch (response) {
            case AgentResponse.TextResponse t ->
                    new NodeResult(ResultStatus.SUCCESS, t.content(), t.metadata());
            case AgentResponse.Error e ->
                    new NodeResult(
                            ResultStatus.FAILURE,
                            e.message(),
                            Map.of("errorType", e.errorType().name()));
            case AgentResponse.ToolRequest _ ->
                    new NodeResult(
                            ResultStatus.FAILURE,
                            "Unexpected ToolRequest after loop exit",
                            Map.of());
        };
    }
}
