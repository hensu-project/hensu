package io.hensu.cli.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowMetadata;
import io.hensu.core.workflow.node.GenericNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ValidatorHandlerTest {

    private ValidatorHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ValidatorHandler();
    }

    @Test
    void shouldReturnCorrectType() {
        assertThat(handler.getType()).isEqualTo("validator");
    }

    // ========== Required Validation Tests ==========

    @Test
    void shouldFailWhenRequiredFieldIsNull() {
        GenericNode node = createNode(Map.of("field", "input", "required", true));
        ExecutionContext context = createContext(Map.of());

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getOutput().toString()).contains("input is required");
    }

    @Test
    void shouldFailWhenRequiredFieldIsBlank() {
        GenericNode node = createNode(Map.of("field", "input", "required", true));
        ExecutionContext context = createContext(Map.of("input", "   "));

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getOutput().toString()).contains("input is required");
    }

    @Test
    void shouldFailWhenRequiredFieldIsEmptyString() {
        GenericNode node = createNode(Map.of("field", "input", "required", true));
        ExecutionContext context = createContext(Map.of("input", ""));

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getOutput().toString()).contains("input is required");
    }

    @Test
    void shouldPassWhenRequiredFieldHasValue() {
        GenericNode node = createNode(Map.of("field", "input", "required", true));
        ExecutionContext context = createContext(Map.of("input", "valid value"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldPassWhenFieldIsNullButNotRequired() {
        GenericNode node = createNode(Map.of("field", "input", "required", false));
        ExecutionContext context = createContext(Map.of());

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    // ========== MinLength Validation Tests ==========

    @Test
    void shouldFailWhenValueShorterThanMinLength() {
        GenericNode node = createNode(Map.of("field", "input", "minLength", 10));
        ExecutionContext context = createContext(Map.of("input", "short"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getOutput().toString()).contains("must be at least 10 characters");
    }

    @Test
    void shouldPassWhenValueEqualsMinLength() {
        GenericNode node = createNode(Map.of("field", "input", "minLength", 5));
        ExecutionContext context = createContext(Map.of("input", "12345"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldPassWhenValueLongerThanMinLength() {
        GenericNode node = createNode(Map.of("field", "input", "minLength", 5));
        ExecutionContext context = createContext(Map.of("input", "longer than minimum"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldSkipMinLengthCheckWhenFieldIsNull() {
        GenericNode node = createNode(Map.of("field", "input", "minLength", 10));
        ExecutionContext context = createContext(Map.of());

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    // ========== MaxLength Validation Tests ==========

    @Test
    void shouldFailWhenValueLongerThanMaxLength() {
        GenericNode node = createNode(Map.of("field", "input", "maxLength", 5));
        ExecutionContext context = createContext(Map.of("input", "this is too long"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getOutput().toString()).contains("must be at most 5 characters");
    }

    @Test
    void shouldPassWhenValueEqualsMaxLength() {
        GenericNode node = createNode(Map.of("field", "input", "maxLength", 5));
        ExecutionContext context = createContext(Map.of("input", "12345"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldPassWhenValueShorterThanMaxLength() {
        GenericNode node = createNode(Map.of("field", "input", "maxLength", 100));
        ExecutionContext context = createContext(Map.of("input", "short"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldSkipMaxLengthCheckWhenFieldIsNull() {
        GenericNode node = createNode(Map.of("field", "input", "maxLength", 5));
        ExecutionContext context = createContext(Map.of());

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    // ========== Pattern Validation Tests ==========

    @Test
    void shouldFailWhenValueDoesNotMatchPattern() {
        GenericNode node =
                createNode(Map.of("field", "email", "pattern", "^[a-z]+@[a-z]+\\.[a-z]+$"));
        ExecutionContext context = createContext(Map.of("email", "invalid-email"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getOutput().toString()).contains("Invalid format");
    }

    @Test
    void shouldPassWhenValueMatchesPattern() {
        GenericNode node =
                createNode(Map.of("field", "email", "pattern", "^[a-z]+@[a-z]+\\.[a-z]+$"));
        ExecutionContext context = createContext(Map.of("email", "test@example.com"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldUseCustomErrorMessageForPatternFailure() {
        GenericNode node =
                createNode(
                        Map.of(
                                "field", "email",
                                "pattern", "^[a-z]+@[a-z]+\\.[a-z]+$",
                                "errorMessage", "Must be a valid email address"));
        ExecutionContext context = createContext(Map.of("email", "invalid"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getOutput().toString()).contains("Must be a valid email address");
    }

    @Test
    void shouldSkipPatternCheckWhenFieldIsNull() {
        GenericNode node = createNode(Map.of("field", "input", "pattern", "^[0-9]+$"));
        ExecutionContext context = createContext(Map.of());

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldSkipPatternCheckWhenFieldIsEmpty() {
        GenericNode node = createNode(Map.of("field", "input", "pattern", "^[0-9]+$"));
        ExecutionContext context = createContext(Map.of("input", ""));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    // ========== Combined Validation Tests ==========

    @Test
    void shouldCollectMultipleErrors() {
        GenericNode node =
                createNode(
                        Map.of(
                                "field",
                                "input",
                                "required",
                                true,
                                "minLength",
                                10,
                                "maxLength",
                                5));
        // Input must be >5 chars (violate maxLength) and <10 chars (violate minLength)
        ExecutionContext context = createContext(Map.of("input", "abcdefg"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        String output = result.getOutput().toString();
        assertThat(output).contains("at least 10");
        assertThat(output).contains("at most 5");
    }

    @Test
    void shouldValidateAllConstraintsTogether() {
        GenericNode node =
                createNode(
                        Map.of(
                                "field",
                                "username",
                                "required",
                                true,
                                "minLength",
                                3,
                                "maxLength",
                                20,
                                "pattern",
                                "^[a-z0-9_]+$",
                                "errorMessage",
                                "Username must contain only lowercase letters, numbers, and underscores"));
        ExecutionContext context = createContext(Map.of("username", "valid_user123"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldFailMultipleConstraints() {
        GenericNode node = createNode(Map.of("field", "input", "required", true, "minLength", 100));
        ExecutionContext context = createContext(Map.of("input", ""));

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getOutput().toString()).contains("required");
    }

    // ========== Default Field Name Tests ==========

    @Test
    void shouldUseDefaultFieldNameWhenNotSpecified() {
        GenericNode node = createNode(Map.of("required", true));
        ExecutionContext context = createContext(Map.of("input", "value"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldFailWithDefaultFieldNameWhenNotFound() {
        GenericNode node = createNode(Map.of("required", true));
        ExecutionContext context = createContext(Map.of());

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getOutput().toString()).contains("input is required");
    }

    // ========== Metadata Tests ==========

    @Test
    void shouldIncludeMetadataOnSuccess() {
        GenericNode node = createNode(Map.of("field", "username", "required", true));
        ExecutionContext context = createContext(Map.of("username", "testuser"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMetadata()).containsEntry("validated_field", "username");
        assertThat(result.getMetadata()).containsEntry("validated", true);
    }

    @Test
    void shouldIncludeErrorsListOnFailure() {
        GenericNode node = createNode(Map.of("field", "input", "required", true, "minLength", 10));
        ExecutionContext context = createContext(Map.of("input", ""));

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getMetadata()).containsKey("errors");
        List<String> errors = (List<String>) result.getMetadata().get("errors");
        assertThat(errors).isNotEmpty();
    }

    // ========== Edge Cases ==========

    @Test
    void shouldHandleNonStringInput() {
        GenericNode node = createNode(Map.of("field", "count", "minLength", 2));
        ExecutionContext context = createContext(Map.of("count", 12345));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldHandleBooleanInput() {
        GenericNode node = createNode(Map.of("field", "flag", "pattern", "true|false"));
        ExecutionContext context = createContext(Map.of("flag", true));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldValidateNumericPattern() {
        GenericNode node =
                createNode(
                        Map.of(
                                "field",
                                "code",
                                "pattern",
                                "^[0-9]{4}$",
                                "errorMessage",
                                "Must be a 4-digit code"));
        ExecutionContext context = createContext(Map.of("code", "1234"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldFailNumericPatternValidation() {
        GenericNode node =
                createNode(
                        Map.of(
                                "field",
                                "code",
                                "pattern",
                                "^[0-9]{4}$",
                                "errorMessage",
                                "Must be a 4-digit code"));
        ExecutionContext context = createContext(Map.of("code", "12"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getOutput().toString()).contains("Must be a 4-digit code");
    }

    @Test
    void shouldReturnSuccessMessageOnPass() {
        GenericNode node = createNode(Map.of("field", "name", "required", true));
        ExecutionContext context = createContext(Map.of("name", "John"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput().toString()).contains("Validation passed for name");
    }

    @Test
    void shouldSeparateMultipleErrorsWithSemicolon() {
        GenericNode node =
                createNode(
                        Map.of(
                                "field", "value",
                                "minLength", 100,
                                "maxLength", 1,
                                "pattern", "^[A-Z]+$",
                                "errorMessage", "Must be uppercase"));
        ExecutionContext context = createContext(Map.of("value", "ab"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        String output = result.getOutput().toString();
        assertThat(output).contains(";");
    }

    private GenericNode createNode(Map<String, Object> config) {
        return GenericNode.builder()
                .id("validator-node")
                .executorType("validator")
                .config(config)
                .transitionRules(List.of(new SuccessTransition("next")))
                .build();
    }

    private ExecutionContext createContext(Map<String, Object> contextData) {
        HashMap<String, Object> mutableContext = new HashMap<>(contextData);
        HensuState state = new HensuState(mutableContext, "workflow-1", "validator-node", null);

        GenericNode startNode =
                GenericNode.builder()
                        .id("start")
                        .executorType("validator")
                        .config(Map.of())
                        .transitionRules(List.of())
                        .build();

        Workflow workflow =
                Workflow.builder()
                        .id("test-workflow")
                        .version("1.0.0")
                        .metadata(
                                new WorkflowMetadata(
                                        "test-workflow",
                                        "Test workflow",
                                        "tester",
                                        Instant.now(),
                                        List.of()))
                        .nodes(Map.of("start", startNode))
                        .agents(Map.of())
                        .startNode("start")
                        .build();

        return ExecutionContext.builder().state(state).workflow(workflow).build();
    }
}
