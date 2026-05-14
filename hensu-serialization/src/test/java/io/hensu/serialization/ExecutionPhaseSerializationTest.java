package io.hensu.serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.ExecutionPhase;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Round-trip serialization tests for {@link ExecutionPhase} sealed subtypes.
///
/// Verifies that the manual {@link ExecutionPhaseSerializer} /
/// {@link ExecutionPhaseDeserializer} pair correctly handles all three
/// subtypes, including nested {@link NodeResult} delegation and
/// {@link Instant} formatting via JavaTimeModule.
///
/// @see ExecutionHistorySerializationTest for the analogous NodeResult/ExecutionStep tests
class ExecutionPhaseSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = WorkflowSerializer.createMapper();
    }

    @Test
    void roundTrip_initial() throws JsonProcessingException {
        String json = mapper.writeValueAsString(ExecutionPhase.INITIAL);
        assertThat(json).contains("\"type\" : \"initial\"");

        ExecutionPhase restored = mapper.readValue(json, ExecutionPhase.class);
        assertThat(restored).isInstanceOf(ExecutionPhase.Initial.class);
    }

    @Test
    void roundTrip_terminal() throws JsonProcessingException {
        String json = mapper.writeValueAsString(ExecutionPhase.TERMINAL);
        assertThat(json).contains("\"type\" : \"terminal\"");

        ExecutionPhase restored = mapper.readValue(json, ExecutionPhase.class);
        assertThat(restored).isInstanceOf(ExecutionPhase.Terminal.class);
    }

    @Test
    void roundTrip_awaiting() throws JsonProcessingException {
        Instant requestedAt = Instant.parse("2025-06-01T14:30:00Z");
        NodeResult cachedResult =
                NodeResult.builder()
                        .status(ResultStatus.SUCCESS)
                        .output("Generated article")
                        .metadata(Map.of("tokens", 200, "model", "claude-sonnet-4"))
                        .timestamp(Instant.parse("2025-06-01T14:29:55Z"))
                        .build();

        var original =
                new ExecutionPhase.Awaiting(
                        "review-node",
                        "ReviewPostProcessor",
                        cachedResult,
                        "corr-abc-123",
                        requestedAt);

        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\" : \"awaiting_post_processor\"");

        ExecutionPhase restored = mapper.readValue(json, ExecutionPhase.class);
        assertThat(restored).isInstanceOf(ExecutionPhase.Awaiting.class);

        var awaiting = (ExecutionPhase.Awaiting) restored;
        assertThat(awaiting.nodeId()).isEqualTo("review-node");
        assertThat(awaiting.processorId()).isEqualTo("ReviewPostProcessor");
        assertThat(awaiting.correlationId()).isEqualTo("corr-abc-123");
        assertThat(awaiting.requestedAt()).isEqualTo(requestedAt);

        // Nested NodeResult delegated through mixin-based serde
        assertThat(awaiting.cachedResult().getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(awaiting.cachedResult().getOutput()).isEqualTo("Generated article");
        assertThat(awaiting.cachedResult().getMetadata()).containsEntry("tokens", 200);
        assertThat(awaiting.cachedResult().getMetadata()).containsEntry("model", "claude-sonnet-4");
        assertThat(awaiting.cachedResult().getTimestamp())
                .isEqualTo(Instant.parse("2025-06-01T14:29:55Z"));
    }

    @Test
    void roundTrip_awaiting_withNullMetadataValues() throws JsonProcessingException {
        // Map.of() rejects null values; production metadata can contain them via Jackson
        var metadata = new java.util.HashMap<String, Object>();
        metadata.put("key", "value");
        metadata.put("nullable", null);

        NodeResult cachedResult =
                NodeResult.builder()
                        .status(ResultStatus.SUCCESS)
                        .output("output")
                        .metadata(metadata)
                        .timestamp(Instant.parse("2025-06-01T14:30:00Z"))
                        .build();

        var original =
                new ExecutionPhase.Awaiting(
                        "node-1",
                        "TestProcessor",
                        cachedResult,
                        "corr-null-meta",
                        Instant.parse("2025-06-01T14:30:00Z"));

        String json = mapper.writeValueAsString(original);
        ExecutionPhase restored = mapper.readValue(json, ExecutionPhase.class);

        var awaiting = (ExecutionPhase.Awaiting) restored;
        assertThat(awaiting.cachedResult().getMetadata()).containsEntry("key", "value");
        assertThat(awaiting.cachedResult().getMetadata()).containsKey("nullable");
    }

    @Test
    void deserialize_unknownType_throwsIOException() {
        String badJson =
                """
                {"type":"nonexistent"}""";

        assertThatThrownBy(() -> mapper.readValue(badJson, ExecutionPhase.class))
                .hasMessageContaining("Unknown ExecutionPhase type: nonexistent");
    }
}
