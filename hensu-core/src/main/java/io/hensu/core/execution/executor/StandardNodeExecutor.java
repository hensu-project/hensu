package io.hensu.core.execution.executor;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.agent.AgentResponse.Error;
import io.hensu.core.agent.AgentResponse.TextResponse;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.enricher.EngineVariablePromptEnricher;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.workflow.node.StandardNode;
import java.util.Map;
import java.util.logging.Logger;

/// Executes standard workflow nodes by invoking the configured agent.
///
/// Resolves the prompt template, enriches it with engine variable format requirements
/// (rubric criteria, score, approved) via {@link EngineVariablePromptEnricher}, then
/// delegates to the configured agent.
///
/// This executor is stateless — all dependencies are obtained from
/// {@link ExecutionContext}.
///
/// ### Features
///
/// - Resolves template variables in the prompt
/// - Supports prompt override for backtracked execution
/// - Injects rubric criteria and engine variable requirements before the agent call
/// - Notifies listeners before/after agent execution
///
/// @implNote **Immutable after construction.** Stateless; safe to share across
/// Virtual Threads.
public class StandardNodeExecutor implements NodeExecutor<StandardNode> {

    private static final Logger logger = Logger.getLogger(StandardNodeExecutor.class.getName());

    @Override
    public Class<StandardNode> getNodeType() {
        return StandardNode.class;
    }

    @Override
    public NodeResult execute(StandardNode node, ExecutionContext context) {
        HensuState state = context.getState();
        ExecutionListener listener = context.getListener();
        AgentRegistry agentRegistry = context.getAgentRegistry();
        TemplateResolver templateResolver = context.getTemplateResolver();

        Agent agent =
                agentRegistry
                        .getAgent(node.getAgentId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Agent not found: " + node.getAgentId()));

        Object promptOverride = state.getContext().remove("_prompt_override");
        String resolvedPrompt;
        if (promptOverride != null) {
            resolvedPrompt = promptOverride.toString();
        } else {
            resolvedPrompt =
                    node.getPrompt() != null
                            ? templateResolver.resolve(node.getPrompt(), state.getContext())
                            : "";
        }

        resolvedPrompt = EngineVariablePromptEnricher.DEFAULT.enrich(resolvedPrompt, node, context);

        logger.info("Executing node: " + node.getId() + " with agent: " + node.getAgentId());

        // Propagate current node ID into context for agent awareness
        state.getContext().put("current_node", node.getId());

        // Remove stale output variables so they don't shadow this node's computation.
        // Template resolution already happened above, so this is safe.
        // OutputExtractionPostProcessor will repopulate these with fresh values after execution.
        node.getWrites().forEach(state.getContext().keySet()::remove);

        // Notify listener before agent execution
        listener.onAgentStart(node.getId(), node.getAgentId(), resolvedPrompt);

        AgentResponse response = agent.execute(resolvedPrompt, state.getContext());

        // Notify listener after agent execution
        listener.onAgentComplete(node.getId(), node.getAgentId(), response);

        return switch (response) {
            case TextResponse t -> new NodeResult(ResultStatus.SUCCESS, t.content(), t.metadata());
            case Error e -> new NodeResult(ResultStatus.FAILURE, e.message(), Map.of());
            default -> new NodeResult(ResultStatus.SUCCESS, "Response received", Map.of());
        };
    }
}
