package io.hensu.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentResponseTest {

    @Test
    void shouldCreateSuccessResponseWithOutput() {
        // When
        AgentResponse response = AgentResponse.success("Hello, world!");

        // Then
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getOutput()).isEqualTo("Hello, world!");
        assertThat(response.getTimestamp()).isNotNull();
        assertThat(response.getError()).isNull();
        assertThat(response.getMetadata()).isEmpty();
    }

    @Test
    void shouldCreateSuccessResponseWithMetadata() {
        // Given
        Map<String, Object> metadata = Map.of("model", "gpt-4", "tokens", 150);

        // When
        AgentResponse response = AgentResponse.success("Response text", metadata);

        // Then
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getOutput()).isEqualTo("Response text");
        assertThat(response.getMetadata()).containsEntry("model", "gpt-4");
        assertThat(response.getMetadata()).containsEntry("tokens", 150);
    }

    @Test
    void shouldCreateFailureResponseWithError() {
        // Given
        RuntimeException error = new RuntimeException("API error");

        // When
        AgentResponse response = AgentResponse.failure(error);

        // Then
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getError()).isEqualTo(error);
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void shouldBuildResponseWithAllFields() {
        // Given
        Instant timestamp = Instant.now();
        RuntimeException error = new RuntimeException("Error");

        // When
        AgentResponse response =
                AgentResponse.builder()
                        .success(false)
                        .output("Error occurred")
                        .metadata(Map.of("code", 500))
                        .timestamp(timestamp)
                        .error(error)
                        .build();

        // Then
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getOutput()).isEqualTo("Error occurred");
        assertThat(response.getMetadata()).containsEntry("code", 500);
        assertThat(response.getTimestamp()).isEqualTo(timestamp);
        assertThat(response.getError()).isEqualTo(error);
    }

    @Test
    void shouldUseDefaultValuesInBuilder() {
        // When
        AgentResponse response = AgentResponse.builder().build();

        // Then
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getOutput()).isEqualTo("");
        assertThat(response.getMetadata()).isEmpty();
        assertThat(response.getTimestamp()).isNotNull();
        assertThat(response.getError()).isNull();
    }

    @Test
    void shouldMakeMetadataImmutable() {
        // When
        AgentResponse response = AgentResponse.builder().metadata(Map.of("key", "value")).build();

        // Then
        assertThat(response.getMetadata()).isUnmodifiable();
    }
}
