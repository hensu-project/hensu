package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.rubric.RubricNotFoundException;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.rubric.evaluator.ScoreExtractingEvaluator;
import io.hensu.core.rubric.model.ComparisonOperator;
import io.hensu.core.rubric.model.DoubleRange;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.ScoreTransition;
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
            var ctx = contextWithRubric(null);

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
            var ctx = contextWithRubric("quality");
            mockRubricEvaluation(90.0, true);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("stores evaluation in state")
        void shouldStoreEvaluation() throws RubricNotFoundException {
            var ctx = contextWithRubric("quality");
            mockRubricEvaluation(90.0, true);

            processor.process(ctx);

            assertThat(ctx.state().getRubricEvaluation()).isNotNull();
            assertThat(ctx.state().getRubricEvaluation().getScore()).isEqualTo(90.0);
        }
    }

    @Nested
    @DisplayName("auto-backtrack on failure")
    class AutoBacktrack {

        @Test
        @DisplayName(
                "backtracks to earliest rubric node in history on critical failure (score < 30)")
        void shouldBacktrackOnCriticalFailure() throws RubricNotFoundException {
            var ctx = contextWithRubricAndHistory();
            mockRubricEvaluation(15.0, false);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.state().getCurrentNode()).isEqualTo("previous");
            assertThat(ctx.state().getHistory().getBacktracks()).hasSize(1);
        }

        @Test
        @DisplayName(
                "backtracks to workflow start node on critical failure when history has no rubric steps")
        void shouldBacktrackToStartNodeOnCriticalFailureWithEmptyHistory()
                throws RubricNotFoundException {
            // Node "review" is current; workflow start is "start" — history is empty.
            // findEarliestLogicalStep must fall back to workflow.getStartNode().
            Node startNode =
                    StandardNode.builder()
                            .id("start")
                            .transitionRules(List.of(new SuccessTransition("review")))
                            .build();

            Node reviewNode =
                    StandardNode.builder()
                            .id("review")
                            .rubricId("quality")
                            .transitionRules(List.of(new SuccessTransition("next")))
                            .build();

            var state =
                    new HensuState.Builder()
                            .executionId("test")
                            .workflowId("test-wf")
                            .currentNode("review")
                            .context(new HashMap<>())
                            .history(new ExecutionHistory())
                            .build();

            var workflow =
                    Workflow.builder()
                            .id("test-wf")
                            .startNode("start")
                            .nodes(Map.of("start", startNode, "review", reviewNode))
                            .rubrics(Map.of("quality", "/tmp/rubric.yaml"))
                            .build();

            var execCtx =
                    ExecutionContext.builder()
                            .state(state)
                            .workflow(workflow)
                            .rubricEngine(rubricEngine)
                            .build();

            var ctx =
                    new ProcessorContext(execCtx, reviewNode, NodeResult.success("out", Map.of()));
            mockRubricEvaluation(15.0, false);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.state().getCurrentNode()).isEqualTo("start");
            assertThat(ctx.state().getHistory().getBacktracks()).hasSize(1);
        }

        @Test
        @DisplayName("backtracks to previous rubric-bearing node on moderate failure (score < 60)")
        void shouldBacktrackOnModerateFailure() throws RubricNotFoundException {
            var ctx = contextWithRubricAndHistory();
            mockRubricEvaluation(45.0, false);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.state().getCurrentNode()).isEqualTo("previous");
            assertThat(ctx.state().getHistory().getBacktracks()).hasSize(1);
        }

        @Test
        @DisplayName("falls through to retry when no prior rubric phase found on moderate failure")
        void shouldRetryWhenNoPhaseFoundOnModerateFailure() throws RubricNotFoundException {
            // Score 45.0 satisfies < 60 (moderate) AND < 80 (minor).
            // When findPreviousPhase returns null the moderate block does not return —
            // execution falls through to the minor retry block.
            var ctx = contextWithHistoryAndNoPriorRubricNode();
            mockRubricEvaluation(45.0, false);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.state().getCurrentNode()).isEqualTo("current");
            assertThat(ctx.state().getContext().get("retry_attempt")).isEqualTo(1);
            assertThat(ctx.state().getHistory().getBacktracks()).hasSize(1);
        }

        @Test
        @DisplayName("retries current node on minor failure (score < 80)")
        void shouldRetryOnMinorFailure() throws RubricNotFoundException {
            var ctx = contextWithRubric("quality");
            mockRubricEvaluation(70.0, false);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.state().getCurrentNode()).isEqualTo("node");
            assertThat(ctx.state().getHistory().getBacktracks()).hasSize(1);
        }

        @Test
        @DisplayName("increments retry counter on minor failure")
        void shouldIncrementRetryCounter() throws RubricNotFoundException {
            var ctx = contextWithRubric("quality");
            mockRubricEvaluation(70.0, false);

            processor.process(ctx);

            assertThat(ctx.state().getContext().get("retry_attempt")).isEqualTo(1);
        }

        @Test
        @DisplayName("does not backtrack and records no history entry after max retry attempts")
        void shouldStopAfterMaxRetries() throws RubricNotFoundException {
            var ctx = contextWithRubric("quality");
            ctx.state().getContext().put("retry_attempt", 3);
            mockRubricEvaluation(70.0, false);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.state().getHistory().getBacktracks()).isEmpty();
        }

        @Test
        @DisplayName("does not auto-backtrack when a matching ScoreTransition handles the failure")
        void shouldNotAutoBacktrackWhenScoreTransitionExists() throws RubricNotFoundException {
            // A ScoreTransition that matches score 45.0 — processor must defer to it.
            var condition =
                    new ScoreCondition(
                            ComparisonOperator.RANGE, null, new DoubleRange(40.0, 50.0), "handled");
            var node =
                    StandardNode.builder()
                            .id("current")
                            .rubricId("quality")
                            .transitionRules(List.of(new ScoreTransition(List.of(condition))))
                            .build();

            var ctx = contextWithNode(node);
            ctx.state().getContext().put("score", 45.0);
            mockRubricEvaluation(45.0, false);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.state().getHistory().getBacktracks()).isEmpty();
        }

        @Test
        @DisplayName(
                "injects backtrack_reason and failed_criteria into context on critical failure")
        void shouldInjectContextKeysOnCriticalFailure() throws RubricNotFoundException {
            var ctx = contextWithRubric("quality");
            mockRubricEvaluation(15.0, false);

            processor.process(ctx);

            assertThat(ctx.state().getContext()).containsKey("backtrack_reason");
            assertThat(ctx.state().getContext().get("backtrack_reason").toString())
                    .contains("Critical");
            assertThat(ctx.state().getContext()).containsKey("failed_criteria");
        }

        @Test
        @DisplayName(
                "injects backtrack_reason and improvement_suggestions into context on moderate failure")
        void shouldInjectContextKeysOnModerateFailure() throws RubricNotFoundException {
            var ctx = contextWithRubricAndHistory();
            mockRubricEvaluation(45.0, false);

            processor.process(ctx);

            assertThat(ctx.state().getContext()).containsKey("backtrack_reason");
            assertThat(ctx.state().getContext().get("backtrack_reason").toString())
                    .contains("Moderate");
            assertThat(ctx.state().getContext()).containsKey("improvement_suggestions");
        }
    }

    @Nested
    @DisplayName("error handling")
    class ErrorHandling {

        @Test
        @DisplayName("returns Failure on RubricNotFoundException")
        void shouldTerminateOnMissingRubric() throws RubricNotFoundException {
            var ctx = contextWithRubric("missing-rubric");
            when(rubricEngine.evaluate(eq("missing-rubric"), any(), any()))
                    .thenThrow(new RubricNotFoundException("Rubric not found: missing-rubric"));

            var result = processor.process(ctx);

            assertThat(result).isPresent();
            assertThat(result.get()).isInstanceOf(ExecutionResult.Failure.class);
        }

        @Test
        @DisplayName("handles plain String in _rubric_criterion_feedback without throwing")
        void shouldHandleStringRecommendationsGracefully() throws RubricNotFoundException {
            // Regression: OutputExtractionPostProcessor may store a plain String under this key
            // when an agent produces a single-value JSON. The safe instanceof List<?> cast must
            // silently skip it — backtrack logic must still execute normally.
            var ctx = contextWithRubric("quality");
            ctx.state()
                    .getContext()
                    .put(ScoreExtractingEvaluator.RECOMMENDATIONS_KEY, "just a plain string");
            mockRubricEvaluation(15.0, false);

            assertThatCode(() -> processor.process(ctx)).doesNotThrowAnyException();
            assertThat(ctx.state().getHistory().getBacktracks()).isNotEmpty();
        }
    }

    // — Helpers —————————————————————————————————————————————————————————————

    private void mockRubricEvaluation(double score, boolean passed) throws RubricNotFoundException {
        when(rubricEngine.evaluate(eq("quality"), any(), any()))
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(score)
                                .passed(passed)
                                .build());
    }

    private ProcessorContext contextWithRubric(String rubricId) {
        Node node =
                StandardNode.builder()
                        .id("node")
                        .rubricId(rubricId)
                        .transitionRules(List.of(new SuccessTransition("next")))
                        .build();

        var state =
                new HensuState.Builder()
                        .executionId("test")
                        .workflowId("test-wf")
                        .currentNode("node")
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .build();

        var workflow =
                Workflow.builder()
                        .id("test-wf")
                        .startNode("node")
                        .nodes(Map.of("node", node))
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

    /// Builds a context with a fully custom node — used when transition rules matter.
    private ProcessorContext contextWithNode(Node node) {
        var state =
                new HensuState.Builder()
                        .executionId("test")
                        .workflowId("test-wf")
                        .currentNode(node.getId())
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .build();

        var workflow =
                Workflow.builder()
                        .id("test-wf")
                        .startNode(node.getId())
                        .nodes(Map.of(node.getId(), node))
                        .rubrics(Map.of("quality", "/tmp/rubric.yaml"))
                        .build();

        var execCtx =
                ExecutionContext.builder()
                        .state(state)
                        .workflow(workflow)
                        .rubricEngine(rubricEngine)
                        .build();

        return new ProcessorContext(execCtx, node, NodeResult.success("output", Map.of()));
    }

    /// Builds a context where "previous" node (with a different rubric) precedes the current node.
    /// Enables testing of `findEarliestLogicalStep` and `findPreviousPhase`.
    private ProcessorContext contextWithRubricAndHistory() {
        Node previousNode =
                StandardNode.builder()
                        .id("previous")
                        .rubricId("other-rubric")
                        .transitionRules(List.of(new SuccessTransition("current")))
                        .build();

        Node currentNode =
                StandardNode.builder()
                        .id("current")
                        .rubricId("quality")
                        .transitionRules(List.of(new SuccessTransition("next")))
                        .build();

        var history = new ExecutionHistory();
        history.addStep(
                new ExecutionStep(
                        "previous",
                        null,
                        NodeResult.success("prev output", Map.of()),
                        Instant.now()));

        var state =
                new HensuState.Builder()
                        .executionId("test")
                        .workflowId("test-wf")
                        .currentNode("current")
                        .context(new HashMap<>())
                        .history(history)
                        .build();

        var workflow =
                Workflow.builder()
                        .id("test-wf")
                        .startNode("previous")
                        .nodes(Map.of("previous", previousNode, "current", currentNode))
                        .rubrics(
                                Map.of(
                                        "quality",
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

    /// Builds a context where the previous history node has NO rubric. Forces `findPreviousPhase`
    /// to return null on moderate failure.
    private ProcessorContext contextWithHistoryAndNoPriorRubricNode() {
        Node previousNode =
                StandardNode.builder()
                        .id("previous")
                        .transitionRules(List.of(new SuccessTransition("current")))
                        .build();

        Node currentNode =
                StandardNode.builder()
                        .id("current")
                        .rubricId("quality")
                        .transitionRules(List.of(new SuccessTransition("next")))
                        .build();

        var history = new ExecutionHistory();
        history.addStep(
                new ExecutionStep(
                        "previous",
                        null,
                        NodeResult.success("prev output", Map.of()),
                        Instant.now()));

        var state =
                new HensuState.Builder()
                        .executionId("test")
                        .workflowId("test-wf")
                        .currentNode("current")
                        .context(new HashMap<>())
                        .history(history)
                        .build();

        var workflow =
                Workflow.builder()
                        .id("test-wf")
                        .startNode("previous")
                        .nodes(Map.of("previous", previousNode, "current", currentNode))
                        .rubrics(Map.of("quality", "/tmp/rubric.yaml"))
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
