package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.HensuState;
import io.hensu.core.util.AgentOutputValidator;
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
    @DisplayName("single write: stores full text under declared variable name, not node ID")
    void shouldStoreFullTextUnderSingleWriteKey() {
        var ctx = contextWith("improve-node", "Improved article text.", List.of("article"));

        var result = processor.process(ctx);

        assertThat(result).isEmpty();
        assertThat(ctx.state().getContext())
                .containsEntry("article", "Improved article text.")
                .doesNotContainKey("improve-node");
    }

    @Test
    @DisplayName("multiple writes: routes each JSON key to its declared variable name")
    void shouldExtractJsonKeysToWriteVariables() {
        String json =
                "{\"article\": \"The text\", \"score\": 85, \"recommendation\": \"Add more\"}";
        var ctx = contextWith("draft-node", json, List.of("article", "score", "recommendation"));

        processor.process(ctx);

        assertThat(ctx.state().getContext())
                .containsEntry("article", "The text")
                .containsEntry("recommendation", "Add more")
                .doesNotContainKey("draft-node");
    }

    @Test
    @DisplayName("no writes: output stored under node ID for downstream template resolution")
    void shouldStoreOutputUnderNodeIdWhenNoWrites() {
        var ctx = contextWith("escalate-node", "Summary of issues.", List.of());

        var result = processor.process(ctx);

        assertThat(result).isEmpty();
        assertThat(ctx.state().getContext()).containsEntry("escalate-node", "Summary of issues.");
    }

    @Test
    @DisplayName("multiple writes: malformed JSON leaves state clean")
    void shouldLeaveStateCleanOnMalformedJson() {
        var ctx = contextWith("node", "{\"article\": \"val", List.of("article", "score"));

        processor.process(ctx);

        assertThat(ctx.state().getContext())
                .doesNotContainKey("article")
                .doesNotContainKey("score")
                .doesNotContainKey("node");
    }

    @Test
    @DisplayName("output with NUL byte returns Failure and does not pollute context")
    void shouldRejectOutputWithNulByte() {
        var ctx = contextWith("guard-node", "clean prefix\u0000injected", List.of("article"));

        var result = processor.process(ctx);

        assertThat(result).isPresent().get().isInstanceOf(ExecutionResult.Failure.class);
        assertThat(ctx.state().getContext()).doesNotContainKey("article");
    }

    @Test
    @DisplayName("output with RLO override (U+202E) returns Failure and does not pollute context")
    void shouldRejectOutputWithRloOverride() {
        var ctx = contextWith("guard-node", "visible\u202Ehidden", List.of("article"));

        var result = processor.process(ctx);

        assertThat(result).isPresent().get().isInstanceOf(ExecutionResult.Failure.class);
        assertThat(ctx.state().getContext()).doesNotContainKey("article");
    }

    @Test
    @DisplayName("output exceeding MAX_LLM_OUTPUT_BYTES returns Failure")
    void shouldRejectOversizedOutput() {
        String oversized = "x".repeat(AgentOutputValidator.MAX_LLM_OUTPUT_BYTES + 1);
        var ctx = contextWith("guard-node", oversized, List.of("article"));

        var result = processor.process(ctx);

        assertThat(result).isPresent().get().isInstanceOf(ExecutionResult.Failure.class);
    }

    @Test
    @DisplayName("Failure message contains node ID for ops debugging")
    void shouldIncludeNodeIdInFailureMessage() {
        var ctx = contextWith("my-node", "bad\u0007content", List.of());

        var result = processor.process(ctx);

        var failure = (ExecutionResult.Failure) result.orElseThrow();
        assertThat(failure.e().getMessage()).contains("my-node");
    }

    // — Helpers —————————————————————————————————————————————————————————————

    private ProcessorContext contextWith(String nodeId, String output, List<String> writes) {
        var node =
                StandardNode.builder()
                        .id(nodeId)
                        .transitionRules(List.of(new SuccessTransition("next")))
                        .writes(writes)
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
}
