package io.hensu.server.integration;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.workflow.node.GenericNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/// Test handler for `"validator"` generic nodes.
///
/// Auto-discovered by
/// {@link io.hensu.server.config.HensuEnvironmentProducer} `registerGenericHandlers()`
/// via CDI and registered in the {@link io.hensu.core.execution.executor.NodeExecutorRegistry}.
///
/// Returns a success result echoing the node's configuration map, allowing
/// integration tests to verify that generic node dispatch and config propagation
/// work end-to-end.
///
/// @implNote Stateless and thread-safe.
///
/// @see GenericNodeHandler for the handler contract
/// @see GenericNode for the node type this handler processes
@ApplicationScoped
public class TestValidatorHandler implements GenericNodeHandler {

    @Override
    public String getType() {
        return "validator";
    }

    /// Validates the node by echoing its configuration.
    ///
    /// @param node the generic node to handle, not null
    /// @param context the current execution context, not null
    /// @return success result containing the node's config, never null
    @Override
    public NodeResult handle(GenericNode node, ExecutionContext context) {
        Map<String, Object> config = node.getConfig();
        return NodeResult.success("Validation passed: " + config, Map.of("validated", true));
    }
}
