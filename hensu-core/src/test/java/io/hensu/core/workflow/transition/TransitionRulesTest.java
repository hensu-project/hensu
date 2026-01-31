package io.hensu.core.workflow.transition;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.rubric.model.ComparisonOperator;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.state.HensuState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TransitionRulesTest {

    private HensuState state;

    @BeforeEach
    void setUp() {
        state =
                new HensuState(
                        new HashMap<>(), "test-workflow", "current-node", new ExecutionHistory());
    }

    @Nested
    class SuccessTransitionTest {

        @Test
        void shouldReturnTargetNodeOnSuccess() {
            // Given
            SuccessTransition transition = new SuccessTransition("next-node");
            NodeResult result = NodeResult.success("Output", Map.of());

            // When
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isEqualTo("next-node");
        }

        @Test
        void shouldReturnNullOnFailure() {
            // Given
            SuccessTransition transition = new SuccessTransition("next-node");
            NodeResult result = NodeResult.failure("Error");

            // When
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isNull();
        }

        @Test
        void shouldExposeTargetNode() {
            // Given
            SuccessTransition transition = new SuccessTransition("target");

            // Then
            assertThat(transition.targetNode()).isEqualTo("target");
            assertThat(transition.getTargetNode()).isEqualTo("target");
        }
    }

    @Nested
    class FailureTransitionTest {

        @Test
        void shouldRetryOnFirstFailure() {
            // Given
            FailureTransition transition = new FailureTransition(3, "fallback");
            NodeResult result = NodeResult.failure("Error");

            // When
            String target = transition.evaluate(state, result);

            // Then - should retry current node
            assertThat(target).isEqualTo("current-node");
            assertThat(state.getRetryCount()).isEqualTo(1);
        }

        @Test
        void shouldRetryUntilMaxRetriesReached() {
            // Given
            FailureTransition transition = new FailureTransition(2, "fallback");
            NodeResult result = NodeResult.failure("Error");

            // When - first retry
            transition.evaluate(state, result);
            assertThat(state.getRetryCount()).isEqualTo(1);

            // When - second retry
            transition.evaluate(state, result);
            assertThat(state.getRetryCount()).isEqualTo(2);

            // When - max retries reached, go to fallback
            String target = transition.evaluate(state, result);
            assertThat(target).isEqualTo("fallback");
        }

        @Test
        void shouldReturnNullOnSuccess() {
            // Given
            FailureTransition transition = new FailureTransition(3, "fallback");
            NodeResult result = NodeResult.success("Output", Map.of());

            // When
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isNull();
        }

        @Test
        void shouldGoDirectlyToFallbackWhenRetryCountIsZero() {
            // Given
            FailureTransition transition = new FailureTransition(0, "fallback");
            NodeResult result = NodeResult.failure("Error");

            // When
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isEqualTo("fallback");
        }

        @Test
        void shouldExposeRetryCountAndTargetNode() {
            // Given
            FailureTransition transition = new FailureTransition(5, "error-handler");

            // Then
            assertThat(transition.getRetryCount()).isEqualTo(5);
            assertThat(transition.getThenTargetNode()).isEqualTo("error-handler");
        }
    }

    @Nested
    class ScoreTransitionTest {

        @Test
        void shouldMatchHighScoreCondition() {
            // Given
            List<ScoreCondition> conditions =
                    List.of(
                            new ScoreCondition(ComparisonOperator.GTE, 80.0, null, "approved"),
                            new ScoreCondition(ComparisonOperator.LT, 80.0, null, "needs-review"));
            ScoreTransition transition = new ScoreTransition(conditions);

            // Set up rubric evaluation with high score
            RubricEvaluation evaluation =
                    RubricEvaluation.builder().rubricId("test").score(85.0).passed(true).build();
            state.setRubricEvaluation(evaluation);

            NodeResult result = NodeResult.success("Output", Map.of());

            // When
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isEqualTo("approved");
        }

        @Test
        void shouldMatchLowScoreCondition() {
            // Given
            List<ScoreCondition> conditions =
                    List.of(
                            new ScoreCondition(ComparisonOperator.GTE, 80.0, null, "approved"),
                            new ScoreCondition(ComparisonOperator.LT, 80.0, null, "needs-review"));
            ScoreTransition transition = new ScoreTransition(conditions);

            // Set up rubric evaluation with low score
            RubricEvaluation evaluation =
                    RubricEvaluation.builder().rubricId("test").score(65.0).passed(false).build();
            state.setRubricEvaluation(evaluation);

            NodeResult result = NodeResult.success("Output", Map.of());

            // When
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isEqualTo("needs-review");
        }

        @Test
        void shouldReturnNullWhenNoScoreAvailable() {
            // Given
            List<ScoreCondition> conditions =
                    List.of(new ScoreCondition(ComparisonOperator.GTE, 80.0, null, "approved"));
            ScoreTransition transition = new ScoreTransition(conditions);
            NodeResult result = NodeResult.success("Output", Map.of());

            // When - no rubric evaluation set
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isNull();
        }

        @Test
        void shouldReadScoreFromContextWhenNoRubricEvaluation() {
            // Given
            List<ScoreCondition> conditions =
                    List.of(
                            new ScoreCondition(ComparisonOperator.GTE, 80.0, null, "approved"),
                            new ScoreCondition(ComparisonOperator.LT, 80.0, null, "needs-review"));
            ScoreTransition transition = new ScoreTransition(conditions);

            // Set score in context (self-reported)
            state.getContext().put("score", 90.0);
            NodeResult result = NodeResult.success("Output", Map.of());

            // When
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isEqualTo("approved");
        }

        @Test
        void shouldParseScoreFromString() {
            // Given
            List<ScoreCondition> conditions =
                    List.of(new ScoreCondition(ComparisonOperator.GTE, 80.0, null, "approved"));
            ScoreTransition transition = new ScoreTransition(conditions);

            // Set score as string
            state.getContext().put("score", "85.5");
            NodeResult result = NodeResult.success("Output", Map.of());

            // When
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isEqualTo("approved");
        }

        @Test
        void shouldTryAlternativeScoreKeys() {
            // Given
            List<ScoreCondition> conditions =
                    List.of(new ScoreCondition(ComparisonOperator.GTE, 80.0, null, "approved"));
            ScoreTransition transition = new ScoreTransition(conditions);

            // Set score using alternative key
            state.getContext().put("quality_score", 90);
            NodeResult result = NodeResult.success("Output", Map.of());

            // When
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isEqualTo("approved");
        }

        @Test
        void shouldExposeConditions() {
            // Given
            List<ScoreCondition> conditions =
                    List.of(new ScoreCondition(ComparisonOperator.GT, 90.0, null, "excellent"));
            ScoreTransition transition = new ScoreTransition(conditions);

            // Then
            assertThat(transition.getConditions()).hasSize(1);
            assertThat(transition.conditions()).hasSize(1);
        }

        @Test
        void shouldReturnNullWhenNoConditionMatches() {
            // Given
            List<ScoreCondition> conditions =
                    List.of(new ScoreCondition(ComparisonOperator.GT, 90.0, null, "excellent"));
            ScoreTransition transition = new ScoreTransition(conditions);

            RubricEvaluation evaluation =
                    RubricEvaluation.builder().rubricId("test").score(85.0).passed(true).build();
            state.setRubricEvaluation(evaluation);
            NodeResult result = NodeResult.success("Output", Map.of());

            // When - score is 85, condition requires > 90
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isNull();
        }
    }

    @Nested
    class AlwaysTransitionTest {

        @Test
        void shouldAlwaysReturnEmptyString() {
            // Given
            AlwaysTransition transition = new AlwaysTransition();
            NodeResult successResult = NodeResult.success("Output", Map.of());
            NodeResult failureResult = NodeResult.failure("Error");

            // When/Then - always returns empty string
            assertThat(transition.evaluate(state, successResult)).isEmpty();
            assertThat(transition.evaluate(state, failureResult)).isEmpty();
        }
    }
}
