package io.hensu.core.execution.executor;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.workflow.node.ActionNode;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/// Executes action nodes, processing actions and continuing workflow execution.
///
/// This executor is stateless - all dependencies are obtained from ExecutionContext.
///
/// ### Features
///
/// - Executes all configured actions via ActionExecutor
/// - Falls back to logging if no ActionExecutor configured
/// - Returns success/failure based on action results
public class ActionNodeExecutor implements NodeExecutor<ActionNode> {

    private static final Logger logger = Logger.getLogger(ActionNodeExecutor.class.getName());

    @Override
    public Class<ActionNode> getNodeType() {
        return ActionNode.class;
    }

    @Override
    public NodeResult execute(ActionNode node, ExecutionContext context) {
        HensuState state = context.getState();
        TemplateResolver templateResolver = context.getTemplateResolver();
        ActionExecutor actionExecutor = context.getActionExecutor();

        logger.info("Executing action node: " + node.getId());

        // TODO Contents of collection 'results' are updated, but never queried
        List<ActionResult> results = new ArrayList<>();
        boolean allSucceeded = true;

        for (Action action : node.getActions()) {
            if (actionExecutor != null) {
                ActionResult result = actionExecutor.execute(action, state.getContext());
                results.add(result);

                if (result.success()) {
                    logger.info("Action executed: " + result.message());
                } else {
                    logger.warning("Action failed: " + result.message());
                    allSucceeded = false;
                }
            } else {
                // Fallback: just log the actions
                executeWithLogging(action, templateResolver, state);
            }
        }

        // Return success or failure based on action results
        if (allSucceeded) {
            return NodeResult.success("Actions completed", null);
        } else {
            return new NodeResult(ResultStatus.FAILURE, "One or more actions failed", null);
        }
    }

    /// Fallback execution that just logs actions (for environments without ActionExecutor).
    private void executeWithLogging(
            Action action, TemplateResolver templateResolver, HensuState state) {
        switch (action) {
            case Action.Notify notify -> {
                String message = templateResolver.resolve(notify.getMessage(), state.getContext());
                logger.info("[NOTIFY:" + notify.getChannel() + "] " + message);
            }
            case Action.Execute execute ->
                    logger.info(
                            "[EXECUTE] Would run command ID: "
                                    + execute.getCommandId()
                                    + " (ActionExecutor not configured)");
            case Action.HttpCall httpCall -> {
                String endpoint =
                        templateResolver.resolve(httpCall.getEndpoint(), state.getContext());
                logger.info(
                        "[HTTP] Would call: "
                                + httpCall.getMethod()
                                + " "
                                + endpoint
                                + " (ActionExecutor not configured)");
            }
        }
    }
}
