package io.hensu.cli.handlers;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.workflow.node.GenericNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.logging.Logger;

/// Generic node handler for making external API calls (currently simulated).
///
/// Reads request data from context, makes an HTTP call, and stores the response
/// back to context for subsequent nodes.
///
/// ### Config Options
/// | Key | Type | Default | Description |
/// |-----|------|---------|-------------|
/// | `endpoint` | String | `""` | API endpoint URL |
/// | `method` | String | `"GET"` | HTTP method (GET, POST, PUT, DELETE) |
/// | `inputField` | String | `"input"` | Context field containing request data |
/// | `outputField` | String | `"response"` | Context field to store response |
/// | `timeout` | Number | `30000` | Request timeout in milliseconds |
///
/// @apiNote **Currently simulated** - returns mock response. Real HTTP implementation
/// should use {@link io.hensu.cli.action.CLIActionExecutor} HttpCall action instead.
///
/// @apiNote Side effect: Modifies workflow context by storing response in `outputField`.
/// @see GenericNodeHandler
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
