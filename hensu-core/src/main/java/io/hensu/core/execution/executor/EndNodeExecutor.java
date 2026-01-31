package io.hensu.core.execution.executor;

import io.hensu.core.workflow.node.EndNode;
import java.util.logging.Logger;

/// Executes end nodes.
///
/// This executor is stateless.
///
/// ### Features
///
/// - Logs the end state reached
/// - Returns a proper end result (never null)
public class EndNodeExecutor implements NodeExecutor<EndNode> {

    private static final Logger logger = Logger.getLogger(EndNodeExecutor.class.getName());

    @Override
    public Class<EndNode> getNodeType() {
        return EndNode.class;
    }

    @Override
    public NodeResult execute(EndNode node, ExecutionContext context) {
        logger.info("Reached end node: " + node.getId() + " (" + node.getExitStatus() + ")");
        return NodeResult.end();
    }
}
