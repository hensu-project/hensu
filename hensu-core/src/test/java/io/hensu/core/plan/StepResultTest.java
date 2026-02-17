package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StepResultTest {

    @Nested
    class ConstructorValidation {

        @Test
        void shouldThrowWhenToolNameIsNull() {
            assertThatThrownBy(
                            () ->
                                    new StepResult(
                                            0, null, true, "output", null, Duration.ZERO, Map.of()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("toolName");
        }

        @Test
        void shouldDefaultDurationToZero() {
            StepResult result = new StepResult(0, "tool", true, "output", null, null, Map.of());

            assertThat(result.duration()).isEqualTo(Duration.ZERO);
        }

        @Test
        void shouldDefaultMetadataToEmptyMap() {
            StepResult result =
                    new StepResult(0, "tool", true, "output", null, Duration.ZERO, null);

            assertThat(result.metadata()).isNotNull().isEmpty();
        }
    }

    @Nested
    class StaticFactoryMethods {

        @Test
        void shouldCreateSuccessResult() {
            StepResult result =
                    StepResult.success(0, "search", "5 results", Duration.ofMillis(100));

            assertThat(result.stepIndex()).isZero();
            assertThat(result.toolName()).isEqualTo("search");
            assertThat(result.success()).isTrue();
            assertThat(result.output()).isEqualTo("5 results");
            assertThat(result.error()).isNull();
            assertThat(result.duration()).isEqualTo(Duration.ofMillis(100));
        }

        @Test
        void shouldCreateFailureResult() {
            StepResult result =
                    StepResult.failure(1, "api_call", "Connection failed", Duration.ofSeconds(5));

            assertThat(result.stepIndex()).isEqualTo(1);
            assertThat(result.toolName()).isEqualTo("api_call");
            assertThat(result.success()).isFalse();
            assertThat(result.output()).isNull();
            assertThat(result.error()).isEqualTo("Connection failed");
            assertThat(result.duration()).isEqualTo(Duration.ofSeconds(5));
        }
    }

    @Nested
    class MetadataHandling {

        @Test
        void shouldAddMetadata() {
            StepResult original = StepResult.success(0, "tool", "output", Duration.ZERO);

            StepResult withMeta = original.withMetadata("key", "value");

            assertThat(original.metadata()).isEmpty();
            assertThat(withMeta.metadata()).containsEntry("key", "value");
        }

        @Test
        void shouldPreserveExistingMetadata() {
            StepResult original =
                    new StepResult(
                            0,
                            "tool",
                            true,
                            "output",
                            null,
                            Duration.ZERO,
                            Map.of("existing", "value"));

            StepResult withMore = original.withMetadata("new", "data");

            assertThat(withMore.metadata())
                    .containsEntry("existing", "value")
                    .containsEntry("new", "data");
        }
    }

    @Nested
    class QueryMethods {

        @Test
        void shouldDetectFailure() {
            StepResult success = StepResult.success(0, "tool", "out", Duration.ZERO);
            StepResult failure = StepResult.failure(0, "tool", "err", Duration.ZERO);

            assertThat(success.isFailure()).isFalse();
            assertThat(failure.isFailure()).isTrue();
        }
    }
}
