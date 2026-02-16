package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.rubric.RubricNotFoundException;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("RubricProcessor")
@ExtendWith(MockitoExtension.class)
class RubricPostProcessorTest {

    @Mock private RubricEngine rubricEngine;

    private RubricPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RubricPostProcessor(rubricEngine);
    }

    @Nested
    @DisplayName("skip conditions")
    class SkipConditions {

        @Test
        @DisplayName("returns empty when node has no rubricId")
        void shouldSkipWhenNoRubric() {
            var ctx = contextWithRubric("node", null);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("passing evaluation")
    class PassingEvaluation {

        @Test
        @DisplayName("returns empty when rubric passes")
        void shouldContinueOnPass() throws RubricNotFoundException {
            var ctx = contextWithRubric("node", "quality");
            mockRubricEvaluation("quality", 90.0, true);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("stores evaluation in state")
        void shouldStoreEvaluation() throws RubricNotFoundException {
            var ctx = contextWithRubric("node", "quality");
            mockRubricEvaluation("quality", 90.0, true);

            processor.process(ctx);

            assertThat(ctx.state().getRubricEvaluation()).isNotNull();
            assertThat(ctx.state().getRubricEvaluation().getScore()).isEqualTo(90.0);
        }
    }

    @Nested
    @DisplayName("auto-backtrack on failure")
    class AutoBacktrack {

        @Test
        @DisplayName("returns empty and mutates state on critical failure (score < 30)")
        void shouldBacktrackOnCriticalFailure() throws RubricNotFoundException {
            var ctx = contextWithRubric("review-node", "quality");
            mockRubricEvaluation("quality", 15.0, false);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.state().getHistory().getBacktracks()).isNotEmpty();
        }

        @Test
        @DisplayName("returns empty and mutates state on moderate failure (score < 60)")
        void shouldBacktrackOnModerateFailure() throws RubricNotFoundException {
            var ctx = contextWithRubricAndHistory("current", "quality");
            mockRubricEvaluation("quality", 45.0, false);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.state().getHistory().getBacktracks()).isNotEmpty();
        }

        @Test
        @DisplayName("retries current node on minor failure (score < 80)")
        void shouldRetryOnMinorFailure() throws RubricNotFoundException {
            var ctx = contextWithRubric("node", "quality");
            mockRubricEvaluation("quality", 70.0, false);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.state().getCurrentNode()).isEqualTo("node");
        }

        @Test
        @DisplayName("increments retry counter on minor failure")
        void shouldIncrementRetryCounter() throws RubricNotFoundException {
            var ctx = contextWithRubric("node", "quality");
            mockRubricEvaluation("quality", 70.0, false);

            processor.process(ctx);

            assertThat(ctx.state().getContext().get("retry_attempt")).isEqualTo(1);
        }

        @Test
        @DisplayName("stops retrying after max attempts")
        void shouldStopAfterMaxRetries() throws RubricNotFoundException {
            var ctx = contextWithRubric("node", "quality");
            ctx.state().getContext().put("retry_attempt", 3);
            mockRubricEvaluation("quality", 70.0, false);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("updates state currentNode on backtrack")
        void shouldUpdateStateOnBacktrack() throws RubricNotFoundException {
            var ctx = contextWithRubric("node", "quality");
            mockRubricEvaluation("quality", 70.0, false);

            processor.process(ctx);

            assertThat(ctx.state().getCurrentNode()).isEqualTo("node");
        }

        @Test
        @DisplayName("records auto-backtrack in history")
        void shouldRecordAutoBacktrack() throws RubricNotFoundException {
            var ctx = contextWithRubric("node", "quality");
            mockRubricEvaluation("quality", 70.0, false);

            processor.process(ctx);

            assertThat(ctx.state().getHistory().getBacktracks()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("error handling")
    class ErrorHandling {

        @Test
        @DisplayName("returns Failure on RubricNotFoundException")
        void shouldTerminateOnMissingRubric() throws RubricNotFoundException {
            var ctx = contextWithRubric("node", "missing-rubric");
            when(rubricEngine.exists("missing-rubric")).thenReturn(true);
            when(rubricEngine.evaluate(eq("missing-rubric"), any(), any()))
                    .thenThrow(new RubricNotFoundException("Rubric not found: missing-rubric"));

            var result = processor.process(ctx);

            assertThat(result).isPresent();
            assertThat(result.get()).isInstanceOf(ExecutionResult.Failure.class);
        }
    }

    // --- Helpers ---

    private void mockRubricEvaluation(String rubricId, double score, boolean passed)
            throws RubricNotFoundException {
        when(rubricEngine.exists(rubricId)).thenReturn(true);
        when(rubricEngine.evaluate(eq(rubricId), any(), any()))
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId(rubricId)
                                .score(score)
                                .passed(passed)
                                .build());
    }

    private ProcessorContext contextWithRubric(String nodeId, String rubricId) {
        Node node =
                StandardNode.builder()
                        .id(nodeId)
                        .rubricId(rubricId)
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
                        .rubrics(rubricId != null ? Map.of(rubricId, "/tmp/rubric.yaml") : Map.of())
                        .build();

        var execCtx =
                ExecutionContext.builder()
                        .state(state)
                        .workflow(workflow)
                        .rubricEngine(rubricEngine)
                        .build();

        return new ProcessorContext(execCtx, node, NodeResult.success("output", Map.of()));
    }

    private ProcessorContext contextWithRubricAndHistory(String nodeId, String rubricId) {
        // Build a context with an earlier rubric-bearing node in history
        // so findPreviousPhase can find a backtrack target
        Node previousNode =
                StandardNode.builder()
                        .id("previous")
                        .rubricId("other-rubric")
                        .transitionRules(List.of(new SuccessTransition(nodeId)))
                        .build();

        Node currentNode =
                StandardNode.builder()
                        .id(nodeId)
                        .rubricId(rubricId)
                        .transitionRules(List.of(new SuccessTransition("next")))
                        .build();

        var history = new ExecutionHistory();
        history.addStep(
                new io.hensu.core.execution.result.ExecutionStep(
                        "previous",
                        null,
                        NodeResult.success("prev output", Map.of()),
                        Instant.now()));

        var state =
                new HensuState.Builder()
                        .executionId("test")
                        .workflowId("test-wf")
                        .currentNode(nodeId)
                        .context(new HashMap<>())
                        .history(history)
                        .build();

        var workflow =
                Workflow.builder()
                        .id("test-wf")
                        .startNode("previous")
                        .nodes(Map.of("previous", previousNode, nodeId, currentNode))
                        .rubrics(
                                Map.of(
                                        rubricId,
                                        "/tmp/rubric.yaml",
                                        "other-rubric",
                                        "/tmp/other.yaml"))
                        .build();

        var execCtx =
                ExecutionContext.builder()
                        .state(state)
                        .workflow(workflow)
                        .rubricEngine(rubricEngine)
                        .build();

        return new ProcessorContext(execCtx, currentNode, NodeResult.success("output", Map.of()));
    }
}
