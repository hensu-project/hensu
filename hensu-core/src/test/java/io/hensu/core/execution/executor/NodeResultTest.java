package io.hensu.core.execution.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.result.ResultStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NodeResultTest {

    @Test
    void shouldCreateSuccessResultWithOutput() {
        // When
        NodeResult result = NodeResult.success("Test output", Map.of("key", "value"));

        // Then
        assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Test output");
        assertThat(result.getMetadata()).containsEntry("key", "value");
        assertThat(result.getTimestamp()).isNotNull();
        assertThat(result.getError()).isNull();
    }

    @Test
    void shouldCreateFailureResultWithError() {
        // Given
        RuntimeException error = new RuntimeException("Test error");

        // When
        NodeResult result = NodeResult.failure(error);

        // Then
        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo(error);
        assertThat(result.getTimestamp()).isNotNull();
    }

    @Test
    void shouldCreateFailureResultWithMessage() {
        // When
        NodeResult result = NodeResult.failure("Something went wrong");

        // Then
        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getOutput()).isEqualTo("Something went wrong");
    }

    @Test
    void shouldCreateEndResult() {
        // When
        NodeResult result = NodeResult.end();

        // Then
        assertThat(result.getStatus()).isEqualTo(ResultStatus.END);
        assertThat(result.getTimestamp()).isNotNull();
    }

    @Test
    void shouldCreateEmptyResult() {
        // When
        NodeResult result = NodeResult.empty();

        // Then
        assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("");
    }

    @Test
    void shouldBuildResultWithAllFields() {
        // Given
        Instant timestamp = Instant.now();
        RuntimeException error = new RuntimeException("Error");

        // When
        NodeResult result =
                NodeResult.builder()
                        .status(ResultStatus.FAILURE)
                        .output("Error output")
                        .metadata(Map.of("debug", "info"))
                        .error(error)
                        .timestamp(timestamp)
                        .build();

        // Then
        assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(result.getOutput()).isEqualTo("Error output");
        assertThat(result.getMetadata()).containsEntry("debug", "info");
        assertThat(result.getError()).isEqualTo(error);
        assertThat(result.getTimestamp()).isEqualTo(timestamp);
    }

    @Test
    void shouldUseDefaultValuesInBuilder() {
        // When
        NodeResult result = NodeResult.builder().build();

        // Then
        assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(result.getOutput()).isEqualTo("");
        assertThat(result.getMetadata()).isEmpty();
        assertThat(result.getTimestamp()).isNotNull();
    }

    @Test
    void shouldMakeMetadataImmutableFromBuilder() {
        // When
        NodeResult result = NodeResult.builder().metadata(Map.of("key", "value")).build();

        // Then - metadata should be immutable
        assertThat(result.getMetadata()).isUnmodifiable();
    }

    @Test
    void shouldCreateResultWithConstructorDirectly() {
        // Given
        Map<String, Object> metadata = Map.of("test", "data");

        // When
        NodeResult result = new NodeResult(ResultStatus.SUCCESS, "Direct output", metadata);

        // Then
        assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(result.getOutput()).isEqualTo("Direct output");
        assertThat(result.getMetadata()).containsEntry("test", "data");
    }
}
