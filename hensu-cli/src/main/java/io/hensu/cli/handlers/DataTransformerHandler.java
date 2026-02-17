package io.hensu.cli.handlers;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.workflow.node.GenericNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/// Data transformer handler - applies transformations to string fields.
///
/// ### Config options:
///
/// - **inputField** - Source field name (default: "input")
/// - **outputField** - Target field name (default: "output")
/// - **operations** - List of operations: trim, lowercase, uppercase, normalize
///
///
/// ### The transformed value is stored in context under outputField.
@ApplicationScoped
public class DataTransformerHandler implements GenericNodeHandler {

    private static final Logger logger = Logger.getLogger(DataTransformerHandler.class.getName());
    public static final String TYPE = "data-transformer";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public NodeResult handle(GenericNode node, ExecutionContext context) {
        Map<String, Object> config = node.getConfig();
        String inputField = (String) config.getOrDefault("inputField", "input");
        String outputField = (String) config.getOrDefault("outputField", "output");

        Object inputObj = context.getState().getContext().get(inputField);
        if (inputObj == null) {
            return NodeResult.failure("Input field '" + inputField + "' not found");
        }

        String value = inputObj.toString();

        // Get operations list
        List<String> operations = (List<String>) config.getOrDefault("operations", List.of());

        // Apply operations
        for (String op : operations) {
            value =
                    switch (op.toLowerCase()) {
                        case "trim" -> value.trim();
                        case "lowercase" -> value.toLowerCase();
                        case "uppercase" -> value.toUpperCase();
                        case "normalize" -> value.replaceAll("\\s+", " ");
                        default -> value;
                    };
        }

        // Store result in context for subsequent nodes
        context.getState().getContext().put(outputField, value);

        logger.info("Transformed " + inputField + " -> " + outputField + ": " + value);

        return NodeResult.success(
                value,
                Map.of(
                        "input_field", inputField,
                        "output_field", outputField,
                        "operations_applied", operations));
    }
}
