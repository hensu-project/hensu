package io.hensu.core.workflow.transition;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.rubric.model.ComparisonOperator;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.state.HensuState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        void shouldReturnNullOnFailure() {
            // Given
            SuccessTransition transition = new SuccessTransition("next-node");
            NodeResult result = NodeResult.failure("Error");

            // When
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isNull();
        }
    }

    @Nested
    class NoConsensusTransitionTest {

        @Test
        void shouldFireOnConsensusFailure() {
            // FAILURE + consensus_reached=false → routes to target
            NoConsensusTransition transition = new NoConsensusTransition("revise-node");
            NodeResult result =
                    new NodeResult(
                            ResultStatus.FAILURE, null, Map.of("consensus_reached", Boolean.FALSE));

            assertThat(transition.evaluate(state, result)).isEqualTo("revise-node");
        }

        @Test
        void shouldNotFireOnNonConsensusFailure() {
            // FAILURE without consensus_reached=false → fall-through (FailureTransition's domain)
            NoConsensusTransition transition = new NoConsensusTransition("revise-node");
            NodeResult result = NodeResult.failure("Agent error");

            assertThat(transition.evaluate(state, result)).isNull();
        }

        @Test
        void shouldNotFireOnSuccess() {
            // SUCCESS with consensus_reached=false in metadata → must not fire
            NoConsensusTransition transition = new NoConsensusTransition("revise-node");
            NodeResult result =
                    NodeResult.success("Output", Map.of("consensus_reached", Boolean.FALSE));

            assertThat(transition.evaluate(state, result)).isNull();
        }

        @Test
        void shouldNotThrowOnNullMetadata() {
            // NodeResult constructed via 3-arg ctor with null metadata — must not NPE
            NoConsensusTransition transition = new NoConsensusTransition("revise-node");
            NodeResult result = new NodeResult(ResultStatus.FAILURE, null, null);

            assertThat(transition.evaluate(state, result)).isNull();
        }
    }

    @Nested
    class FailureTransitionTest {

        @Test
        void shouldNotFireOnConsensusFailure() {
            // consensus_reached=false with FAILURE status — FailureTransition must yield to
            // NoConsensusTransition, not swallow the event
            FailureTransition transition = new FailureTransition("fallback");
            NodeResult result =
                    new NodeResult(
                            ResultStatus.FAILURE, null, Map.of("consensus_reached", Boolean.FALSE));

            String target = transition.evaluate(state, result);

            assertThat(target).isNull();
        }
    }

    @Nested
    class BoundedTransitionTest {

        @Test
        void shouldRetryAtBudgetBoundaryThenEscalate() {
            // budget=2: at count=1 (one below budget) → inner target; at count=2 → escalation
            BoundedTransition bounded =
                    new BoundedTransition(new FailureTransition(null), "failure", 2, "fallback");
            NodeResult result = NodeResult.failure("Error");

            // One attempt used — still under budget
            state.incrementRetryCount("failure", "current-node");
            assertThat(bounded.evaluate(state, result)).isEqualTo("current-node");

            // Second attempt — budget exhausted → escalate
            state.incrementRetryCount("failure", "current-node");
            assertThat(bounded.evaluate(state, result)).isEqualTo("fallback");
        }

        @Test
        void selfLoopShouldRespectBudget() {
            // FailureTransition(null) self-loops to current node. If counter wiring is wrong,
            // this spins forever. Verify escalation still fires after budget attempts.
            BoundedTransition bounded =
                    new BoundedTransition(new FailureTransition(null), "failure", 1, "fallback");
            NodeResult result = NodeResult.failure("Error");

            // First evaluation — under budget → self-loop target
            assertThat(bounded.evaluate(state, result)).isEqualTo("current-node");

            // Simulate TransitionPostProcessor incrementing counter after the match
            state.incrementRetryCount("failure", "current-node");

            // Second evaluation — budget exhausted → must escalate, not self-loop
            assertThat(bounded.evaluate(state, result)).isEqualTo("fallback");
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

            state.getContext().put(EngineVariables.SCORE, 85.0);
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

            state.getContext().put(EngineVariables.SCORE, 65.0);
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
            state.getContext().put(EngineVariables.SCORE, 90.0);
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
            state.getContext().put(EngineVariables.SCORE, "85.5");
            NodeResult result = NodeResult.success("Output", Map.of());

            // When
            String target = transition.evaluate(state, result);

            // Then
            assertThat(target).isEqualTo("approved");
        }

        @Test
        void shouldReturnNullWhenNoConditionMatches() {
            // score 85 with a GT 90 condition — no match → null
            List<ScoreCondition> conditions =
                    List.of(new ScoreCondition(ComparisonOperator.GT, 90.0, null, "excellent"));
            ScoreTransition transition = new ScoreTransition(conditions);

            state.getContext().put(EngineVariables.SCORE, 85.0);
            NodeResult result = NodeResult.success("Output", Map.of());

            String target = transition.evaluate(state, result);

            assertThat(target).isNull();
        }
    }

    @Nested
    class ApprovalTransitionTest {

        /// Truth table: all four (expected × contextValue) permutations in one test.
        @ParameterizedTest(name = "expected={0}, approved={1} → {2}")
        @CsvSource(
                nullValues = "null",
                value = {
                    "true,  true,  finalize", // approved=true,  expecting=true  → routes
                    "true,  false, null", //     approved=false, expecting=true  → fall-through
                    "false, false, improve", //  approved=false, expecting=false → routes
                    "false, true,  null" //      approved=true,  expecting=false → fall-through
                })
        void shouldRouteByBooleanApprovalTruthTable(
                boolean expected, boolean contextValue, String expectedTarget) {
            state.getContext().put(EngineVariables.APPROVED, contextValue);
            String targetNode = expected ? "finalize" : "improve";
            ApprovalTransition transition = new ApprovalTransition(expected, targetNode);

            assertThat(transition.evaluate(state, NodeResult.success("Output", Map.of())))
                    .isEqualTo(expectedTarget);
        }

        @Test
        void shouldReturnNullWhenApprovedKeyAbsent() {
            // Node did not write "approved" to context — transition must fall through
            assertThat(
                            new ApprovalTransition(true, "finalize")
                                    .evaluate(state, NodeResult.success("Output", Map.of())))
                    .isNull();
        }

        @Test
        void shouldReturnNullForNonBooleanValue() {
            // Agent output an integer — strict parsing must fall through, never guess intent
            state.getContext().put(EngineVariables.APPROVED, 1);
            assertThat(
                            new ApprovalTransition(true, "finalize")
                                    .evaluate(state, NodeResult.success("Output", Map.of())))
                    .isNull();
        }

        @Test
        void shouldReturnNullForAmbiguousStringValue() {
            // Agent output free-form text — strict parsing must reject it
            state.getContext().put(EngineVariables.APPROVED, "looks good to me");
            assertThat(
                            new ApprovalTransition(true, "finalize")
                                    .evaluate(state, NodeResult.success("Output", Map.of())))
                    .isNull();
        }

        /// Some LLMs output "true"/"false" as JSON strings despite format instructions.
        @ParameterizedTest(name = "\"{0}\" (expected={1}) → {2}")
        @CsvSource({
            "true,  true,  finalize",
            "True,  true,  finalize",
            "TRUE,  true,  finalize",
            "false, false, improve",
            "False, false, improve",
            "FALSE, false, improve"
        })
        void shouldAcceptBooleanStringsCaseInsensitively(
                String contextValue, boolean expected, String expectedTarget) {
            state.getContext().put(EngineVariables.APPROVED, contextValue);
            assertThat(
                            new ApprovalTransition(expected, expectedTarget)
                                    .evaluate(state, NodeResult.success("Output", Map.of())))
                    .isEqualTo(expectedTarget);
        }
    }
}
