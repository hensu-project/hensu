package io.hensu.serialization;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.BacktrackEvent;
import io.hensu.core.execution.result.BacktrackType;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.execution.result.ResultStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Round-trip serialization tests for execution history types.
///
/// Verifies that `NodeResult`, `ExecutionStep`, `BacktrackEvent`, and
/// `ExecutionHistory` survive JSON serialization via the `HensuJacksonModule`
/// mixins. These types are persisted as JSONB in the server's PostgreSQL
/// `execution_states` table.
///
/// @see HensuJacksonModule for mixin registrations
/// @see WorkflowSerializerTest for workflow-level serialization tests
class ExecutionHistorySerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = WorkflowSerializer.createMapper();
    }

    // --- NodeResult ---

    @Test
    void roundTrip_nodeResult_success() throws JsonProcessingException {
        NodeResult original =
                NodeResult.builder()
                        .status(ResultStatus.SUCCESS)
                        .output("Generated content")
                        .metadata(Map.of("tokens", 150, "model", "claude-sonnet-4"))
                        .timestamp(Instant.parse("2025-01-15T10:30:00Z"))
                        .build();

        String json = mapper.writeValueAsString(original);
        NodeResult restored = mapper.readValue(json, NodeResult.class);

        assertThat(restored.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(restored.getOutput()).isEqualTo("Generated content");
        assertThat(restored.getMetadata()).containsEntry("tokens", 150);
        assertThat(restored.getMetadata()).containsEntry("model", "claude-sonnet-4");
        assertThat(restored.getTimestamp()).isEqualTo(Instant.parse("2025-01-15T10:30:00Z"));
    }

    @Test
    void roundTrip_nodeResult_failure() throws JsonProcessingException {
        NodeResult original =
                NodeResult.builder()
                        .status(ResultStatus.FAILURE)
                        .output("Rate limit exceeded")
                        .timestamp(Instant.parse("2025-01-15T10:30:00Z"))
                        .build();

        String json = mapper.writeValueAsString(original);
        NodeResult restored = mapper.readValue(json, NodeResult.class);

        assertThat(restored.getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(restored.getOutput()).isEqualTo("Rate limit exceeded");
    }

    @Test
    void roundTrip_nodeResult_errorFieldIgnored() throws JsonProcessingException {
        NodeResult original =
                NodeResult.builder()
                        .status(ResultStatus.FAILURE)
                        .output("Something broke")
                        .error(new RuntimeException("should not serialize"))
                        .timestamp(Instant.parse("2025-01-15T10:30:00Z"))
                        .build();

        String json = mapper.writeValueAsString(original);

        assertThat(json).doesNotContain("RuntimeException");
        assertThat(json).doesNotContain("should not serialize");

        NodeResult restored = mapper.readValue(json, NodeResult.class);
        assertThat(restored.getError()).isNull();
        assertThat(restored.getStatus()).isEqualTo(ResultStatus.FAILURE);
    }

    @Test
    void roundTrip_nodeResult_emptyMetadata() throws JsonProcessingException {
        NodeResult original = NodeResult.success("output", Map.of());

        String json = mapper.writeValueAsString(original);
        NodeResult restored = mapper.readValue(json, NodeResult.class);

        assertThat(restored.getMetadata()).isEmpty();
        assertThat(restored.getOutput()).isEqualTo("output");
    }

    @Test
    void roundTrip_nodeResult_endStatus() throws JsonProcessingException {
        NodeResult original = NodeResult.end();

        String json = mapper.writeValueAsString(original);
        NodeResult restored = mapper.readValue(json, NodeResult.class);

        assertThat(restored.getStatus()).isEqualTo(ResultStatus.END);
    }

    // --- ExecutionStep ---

    @Test
    void roundTrip_executionStep() throws JsonProcessingException {
        ExecutionStep original =
                ExecutionStep.builder()
                        .nodeId("process")
                        .result(NodeResult.success("Generated article", Map.of("tokens", 200)))
                        .timestamp(Instant.parse("2025-01-15T10:30:00Z"))
                        .build();

        String json = mapper.writeValueAsString(original);
        ExecutionStep restored = mapper.readValue(json, ExecutionStep.class);

        assertThat(restored.getNodeId()).isEqualTo("process");
        assertThat(restored.getResult().getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(restored.getResult().getOutput()).isEqualTo("Generated article");
        assertThat(restored.getResult().getMetadata()).containsEntry("tokens", 200);
        assertThat(restored.getTimestamp()).isEqualTo(Instant.parse("2025-01-15T10:30:00Z"));
    }

    @Test
    void roundTrip_executionStep_withFailedResult() throws JsonProcessingException {
        ExecutionStep original =
                ExecutionStep.builder()
                        .nodeId("validate")
                        .result(NodeResult.failure("Validation failed"))
                        .build();

        String json = mapper.writeValueAsString(original);
        ExecutionStep restored = mapper.readValue(json, ExecutionStep.class);

        assertThat(restored.getNodeId()).isEqualTo("validate");
        assertThat(restored.getResult().getStatus()).isEqualTo(ResultStatus.FAILURE);
        assertThat(restored.getResult().getOutput()).isEqualTo("Validation failed");
    }

    // --- BacktrackEvent ---

    @Test
    void roundTrip_backtrackEvent_automatic() throws JsonProcessingException {
        BacktrackEvent original =
                BacktrackEvent.builder()
                        .from("review")
                        .to("process")
                        .reason("Score below threshold")
                        .type(BacktrackType.AUTOMATIC)
                        .rubricScore(3.5)
                        .timestamp(Instant.parse("2025-01-15T10:30:00Z"))
                        .build();

        String json = mapper.writeValueAsString(original);
        BacktrackEvent restored = mapper.readValue(json, BacktrackEvent.class);

        assertThat(restored.getFrom()).isEqualTo("review");
        assertThat(restored.getTo()).isEqualTo("process");
        assertThat(restored.getReason()).isEqualTo("Score below threshold");
        assertThat(restored.getType()).isEqualTo(BacktrackType.AUTOMATIC);
        assertThat(restored.getRubricScore()).isEqualTo(3.5);
        assertThat(restored.getTimestamp()).isEqualTo(Instant.parse("2025-01-15T10:30:00Z"));
    }

    @Test
    void roundTrip_backtrackEvent_manual() throws JsonProcessingException {
        BacktrackEvent original =
                BacktrackEvent.builder()
                        .from("finalize")
                        .to("draft")
                        .reason("Reviewer requested changes")
                        .type(BacktrackType.MANUAL)
                        .timestamp(Instant.parse("2025-01-15T10:30:00Z"))
                        .build();

        String json = mapper.writeValueAsString(original);
        BacktrackEvent restored = mapper.readValue(json, BacktrackEvent.class);

        assertThat(restored.getFrom()).isEqualTo("finalize");
        assertThat(restored.getTo()).isEqualTo("draft");
        assertThat(restored.getType()).isEqualTo(BacktrackType.MANUAL);
        assertThat(restored.getRubricScore()).isNull();
    }

    @Test
    void roundTrip_backtrackEvent_jump() throws JsonProcessingException {
        BacktrackEvent original =
                BacktrackEvent.builder()
                        .from("evaluate")
                        .to("refine")
                        .reason("Score-based routing")
                        .type(BacktrackType.JUMP)
                        .rubricScore(7.2)
                        .timestamp(Instant.parse("2025-01-15T10:30:00Z"))
                        .build();

        String json = mapper.writeValueAsString(original);
        BacktrackEvent restored = mapper.readValue(json, BacktrackEvent.class);

        assertThat(restored.getType()).isEqualTo(BacktrackType.JUMP);
        assertThat(restored.getRubricScore()).isEqualTo(7.2);
    }

    // --- ExecutionHistory ---

    @Test
    void roundTrip_executionHistory_withSteps() throws JsonProcessingException {
        ExecutionHistory original = new ExecutionHistory();
        original.addStep(
                ExecutionStep.builder()
                        .nodeId("process")
                        .result(NodeResult.success("First output", Map.of()))
                        .timestamp(Instant.parse("2025-01-15T10:30:00Z"))
                        .build());
        original.addStep(
                ExecutionStep.builder()
                        .nodeId("review")
                        .result(NodeResult.success("Reviewed output", Map.of("score", 8.5)))
                        .timestamp(Instant.parse("2025-01-15T10:31:00Z"))
                        .build());

        String json = mapper.writeValueAsString(original);
        ExecutionHistory restored = mapper.readValue(json, ExecutionHistory.class);

        assertThat(restored.getSteps()).hasSize(2);
        assertThat(restored.getSteps().get(0).getNodeId()).isEqualTo("process");
        assertThat(restored.getSteps().get(1).getNodeId()).isEqualTo("review");
        assertThat(restored.getSteps().get(1).getResult().getMetadata())
                .containsEntry("score", 8.5);
    }

    @Test
    void roundTrip_executionHistory_withBacktracks() throws JsonProcessingException {
        ExecutionHistory original = new ExecutionHistory();
        original.addStep(
                ExecutionStep.builder()
                        .nodeId("process")
                        .result(NodeResult.success("Draft", Map.of()))
                        .build());
        original.addBacktrack(
                BacktrackEvent.builder()
                        .from("review")
                        .to("process")
                        .reason("Quality too low")
                        .type(BacktrackType.AUTOMATIC)
                        .rubricScore(2.5)
                        .timestamp(Instant.parse("2025-01-15T10:32:00Z"))
                        .build());

        String json = mapper.writeValueAsString(original);
        ExecutionHistory restored = mapper.readValue(json, ExecutionHistory.class);

        assertThat(restored.getSteps()).hasSize(1);
        assertThat(restored.getBacktracks()).hasSize(1);
        assertThat(restored.getBacktracks().getFirst().getFrom()).isEqualTo("review");
        assertThat(restored.getBacktracks().getFirst().getRubricScore()).isEqualTo(2.5);
    }

    @Test
    void roundTrip_executionHistory_empty() throws JsonProcessingException {
        ExecutionHistory original = new ExecutionHistory();

        String json = mapper.writeValueAsString(original);
        ExecutionHistory restored = mapper.readValue(json, ExecutionHistory.class);

        assertThat(restored.getSteps()).isEmpty();
        assertThat(restored.getBacktracks()).isEmpty();
    }

    @Test
    void roundTrip_executionHistory_remainsMutableAfterDeserialization()
            throws JsonProcessingException {
        ExecutionHistory original = new ExecutionHistory();
        original.addStep(
                ExecutionStep.builder()
                        .nodeId("first")
                        .result(NodeResult.success("output", Map.of()))
                        .build());

        String json = mapper.writeValueAsString(original);
        ExecutionHistory restored = mapper.readValue(json, ExecutionHistory.class);

        // Must remain mutable — resumed executions add steps to deserialized history
        restored.addStep(
                ExecutionStep.builder()
                        .nodeId("second")
                        .result(NodeResult.success("more output", Map.of()))
                        .build());

        assertThat(restored.getSteps()).hasSize(2);
    }
}
