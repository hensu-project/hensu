package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.plan.PlanSnapshot.PlannedStepSnapshot;
import io.hensu.core.plan.PlanSnapshot.StepResultSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlanSnapshotTest {

    @Nested
    class ConstructorValidation {

        @Test
        void shouldThrowWhenNodeIdIsNull() {
            assertThatThrownBy(() -> new PlanSnapshot(null, List.of(), 0, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nodeId must not be null");
        }

        @Test
        void shouldThrowWhenCurrentStepIndexIsNegative() {
            assertThatThrownBy(() -> new PlanSnapshot("node-1", List.of(), -1, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currentStepIndex must be >= 0");
        }

        @Test
        void shouldDefaultStepsToEmptyList() {
            PlanSnapshot snapshot = new PlanSnapshot("node-1", null, 0, List.of());

            assertThat(snapshot.steps()).isNotNull().isEmpty();
        }

        @Test
        void shouldDefaultCompletedResultsToEmptyList() {
            PlanSnapshot snapshot = new PlanSnapshot("node-1", List.of(), 0, null);

            assertThat(snapshot.completedResults()).isNotNull().isEmpty();
        }

        @Test
        void shouldMakeDefensiveCopyOfSteps() {
            List<PlannedStepSnapshot> originalSteps =
                    new java.util.ArrayList<>(
                            List.of(
                                    new PlannedStepSnapshot(
                                            0, "tool", Map.of(), "desc", "PENDING")));

            PlanSnapshot snapshot = new PlanSnapshot("node-1", originalSteps, 0, List.of());

            originalSteps.clear();

            assertThat(snapshot.steps()).hasSize(1);
        }

        @Test
        void shouldMakeDefensiveCopyOfCompletedResults() {
            List<StepResultSnapshot> originalResults =
                    new java.util.ArrayList<>(
                            List.of(new StepResultSnapshot(0, "tool", true, "output", null, 100)));

            PlanSnapshot snapshot = new PlanSnapshot("node-1", List.of(), 0, originalResults);

            originalResults.clear();

            assertThat(snapshot.completedResults()).hasSize(1);
        }
    }

    @Nested
    class StaticFactoryMethods {

        @Test
        void shouldCreateEmptySnapshot() {
            PlanSnapshot snapshot = PlanSnapshot.empty("node-1");

            assertThat(snapshot.nodeId()).isEqualTo("node-1");
            assertThat(snapshot.steps()).isEmpty();
            assertThat(snapshot.currentStepIndex()).isZero();
            assertThat(snapshot.completedResults()).isEmpty();
        }
    }

    @Nested
    class CompletionStatus {

        @Test
        void shouldBeCompleteWhenNoSteps() {
            PlanSnapshot snapshot = PlanSnapshot.empty("node-1");

            assertThat(snapshot.isComplete()).isTrue();
            assertThat(snapshot.remainingSteps()).isZero();
        }

        @Test
        void shouldBeCompleteWhenAllStepsExecuted() {
            List<PlannedStepSnapshot> steps =
                    List.of(
                            new PlannedStepSnapshot(0, "tool1", Map.of(), "step 1", "COMPLETED"),
                            new PlannedStepSnapshot(1, "tool2", Map.of(), "step 2", "COMPLETED"));

            PlanSnapshot snapshot = new PlanSnapshot("node-1", steps, 2, List.of());

            assertThat(snapshot.isComplete()).isTrue();
            assertThat(snapshot.remainingSteps()).isZero();
        }

        @Test
        void shouldNotBeCompleteWhenStepsRemain() {
            List<PlannedStepSnapshot> steps =
                    List.of(
                            new PlannedStepSnapshot(0, "tool1", Map.of(), "step 1", "COMPLETED"),
                            new PlannedStepSnapshot(1, "tool2", Map.of(), "step 2", "PENDING"));

            PlanSnapshot snapshot = new PlanSnapshot("node-1", steps, 1, List.of());

            assertThat(snapshot.isComplete()).isFalse();
            assertThat(snapshot.remainingSteps()).isEqualTo(1);
        }

        @Test
        void shouldCalculateRemainingStepsCorrectly() {
            List<PlannedStepSnapshot> steps =
                    List.of(
                            new PlannedStepSnapshot(0, "tool1", Map.of(), "step 1", "PENDING"),
                            new PlannedStepSnapshot(1, "tool2", Map.of(), "step 2", "PENDING"),
                            new PlannedStepSnapshot(2, "tool3", Map.of(), "step 3", "PENDING"));

            PlanSnapshot atStart = new PlanSnapshot("node-1", steps, 0, List.of());
            PlanSnapshot atMiddle = new PlanSnapshot("node-1", steps, 1, List.of());
            PlanSnapshot atEnd = new PlanSnapshot("node-1", steps, 3, List.of());

            assertThat(atStart.remainingSteps()).isEqualTo(3);
            assertThat(atMiddle.remainingSteps()).isEqualTo(2);
            assertThat(atEnd.remainingSteps()).isZero();
        }
    }

    @Nested
    class NestedRecords {

        @Test
        void shouldCreatePlannedStepSnapshot() {
            PlannedStepSnapshot step =
                    new PlannedStepSnapshot(
                            0,
                            "search_tool",
                            Map.of("query", "test"),
                            "Search for data",
                            "PENDING");

            assertThat(step.index()).isZero();
            assertThat(step.toolName()).isEqualTo("search_tool");
            assertThat(step.arguments()).containsEntry("query", "test");
            assertThat(step.description()).isEqualTo("Search for data");
            assertThat(step.status()).isEqualTo("PENDING");
        }

        @Test
        void shouldCreateStepResultSnapshot() {
            StepResultSnapshot result =
                    new StepResultSnapshot(0, "search_tool", true, "found 5 results", null, 150);

            assertThat(result.stepIndex()).isZero();
            assertThat(result.toolName()).isEqualTo("search_tool");
            assertThat(result.success()).isTrue();
            assertThat(result.output()).isEqualTo("found 5 results");
            assertThat(result.error()).isNull();
            assertThat(result.durationMillis()).isEqualTo(150);
        }

        @Test
        void shouldCreateFailedStepResultSnapshot() {
            StepResultSnapshot result =
                    new StepResultSnapshot(1, "api_call", false, null, "Connection timeout", 5000);

            assertThat(result.success()).isFalse();
            assertThat(result.output()).isNull();
            assertThat(result.error()).isEqualTo("Connection timeout");
        }
    }
}
