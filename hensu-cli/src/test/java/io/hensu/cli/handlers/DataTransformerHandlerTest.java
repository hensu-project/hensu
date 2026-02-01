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

class DataTransformerHandlerTest {

    private DataTransformerHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DataTransformerHandler();
    }

    @Test
    void shouldReturnCorrectType() {
        assertThat(handler.getType()).isEqualTo("data-transformer");
    }

    @Test
    void shouldFailWhenInputFieldNotFound() {
        GenericNode node = createNode(Map.of("inputField", "missing", "outputField", "output"));
        ExecutionContext context = createContext(Map.of());

        NodeResult result = handler.handle(node, context);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getOutput().toString()).contains("Input field 'missing' not found");
    }

    @Test
    void shouldUseDefaultInputFieldWhenNotSpecified() {
        GenericNode node = createNode(Map.of("outputField", "output"));
        ExecutionContext context = createContext(Map.of("input", "test value"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("test value");
    }

    @Test
    void shouldUseDefaultOutputFieldWhenNotSpecified() {
        GenericNode node = createNode(Map.of("inputField", "source"));
        ExecutionContext context = createContext(Map.of("source", "test value"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(context.getState().getContext().get("output")).isEqualTo("test value");
    }

    @Test
    void shouldApplyTrimOperation() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("trim")));
        ExecutionContext context = createContext(Map.of("input", "  hello world  "));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("hello world");
        assertThat(context.getState().getContext().get("output")).isEqualTo("hello world");
    }

    @Test
    void shouldApplyLowercaseOperation() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("lowercase")));
        ExecutionContext context = createContext(Map.of("input", "HELLO WORLD"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("hello world");
    }

    @Test
    void shouldApplyUppercaseOperation() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("uppercase")));
        ExecutionContext context = createContext(Map.of("input", "hello world"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("HELLO WORLD");
    }

    @Test
    void shouldApplyNormalizeOperation() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("normalize")));
        ExecutionContext context = createContext(Map.of("input", "hello    world   test"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("hello world test");
    }

    @Test
    void shouldApplyMultipleOperationsInOrder() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("trim", "lowercase", "normalize")));
        ExecutionContext context = createContext(Map.of("input", "  HELLO    WORLD  "));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("hello world");
    }

    @Test
    void shouldIgnoreUnknownOperations() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("unknown", "lowercase")));
        ExecutionContext context = createContext(Map.of("input", "HELLO"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("hello");
    }

    @Test
    void shouldHandleEmptyOperationsList() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of()));
        ExecutionContext context = createContext(Map.of("input", "unchanged"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("unchanged");
    }

    @Test
    void shouldHandleNoOperationsConfig() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output"));
        ExecutionContext context = createContext(Map.of("input", "no ops"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("no ops");
    }

    @Test
    void shouldStoreResultInContext() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "source",
                                "outputField", "target",
                                "operations", List.of("uppercase")));
        ExecutionContext context = createContext(Map.of("source", "test"));

        handler.handle(node, context);

        assertThat(context.getState().getContext().get("target")).isEqualTo("TEST");
    }

    @Test
    void shouldIncludeMetadataInResult() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("trim")));
        ExecutionContext context = createContext(Map.of("input", " test "));

        NodeResult result = handler.handle(node, context);

        assertThat(result.getMetadata()).containsEntry("input_field", "input");
        assertThat(result.getMetadata()).containsEntry("output_field", "output");
        assertThat(result.getMetadata()).containsKey("operations_applied");
    }

    @Test
    void shouldHandleNumericInputByConvertingToString() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "number",
                                "outputField", "output"));
        ExecutionContext context = createContext(Map.of("number", 12345));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("12345");
    }

    @Test
    void shouldHandleBooleanInputByConvertingToString() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "flag",
                                "outputField", "output"));
        ExecutionContext context = createContext(Map.of("flag", true));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("true");
    }

    @Test
    void shouldHandleOperationCaseInsensitively() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("UPPERCASE", "TRIM")));
        ExecutionContext context = createContext(Map.of("input", " hello "));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("HELLO");
    }

    @Test
    void shouldPreserveOtherContextValues() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("uppercase")));
        ExecutionContext context = createContext(Map.of("input", "test", "other", "preserved"));

        handler.handle(node, context);

        assertThat(context.getState().getContext().get("other")).isEqualTo("preserved");
        assertThat(context.getState().getContext().get("output")).isEqualTo("TEST");
    }

    @Test
    void shouldHandleEmptyStringInput() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("trim", "uppercase")));
        ExecutionContext context = createContext(Map.of("input", ""));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("");
    }

    @Test
    void shouldHandleWhitespaceOnlyInput() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("trim")));
        ExecutionContext context = createContext(Map.of("input", "   "));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("");
    }

    @Test
    void shouldHandleNewlinesInNormalize() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("normalize")));
        ExecutionContext context = createContext(Map.of("input", "hello\n\nworld"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("hello world");
    }

    @Test
    void shouldHandleTabsInNormalize() {
        GenericNode node =
                createNode(
                        Map.of(
                                "inputField", "input",
                                "outputField", "output",
                                "operations", List.of("normalize")));
        ExecutionContext context = createContext(Map.of("input", "hello\t\tworld"));

        NodeResult result = handler.handle(node, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("hello world");
    }

    private GenericNode createNode(Map<String, Object> config) {
        return GenericNode.builder()
                .id("transformer-node")
                .executorType("data-transformer")
                .config(config)
                .transitionRules(List.of(new SuccessTransition("next")))
                .build();
    }

    private ExecutionContext createContext(Map<String, Object> contextData) {
        HashMap<String, Object> mutableContext = new HashMap<>(contextData);
        HensuState state = new HensuState(mutableContext, "workflow-1", "transformer-node", null);

        GenericNode startNode =
                GenericNode.builder()
                        .id("start")
                        .executorType("data-transformer")
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
