package io.hensu.core.execution.executor;

import io.hensu.core.exception.NodeExecutorNotFound;
import io.hensu.core.workflow.node.GenericNode;
import java.util.logging.Logger;

/// Executor for GenericNode that delegates to registered handlers.
///
/// This executor looks up the appropriate {@link GenericNodeHandler} based on the node's
/// `executorType` and invokes it. This allows multiple GenericNodes to share handlers or have
/// unique handlers.
///
/// The handler is retrieved from {@link NodeExecutorRegistry#getGenericHandler(String)} at
/// execution time, enabling dynamic registration of handlers.
public class GenericNodeExecutor implements NodeExecutor<GenericNode> {

    private static final Logger logger = Logger.getLogger(GenericNodeExecutor.class.getName());

    @Override
    public Class<GenericNode> getNodeType() {
        return GenericNode.class;
    }

    @Override
    public NodeResult execute(GenericNode node, ExecutionContext context) throws Exception {
        NodeExecutorRegistry registry = context.getNodeExecutorRegistry();
        String executorType = node.getExecutorType();

        logger.info(
                "Executing generic node: " + node.getId() + " with executor type: " + executorType);

        // Look up the handler by executor type
        GenericNodeHandler handler =
                registry.getGenericHandler(executorType)
                        .orElseThrow(
                                () ->
                                        new NodeExecutorNotFound(
                                                "No handler registered for generic executor type: "
                                                        + executorType
                                                        + ". Register a handler using registry.registerGenericHandler(\""
                                                        + executorType
                                                        + "\", handler)"));

        // Delegate to the handler
        NodeResult result = handler.handle(node, context);

        logger.info(
                "Generic node " + node.getId() + " completed with status: " + result.getStatus());

        return result;
    }
}
