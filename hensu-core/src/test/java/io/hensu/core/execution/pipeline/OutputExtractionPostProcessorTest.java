package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OutputExtractionProcessor")
class OutputExtractionPostProcessorTest {

    private OutputExtractionPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OutputExtractionPostProcessor();
    }

    @Test
    @DisplayName("stores raw output in context keyed by node ID")
    void shouldStoreRawOutput() {
        var ctx = contextWith("my-node", "Hello from agent", List.of());

        var result = processor.process(ctx);

        assertThat(result).isEmpty();
        assertThat(ctx.state().getContext()).containsEntry("my-node", "Hello from agent");
    }

    @Test
    @DisplayName("extracts JSON output params into context")
    void shouldExtractOutputParams() {
        String jsonOutput = "{\"summary\": \"Brief summary\", \"score\": 85}";
        var ctx = contextWith("eval-node", jsonOutput, List.of("summary", "score"));

        processor.process(ctx);

        assertThat(ctx.state().getContext())
                .containsEntry("eval-node", jsonOutput)
                .containsEntry("summary", "Brief summary");
    }

    @Test
    @DisplayName("skips extraction when output is null")
    void shouldSkipNullOutput() {
        var ctx = contextWithNullOutput("my-node");

        var result = processor.process(ctx);

        assertThat(result).isEmpty();
        assertThat(ctx.state().getContext()).doesNotContainKey("my-node");
    }

    @Test
    @DisplayName("does not extract from malformed JSON")
    void shouldHandleMalformedJson() {
        var ctx = contextWith("node", "{\"key\": \"val", List.of("key"));

        processor.process(ctx);

        assertThat(ctx.state().getContext())
                .containsEntry("node", "{\"key\": \"val")
                .doesNotContainKey("key");
    }

    @Test
    @DisplayName("does not extract from empty string output")
    void shouldHandleEmptyStringOutput() {
        var ctx = contextWith("node", "", List.of("key"));

        processor.process(ctx);

        assertThat(ctx.state().getContext()).containsEntry("node", "").doesNotContainKey("key");
    }

    @Test
    @DisplayName("does not extract from JSON array output")
    void shouldHandleJsonArrayOutput() {
        var ctx = contextWith("node", "[1, 2, 3]", List.of("0"));

        processor.process(ctx);

        assertThat(ctx.state().getContext())
                .containsEntry("node", "[1, 2, 3]")
                .doesNotContainKey("0");
    }

    @Test
    @DisplayName("ignores missing keys without polluting context")
    void shouldIgnoreMissingKeys() {
        var ctx = contextWith("node", "{\"foo\": \"bar\"}", List.of("baz"));

        processor.process(ctx);

        assertThat(ctx.state().getContext())
                .containsEntry("node", "{\"foo\": \"bar\"}")
                .doesNotContainKey("baz");
    }

    @Test
    @DisplayName("extracts boolean values")
    void shouldExtractBooleanValues() {
        var ctx = contextWith("node", "{\"active\": true}", List.of("active"));

        processor.process(ctx);

        assertThat(ctx.state().getContext()).containsEntry("active", true);
    }

    @Test
    @DisplayName("extracts null values")
    void shouldExtractNullValues() {
        var ctx = contextWith("node", "{\"name\": null}", List.of("name"));

        processor.process(ctx);

        assertThat(ctx.state().getContext()).containsEntry("name", null);
    }

    @Test
    @DisplayName("extracts JSON embedded in surrounding text")
    void shouldExtractFromEmbeddedJson() {
        String output = "Here is the result: {\"score\": 95} done.";
        var ctx = contextWith("node", output, List.of("score"));

        processor.process(ctx);

        assertThat(ctx.state().getContext()).containsEntry("score", 95.0);
    }

    @Test
    @DisplayName("skips nested object values that regex cannot parse")
    void shouldSkipNestedObjectValues() {
        var ctx =
                contextWith(
                        "node",
                        "{\"meta\": {\"nested\": \"val\"}, \"flat\": \"ok\"}",
                        List.of("meta", "flat"));

        processor.process(ctx);

        assertThat(ctx.state().getContext()).containsEntry("flat", "ok").doesNotContainKey("meta");
    }

    // --- Helpers ---

    private ProcessorContext contextWith(String nodeId, String output, List<String> outputParams) {
        var node =
                StandardNode.builder()
                        .id(nodeId)
                        .transitionRules(List.of(new SuccessTransition("next")))
                        .outputParams(outputParams)
                        .build();

        var state =
                new HensuState.Builder()
                        .executionId("test")
                        .workflowId("test-wf")
                        .currentNode(nodeId)
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .build();

        var workflow =
                Workflow.builder()
                        .id("test-wf")
                        .startNode(nodeId)
                        .nodes(Map.of(nodeId, node))
                        .build();

        var execCtx = ExecutionContext.builder().state(state).workflow(workflow).build();

        return new ProcessorContext(execCtx, node, NodeResult.success(output, Map.of()));
    }

    private ProcessorContext contextWithNullOutput(String nodeId) {
        var node =
                StandardNode.builder()
                        .id(nodeId)
                        .transitionRules(List.of(new SuccessTransition("next")))
                        .build();

        var state =
                new HensuState.Builder()
                        .executionId("test")
                        .workflowId("test-wf")
                        .currentNode(nodeId)
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .build();

        var workflow =
                Workflow.builder()
                        .id("test-wf")
                        .startNode(nodeId)
                        .nodes(Map.of(nodeId, node))
                        .build();

        var execCtx = ExecutionContext.builder().state(state).workflow(workflow).build();

        return new ProcessorContext(
                execCtx,
                node,
                NodeResult.builder().status(ResultStatus.SUCCESS).output(null).build());
    }
}
