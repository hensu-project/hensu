package io.hensu.cli.handlers;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.workflow.node.GenericNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/// Validator handler - validates fields based on config.
///
/// Config options:
///
/// - **field** - Field name to validate (default: "input")
/// - **required** - Whether field is required (default: false)
/// - **minLength** - Minimum string length
/// - **maxLength** - Maximum string length
/// - **pattern** - Regex pattern to match
/// - **errorMessage** - Custom error message for pattern mismatch
@ApplicationScoped
public class ValidatorHandler implements GenericNodeHandler {

    private static final Logger logger = Logger.getLogger(ValidatorHandler.class.getName());
    public static final String TYPE = "validator";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public NodeResult handle(GenericNode node, ExecutionContext context) {
        Map<String, Object> config = node.getConfig();
        String fieldName = (String) config.getOrDefault("field", "input");
        Object fieldValueObj = context.getState().getContext().get(fieldName);
        String fieldValue = fieldValueObj != null ? fieldValueObj.toString() : null;

        List<String> errors = new ArrayList<>();

        // Required check
        if (Boolean.TRUE.equals(config.get("required"))
                && (fieldValue == null || fieldValue.isBlank())) {
            errors.add(fieldName + " is required");
        }

        if (fieldValue != null && !fieldValue.isEmpty()) {
            // Length checks
            Object minLengthObj = config.get("minLength");
            if (minLengthObj instanceof Number minLength) {
                if (fieldValue.length() < minLength.intValue()) {
                    errors.add(fieldName + " must be at least " + minLength + " characters");
                }
            }

            Object maxLengthObj = config.get("maxLength");
            if (maxLengthObj instanceof Number maxLength) {
                if (fieldValue.length() > maxLength.intValue()) {
                    errors.add(fieldName + " must be at most " + maxLength + " characters");
                }
            }

            // Pattern check
            String pattern = (String) config.get("pattern");
            if (pattern != null && !Pattern.matches(pattern, fieldValue)) {
                String errorMessage =
                        (String) config.getOrDefault("errorMessage", "Invalid format");
                errors.add(errorMessage);
            }
        }

        if (errors.isEmpty()) {
            logger.info("Validation passed for field: " + fieldName);
            return NodeResult.success(
                    "Validation passed for " + fieldName,
                    Map.of("validated_field", fieldName, "validated", true));
        } else {
            logger.warning("Validation failed: " + String.join("; ", errors));
            return new NodeResult(
                    ResultStatus.FAILURE, String.join("; ", errors), Map.of("errors", errors));
        }
    }
}
