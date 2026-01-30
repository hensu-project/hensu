package io.hensu.cli.handlers;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.workflow.node.GenericNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.logging.Logger;

/// API caller handler - makes external API calls.
///
/// ### Config options:
///
/// - **endpoint** - API endpoint URL
/// - **method** - HTTP method (GET, POST, etc.)
/// - **inputField** - Field containing request data
/// - **outputField** - Field to store response
/// - **timeout** - Request timeout in ms
///
///
/// ### Note: Currently simulated. Real implementation would use HTTP client.
@ApplicationScoped
public class ApiCallerHandler implements GenericNodeHandler {

    private static final Logger logger = Logger.getLogger(ApiCallerHandler.class.getName());
    public static final String TYPE = "api-caller";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public NodeResult handle(GenericNode node, ExecutionContext context) {
        Map<String, Object> config = node.getConfig();
        String endpoint = (String) config.getOrDefault("endpoint", "");
        String method = (String) config.getOrDefault("method", "GET");
        String inputField = (String) config.getOrDefault("inputField", "input");
        String outputField = (String) config.getOrDefault("outputField", "response");
        int timeout = config.get("timeout") instanceof Number n ? n.intValue() : 30000;

        Object inputData = context.getState().getContext().get(inputField);
        if (inputData == null) {
            return NodeResult.failure("Input field '" + inputField + "' not found");
        }

        logger.info("Calling " + method + " " + endpoint + " (timeout: " + timeout + "ms)");
        logger.info("Request data: " + inputData);

        // Simulated API response (in real implementation, use HTTP client)
        String apiResponse =
                """
                {
                    "status": "success",
                    "data": {
                        "processed": true,
                        "result": "Processed: %s",
                        "timestamp": %d
                    }
                }
                """
                        .formatted(inputData, System.currentTimeMillis());

        // Store response in context
        context.getState().getContext().put(outputField, apiResponse);

        logger.info("API response stored in: " + outputField);

        return NodeResult.success(
                apiResponse,
                Map.of(
                        "endpoint", endpoint,
                        "method", method,
                        "status_code", 200));
    }
}
