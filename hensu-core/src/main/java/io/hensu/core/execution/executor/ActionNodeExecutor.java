package io.hensu.core.execution.executor;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.node.ActionNode;
import java.util.Map;
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
        ActionExecutor actionExecutor = context.getActionExecutor();

        logger.info("Executing action node: " + node.getId());

        boolean allSucceeded = true;

        for (Action action : node.getActions()) {
            if (actionExecutor != null) {
                ActionResult result = actionExecutor.execute(action, state.getContext());

                if (result.success()) {
                    logger.info("Action executed: " + result.message());
                } else {
                    logger.warning("Action failed: " + result.message());
                    allSucceeded = false;
                }
            } else {
                // Fallback: just log the actions
                executeWithLogging(action);
            }
        }

        // Return success or failure based on action results
        if (allSucceeded) {
            return NodeResult.success("Actions completed", Map.of());
        } else {
            return new NodeResult(ResultStatus.FAILURE, "One or more actions failed", Map.of());
        }
    }

    /// Fallback execution that just logs actions (for environments without ActionExecutor).
    private void executeWithLogging(Action action) {
        switch (action) {
            case Action.Send send ->
                    logger.info(
                            "[SEND] Would invoke handler: "
                                    + send.getHandlerId()
                                    + " (ActionExecutor not configured)");
            case Action.Execute execute ->
                    logger.info(
                            "[EXECUTE] Would run command ID: "
                                    + execute.getCommandId()
                                    + " (ActionExecutor not configured)");
        }
    }
}
