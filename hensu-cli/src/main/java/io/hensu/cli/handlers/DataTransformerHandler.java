package io.hensu.cli.handlers;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.workflow.node.GenericNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/// Generic node handler for applying string transformations to context fields.
///
/// Reads a value from context, applies a chain of transformations, and stores the
/// result back to context for subsequent nodes.
///
/// ### Config Options
/// | Key | Type | Default | Description |
/// |-----|------|---------|-------------|
/// | `inputField` | String | `"input"` | Source field name in context |
/// | `outputField` | String | `"output"` | Target field name in context |
/// | `operations` | List&lt;String&gt; | `[]` | Transformations to apply in order |
///
/// ### Supported Operations
/// - `trim` - Remove leading/trailing whitespace
/// - `lowercase` - Convert to lowercase
/// - `uppercase` - Convert to uppercase
/// - `normalize` - Collapse multiple spaces to single space
///
/// @apiNote Side effect: Modifies workflow context by storing result in `outputField`.
/// @see GenericNodeHandler
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
