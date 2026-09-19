package io.hensu.core.execution.executor;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.agent.ToolCapable;
import io.hensu.core.agent.ToolSession;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.tool.CapabilityGaps;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolInvoker;
import io.hensu.core.tool.ToolRegistry;
import io.hensu.core.tool.ToolResultEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/// Stateless driver for the agent-native tool execution loop.
///
/// Dispatched from {@link AgentLifecycleRunner} when an agent declares tools
/// and implements {@link ToolCapable}. Resolves tools from the
/// {@link ToolRegistry}, opens a {@link ToolSession}, and iterates
/// tool-request/tool-result rounds until the agent emits a terminal response
/// or the tool call budget is exhausted. Every round is invoked through the
/// context's {@link ToolInvoker} and reported to the execution listener, so an
/// unattended run leaves a complete audit trail.
///
/// @implNote Package-private, stateless, no instances. Safe to call from any
/// thread including Virtual Threads.
final class ToolLoopRunner {

    private static final Logger logger = Logger.getLogger(ToolLoopRunner.class.getName());
    private static final int DEFAULT_MAX_TOOL_CALLS = 10;

    /// Source of the call identity that pairs a request record with its outcome.
    ///
    /// Sinks used to pair on node and tool name. That is correct only as long as no two
    /// concurrent calls share both, which is a property of the current graph shapes —
    /// parallel branches report as `node/branch`, sub-workflows run sequentially — and
    /// not of the audit layer. A minted id makes the pairing correct by construction, so
    /// a future branch kind cannot silently start attributing one call's arguments to
    /// another's outcome. The counter is per process, the widest scope any sink observes.
    private static final AtomicLong CALL_SEQUENCE = new AtomicLong();

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

        ToolInvoker toolInvoker = ctx.getToolInvoker();
        if (toolInvoker == null) {
            return new NodeResult(
                    ResultStatus.FAILURE,
                    "Agent '" + agentId + "' declares tools but no ToolInvoker is configured",
                    Map.of());
        }

        List<ToolDefinition> catalog;
        try {
            catalog = catalog(ctx);
        } catch (RuntimeException e) {
            // Colliding provider catalogs must fail the node, not escape the loop.
            return new NodeResult(
                    ResultStatus.FAILURE,
                    "Tool catalog is unusable for agent '" + agentId + "': " + e.getMessage(),
                    Map.of());
        }

        List<ToolDefinition> availableTools = resolveTools(agent, catalog);
        if (availableTools == null) {
            List<String> declared = agent.getConfig().getTools();
            List<String> available =
                    catalog != null
                            ? catalog.stream().map(ToolDefinition::name).toList()
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
                            ToolCallResult.of(
                                    toolRequest.toolName(),
                                    ToolCallStatus.BUDGET_EXHAUSTED,
                                    null,
                                    "Tool call budget exhausted ("
                                            + toolCallCount
                                            + "/"
                                            + maxToolCalls
                                            + "). Provide your final answer based on the tool results received so far.",
                                    null);
                    String callId = nextCallId();
                    fireCall(ctx, callId, eventSourceId, agentId, toolRequest, availableTools);
                    fireResult(ctx, callId, eventSourceId, agentId, exhaustion, 0L);
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
                        executeTool(toolRequest, availableTools, ctx, eventSourceId, agentId);

                if (result.status() == ToolCallStatus.CATALOG_ERROR) {
                    // A misconfigured catalog is an operator problem, not something
                    // the model should see and retry against. The attempt is audited;
                    // the node fails so transitions can route around it.
                    return new NodeResult(
                            ResultStatus.FAILURE,
                            "Tool catalog is unusable for agent '"
                                    + agentId
                                    + "': "
                                    + result.error(),
                            Map.of());
                }

                toolCallCount++;
                response = session.submit(result);
            }

            return toNodeResult(response);

        } finally {
            session.close();
        }
    }

    /// Materializes the tool catalog, or null when no registry is configured.
    ///
    /// @throws RuntimeException if the registry rejects its own catalog – two
    ///     providers exposing the same tool name raises IllegalStateException
    private static List<ToolDefinition> catalog(ExecutionContext ctx) {
        ToolRegistry registry = ctx.getToolRegistry();
        return registry != null ? registry.all() : null;
    }

    private static List<ToolDefinition> resolveTools(Agent agent, List<ToolDefinition> catalog) {
        if (catalog == null) {
            return null;
        }

        List<String> declaredNames = agent.getConfig().getTools();
        Map<String, ToolDefinition> byName =
                catalog.stream()
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
            ExecutionContext ctx,
            String eventSourceId,
            String agentId) {

        String toolName = toolRequest.toolName();
        String callId = nextCallId();

        // Unknown tool (hallucination) — feed back, don't hard-fail
        boolean known = availableTools.stream().anyMatch(t -> t.name().equals(toolName));
        if (!known) {
            List<String> available = availableTools.stream().map(ToolDefinition::name).toList();
            logger.warning(
                    "Agent requested unknown tool '" + toolName + "', available: " + available);
            ToolCallResult unknown =
                    ToolCallResult.of(
                            toolName,
                            ToolCallStatus.UNKNOWN_TOOL,
                            null,
                            "Unknown tool '" + toolName + "', available: " + available,
                            null);
            fireCall(ctx, callId, eventSourceId, agentId, toolRequest, availableTools);
            fireResult(ctx, callId, eventSourceId, agentId, unknown, 0L);
            recordGap(ctx, eventSourceId, toolRequest, unknown);
            return unknown;
        }

        fireCall(ctx, callId, eventSourceId, agentId, toolRequest, availableTools);
        long startNanos = System.nanoTime();

        try {
            ToolCallResult result =
                    ctx.getToolInvoker()
                            .call(toolName, arguments(toolRequest), ctx.getState().getContext());
            fireResult(ctx, callId, eventSourceId, agentId, result, elapsedMs(startNanos));
            recordGap(ctx, eventSourceId, toolRequest, result);
            return result;
        } catch (Exception e) {
            logger.warning("Tool execution failed for '" + toolName + "': " + e.getMessage());
            ToolCallResult failure =
                    ToolCallResult.failure(toolName, "Execution error: " + e.getMessage());
            fireResult(ctx, callId, eventSourceId, agentId, failure, elapsedMs(startNanos));
            return failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(AgentResponse.ToolRequest toolRequest) {
        return (Map<String, Object>) (Map<?, ?>) toolRequest.arguments();
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /// Appends a capability-gap record when the call was refused rather than merely
    /// unsuccessful, so transitions can route on a blocked run.
    ///
    /// Argument **key** names only: a `secret:` parameter's value must not survive into
    /// state that outlives the call. {@link CapabilityGaps#record} decides whether the
    /// outcome is a gap at all, so every outcome may be handed over.
    ///
    /// @param ctx execution context carrying the live state, not null
    /// @param nodeId id of the node whose agent asked, not null
    /// @param toolRequest the request the agent made, not null
    /// @param result the outcome of that request, not null
    private static void recordGap(
            ExecutionContext ctx,
            String nodeId,
            AgentResponse.ToolRequest toolRequest,
            ToolCallResult result) {
        CapabilityGaps.record(
                ctx.getState().getContext(),
                nodeId,
                toolRequest.toolName(),
                result.status(),
                arguments(toolRequest).keySet());
    }

    private static String nextCallId() {
        return Long.toUnsignedString(CALL_SEQUENCE.incrementAndGet());
    }

    private static void fireCall(
            ExecutionContext ctx,
            String callId,
            String eventSourceId,
            String agentId,
            AgentResponse.ToolRequest toolRequest,
            List<ToolDefinition> availableTools) {
        ExecutionListener listener = ctx.getListener();
        listener.onToolCall(
                ToolCallEvent.now(
                        callId,
                        eventSourceId,
                        agentId,
                        toolRequest.toolName(),
                        auditable(
                                arguments(toolRequest), definition(availableTools, toolRequest))));
    }

    private static void fireResult(
            ExecutionContext ctx,
            String callId,
            String eventSourceId,
            String agentId,
            ToolCallResult result,
            long durationMs) {
        ExecutionListener listener = ctx.getListener();
        listener.onToolResult(
                ToolResultEvent.now(callId, eventSourceId, agentId, result, durationMs));
    }

    private static ToolDefinition definition(
            List<ToolDefinition> availableTools, AgentResponse.ToolRequest toolRequest) {
        return availableTools.stream()
                .filter(tool -> tool.name().equals(toolRequest.toolName()))
                .findFirst()
                .orElse(null);
    }

    /// Replaces the values of sensitive parameters before the arguments are audited.
    ///
    /// Redaction happens here rather than in a sink because the loop is the last
    /// place that holds both the arguments and the schema describing them: the
    /// provider still receives the real value, and no listener ever sees it.
    ///
    /// @param arguments arguments as the agent supplied them, not null
    /// @param definition the resolved tool, or null for an unresolvable request
    /// @return arguments safe to audit, never null
    private static Map<String, Object> auditable(
            Map<String, Object> arguments, ToolDefinition definition) {
        if (definition == null || arguments == null || arguments.isEmpty()) {
            return arguments;
        }

        List<String> secrets =
                definition.parameters().stream()
                        .filter(ToolDefinition.ParameterDef::sensitive)
                        .map(ToolDefinition.ParameterDef::name)
                        .toList();
        if (secrets.isEmpty()) {
            return arguments;
        }

        Map<String, Object> redacted = new LinkedHashMap<>(arguments);
        for (String secret : secrets) {
            if (redacted.containsKey(secret)) {
                redacted.put(secret, ToolCallEvent.REDACTED);
            }
        }
        return redacted;
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
