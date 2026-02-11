package io.hensu.core.execution.executor;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.agent.AgentResponse.Error;
import io.hensu.core.agent.AgentResponse.TextResponse;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.workflow.node.StandardNode;
import java.util.Map;
import java.util.logging.Logger;

/// Executes standard workflow nodes by invoking the configured agent.
///
/// This executor is stateless - all dependencies are obtained from ExecutionContext.
///
/// ### Features
///
/// - Resolves template variables in the prompt
/// - Supports prompt override for backtracked execution
/// - Notifies listeners before/after agent execution
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

        // Check for prompt override (from manual backtrack with edited prompt)
        String overrideKey = "_prompt_override_" + node.getId();
        String promptOverride = (String) state.getContext().get(overrideKey);

        String resolvedPrompt;
        if (promptOverride != null) {
            // Use the edited prompt and clear the override
            resolvedPrompt = templateResolver.resolve(promptOverride, state.getContext());
            state.getContext().remove(overrideKey);
            logger.info("Using edited prompt for node: " + node.getId());
        } else {
            resolvedPrompt =
                    node.getPrompt() != null
                            ? templateResolver.resolve(node.getPrompt(), state.getContext())
                            : "";
        }

        logger.info("Executing node: " + node.getId() + " with agent: " + node.getAgentId());

        // Propagate current node ID into context for agent awareness
        state.getContext().put("current_node", node.getId());

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
