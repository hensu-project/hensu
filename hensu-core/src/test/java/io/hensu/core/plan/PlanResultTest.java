package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.plan.PlanResult.PlanStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlanResultTest {

    @Nested
    class ConstructorValidation {

        @Test
        void shouldThrowWhenStatusIsNull() {
            assertThatThrownBy(() -> new PlanResult(null, List.of(), -1, Duration.ZERO, null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("status");
        }

        @Test
        void shouldDefaultStepResultsToEmptyList() {
            PlanResult result =
                    new PlanResult(PlanStatus.COMPLETED, null, -1, Duration.ZERO, null, null);

            assertThat(result.stepResults()).isNotNull().isEmpty();
        }

        @Test
        void shouldDefaultTotalDurationToZero() {
            PlanResult result =
                    new PlanResult(PlanStatus.COMPLETED, List.of(), -1, null, null, null);

            assertThat(result.totalDuration()).isEqualTo(Duration.ZERO);
        }
    }

    @Nested
    class StaticFactoryMethods {

        @Test
        void shouldCreateCompletedResult() {
            List<StepResult> steps =
                    List.of(
                            StepResult.success(0, "t1", "out1", Duration.ofMillis(100)),
                            StepResult.success(1, "t2", "out2", Duration.ofMillis(200)));

            PlanResult result = PlanResult.completed(steps);

            assertThat(result.status()).isEqualTo(PlanStatus.COMPLETED);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.stepResults()).hasSize(2);
            assertThat(result.totalDuration()).isEqualTo(Duration.ofMillis(300));
            assertThat(result.output()).isEqualTo("out2");
            assertThat(result.failedAtStep()).isEqualTo(-1);
        }

        @Test
        void shouldCreateFailedResultSimple() {
            PlanResult result = PlanResult.failed(2, "Step 2 failed");

            assertThat(result.status()).isEqualTo(PlanStatus.FAILED);
            assertThat(result.isFailure()).isTrue();
            assertThat(result.failedAtStep()).isEqualTo(2);
            assertThat(result.error()).isEqualTo("Step 2 failed");
        }

        @Test
        void shouldCreateFailedResultWithSteps() {
            List<StepResult> steps =
                    List.of(
                            StepResult.success(0, "t1", "out", Duration.ofMillis(100)),
                            StepResult.failure(1, "t2", "error", Duration.ofMillis(50)));

            PlanResult result = PlanResult.failed(steps, 1, "Step 1 failed");

            assertThat(result.status()).isEqualTo(PlanStatus.FAILED);
            assertThat(result.stepResults()).hasSize(2);
            assertThat(result.totalDuration()).isEqualTo(Duration.ofMillis(150));
        }

        @Test
        void shouldCreateTimeoutResult() {
            List<StepResult> steps =
                    List.of(StepResult.success(0, "t1", "out", Duration.ofMinutes(4)));

            PlanResult result = PlanResult.timeout(steps, Duration.ofMinutes(5));

            assertThat(result.status()).isEqualTo(PlanStatus.TIMEOUT);
            assertThat(result.error()).contains("exceeded maximum duration");
        }

        @Test
        void shouldCreateCancelledResult() {
            List<StepResult> steps =
                    List.of(StepResult.success(0, "t1", "out", Duration.ofSeconds(1)));

            PlanResult result = PlanResult.cancelled(steps, "User cancelled");

            assertThat(result.status()).isEqualTo(PlanStatus.CANCELLED);
            assertThat(result.error()).isEqualTo("User cancelled");
        }
    }

    @Nested
    class QueryMethods {

        @Test
        void shouldCountCompletedSteps() {
            List<StepResult> steps =
                    List.of(
                            StepResult.success(0, "t1", "out", Duration.ZERO),
                            StepResult.success(1, "t2", "out", Duration.ZERO),
                            StepResult.failure(2, "t3", "err", Duration.ZERO));

            PlanResult result = PlanResult.failed(steps, 2, "Step failed");

            assertThat(result.completedStepCount()).isEqualTo(2);
        }

        @Test
        void shouldCountFailedSteps() {
            List<StepResult> steps =
                    List.of(
                            StepResult.success(0, "t1", "out", Duration.ZERO),
                            StepResult.failure(1, "t2", "err", Duration.ZERO));

            PlanResult result = PlanResult.failed(steps, 1, "Step failed");

            assertThat(result.failedStepCount()).isEqualTo(1);
        }

        @Test
        void shouldDetectSuccessAndFailure() {
            PlanResult success = PlanResult.completed(List.of());
            PlanResult failure = PlanResult.failed(0, "error");

            assertThat(success.isSuccess()).isTrue();
            assertThat(success.isFailure()).isFalse();
            assertThat(failure.isSuccess()).isFalse();
            assertThat(failure.isFailure()).isTrue();
        }
    }
}
