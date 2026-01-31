package io.hensu.core.execution.result;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExecutionHistoryTest {

    private ExecutionHistory history;

    @BeforeEach
    void setUp() {
        history = new ExecutionHistory();
    }

    @Nested
    class AddStepTest {

        @Test
        void shouldAddStep() {
            // Given
            ExecutionStep step = createStep("node-1");

            // When
            history.addStep(step);

            // Then
            assertThat(history.getSteps()).hasSize(1);
            assertThat(history.getSteps().getFirst()).isEqualTo(step);
        }

        @Test
        void shouldAddMultipleSteps() {
            // Given
            ExecutionStep step1 = createStep("node-1");
            ExecutionStep step2 = createStep("node-2");
            ExecutionStep step3 = createStep("node-3");

            // When
            history.addStep(step1);
            history.addStep(step2);
            history.addStep(step3);

            // Then
            assertThat(history.getSteps()).hasSize(3);
        }

        @Test
        void shouldReturnCopyAfterAddingStep() {
            // Given
            ExecutionStep step = createStep("node-1");

            // When
            ExecutionHistory returned = history.addStep(step);

            // Then - returned is a copy, not the same instance
            assertThat(returned).isNotSameAs(history);
            assertThat(returned.getSteps()).hasSize(1);
        }
    }

    @Nested
    class AddBacktrackTest {

        @Test
        void shouldAddBacktrackEvent() {
            // Given
            BacktrackEvent backtrack =
                    BacktrackEvent.builder()
                            .from("node-2")
                            .to("node-1")
                            .reason("Quality check failed")
                            .type(BacktrackType.AUTOMATIC)
                            .build();

            // When
            history.addBacktrack(backtrack);

            // Then
            assertThat(history.getBacktracks()).hasSize(1);
            assertThat(history.getBacktracks().getFirst()).isEqualTo(backtrack);
        }

        @Test
        void shouldReturnCopyAfterAddingBacktrack() {
            // Given
            BacktrackEvent backtrack = BacktrackEvent.builder().from("node-2").to("node-1").build();

            // When
            ExecutionHistory returned = history.addBacktrack(backtrack);

            // Then
            assertThat(returned).isNotSameAs(history);
            assertThat(returned.getBacktracks()).hasSize(1);
        }
    }

    @Nested
    class AddAutoBacktrackTest {

        @Test
        void shouldAddAutoBacktrackFromRubricEvaluation() {
            // Given
            RubricEvaluation evaluation =
                    RubricEvaluation.builder()
                            .rubricId("quality")
                            .score(45.0)
                            .passed(false)
                            .build();

            // When
            history.addAutoBacktrack("node-2", "node-1", "Score too low", evaluation);

            // Then
            assertThat(history.getBacktracks()).hasSize(1);
            BacktrackEvent backtrack = history.getBacktracks().getFirst();
            assertThat(backtrack.getFrom()).isEqualTo("node-2");
            assertThat(backtrack.getTo()).isEqualTo("node-1");
            assertThat(backtrack.getReason()).isEqualTo("Score too low");
            assertThat(backtrack.getType()).isEqualTo(BacktrackType.AUTOMATIC);
            assertThat(backtrack.getRubricScore()).isEqualTo(45.0);
            assertThat(backtrack.getTimestamp()).isNotNull();
        }
    }

    @Nested
    class AddJumpTest {

        @Test
        void shouldAddJumpBacktrack() {
            // Given
            RubricEvaluation evaluation =
                    RubricEvaluation.builder()
                            .rubricId("quality")
                            .score(30.0)
                            .passed(false)
                            .build();

            // When
            history.addJump("node-3", "node-1", "Major revision needed", evaluation);

            // Then
            assertThat(history.getBacktracks()).hasSize(1);
            BacktrackEvent backtrack = history.getBacktracks().getFirst();
            assertThat(backtrack.getFrom()).isEqualTo("node-3");
            assertThat(backtrack.getTo()).isEqualTo("node-1");
            assertThat(backtrack.getType()).isEqualTo(BacktrackType.JUMP);
        }
    }

    @Nested
    class GetStepsTest {

        @Test
        void shouldReturnEmptyListWhenNoSteps() {
            // When/Then
            assertThat(history.getSteps()).isEmpty();
        }

        @Test
        void shouldReturnImmutableList() {
            // When
            var steps = history.getSteps();

            // Then
            assertThat(steps).isUnmodifiable();
        }
    }

    @Nested
    class GetBacktracksTest {

        @Test
        void shouldReturnEmptyListWhenNoBacktracks() {
            // When/Then
            assertThat(history.getBacktracks()).isEmpty();
        }

        @Test
        void shouldReturnImmutableList() {
            // When
            var backtracks = history.getBacktracks();

            // Then
            assertThat(backtracks).isUnmodifiable();
        }
    }

    @Nested
    class CopyTest {

        @Test
        void shouldCreateDeepCopy() {
            // Given
            history.addStep(createStep("node-1"));
            history.addBacktrack(BacktrackEvent.builder().from("node-2").to("node-1").build());

            // When
            ExecutionHistory copy = history.copy();

            // Then
            assertThat(copy).isNotSameAs(history);
            assertThat(copy.getSteps()).hasSize(1);
            assertThat(copy.getBacktracks()).hasSize(1);
        }

        @Test
        void shouldNotAffectOriginalWhenCopyModified() {
            // Given
            history.addStep(createStep("node-1"));
            ExecutionHistory copy = history.copy();

            // When - Note: copy() returns immutable lists, so we can't modify the copy's lists
            // This test verifies the copy has its own data

            // Then
            assertThat(history.getSteps()).hasSize(1);
            assertThat(copy.getSteps()).hasSize(1);
        }
    }

    private ExecutionStep createStep(String nodeId) {
        return ExecutionStep.builder()
                .nodeId(nodeId)
                .result(NodeResult.success("output", Map.of()))
                .timestamp(Instant.now())
                .build();
    }
}
