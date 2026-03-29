package io.hensu.core.execution.executor;

import io.hensu.core.state.HensuState;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.workflow.node.StandardNode;
import java.util.logging.Logger;

/// Executes standard workflow nodes by invoking the configured agent.
///
/// Handles node-specific concerns (prompt override for backtracked execution,
/// {@code current_node} propagation, stale writes cleanup) then delegates the
/// agent call lifecycle to {@link AgentLifecycleRunner}.
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

        // Node-specific: prompt override for backtracked execution
        Object promptOverride = state.getContext().remove("_prompt_override");
        String prompt = promptOverride != null ? promptOverride.toString() : node.getPrompt();

        logger.info("Executing node: " + node.getId() + " with agent: " + node.getAgentId());

        // Propagate current node ID into context for agent awareness
        state.getContext().put("current_node", node.getId());

        // Resolve template while writes variables are still in context
        TemplateResolver resolver = context.getTemplateResolver();
        String resolved = prompt != null ? resolver.resolve(prompt, state.getContext()) : "";

        // Remove stale output variables so they don't shadow this node's computation.
        // OutputExtractionPostProcessor will repopulate these with fresh values after execution.
        node.getWrites().forEach(state.getContext().keySet()::remove);

        // Delegate to shared agent execution lifecycle (prompt already resolved)
        return AgentLifecycleRunner.execute(
                node.getId(), node.getAgentId(), resolved, node, context);
    }
}
