package io.hensu.cli.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DaemonFrameSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // — ReviewPayload round-trip ——————————————————————————————————————————————

    @Test
    void shouldRoundTripReviewRequestFrame() throws Exception {
        var payload =
                new DaemonFrame.ReviewPayload(
                        "node-1",
                        "Generated output text",
                        "SUCCESS",
                        0.85,
                        true,
                        true,
                        List.of(
                                new DaemonFrame.HistoryStep("step-1", "SUCCESS", "Do thing A"),
                                new DaemonFrame.HistoryStep("step-2", "SUCCESS", null)),
                        "{\"id\":\"wf-1\"}",
                        Map.of("key", "value"));

        DaemonFrame frame = DaemonFrame.reviewRequest("exec-1", "review-42", payload);

        String json = mapper.writeValueAsString(frame);
        DaemonFrame deserialized = mapper.readValue(json, DaemonFrame.class);

        assertThat(deserialized.type).isEqualTo("review_request");
        assertThat(deserialized.execId).isEqualTo("exec-1");
        assertThat(deserialized.reviewId).isEqualTo("review-42");

        DaemonFrame.ReviewPayload p = deserialized.reviewPayload;
        assertThat(p.nodeId()).isEqualTo("node-1");
        assertThat(p.output()).isEqualTo("Generated output text");
        assertThat(p.status()).isEqualTo("SUCCESS");
        assertThat(p.rubricScore()).isEqualTo(0.85);
        assertThat(p.rubricPassed()).isTrue();
        assertThat(p.allowBacktrack()).isTrue();
        assertThat(p.historySteps()).hasSize(2);
        assertThat(p.historySteps().get(0).nodeId()).isEqualTo("step-1");
        assertThat(p.historySteps().get(1).promptTemplate()).isNull();
        assertThat(p.workflowJson()).isEqualTo("{\"id\":\"wf-1\"}");
        assertThat(p.context()).containsEntry("key", "value");
    }

    // — ReviewResponse round-trip —————————————————————————————————————————————

    @Test
    void shouldRoundTripReviewResponseFrame() throws Exception {
        DaemonFrame frame =
                DaemonFrame.reviewResponse(
                        "exec-1",
                        "review-42",
                        "backtrack",
                        "step-1",
                        "Output was wrong",
                        Map.of("temperature", 0.5));

        String json = mapper.writeValueAsString(frame);
        DaemonFrame deserialized = mapper.readValue(json, DaemonFrame.class);

        assertThat(deserialized.type).isEqualTo("review_response");
        assertThat(deserialized.execId).isEqualTo("exec-1");
        assertThat(deserialized.reviewId).isEqualTo("review-42");
        assertThat(deserialized.decision).isEqualTo("backtrack");
        assertThat(deserialized.backtrackNodeId).isEqualTo("step-1");
        assertThat(deserialized.backtrackReason).isEqualTo("Output was wrong");
        assertThat(deserialized.editedContext).containsEntry("temperature", 0.5);
    }

    // — NON_NULL omission —————————————————————————————————————————————————————

    @Test
    void shouldOmitNullFieldsInSerialization() throws Exception {
        DaemonFrame frame =
                DaemonFrame.reviewResponse("exec-1", "review-42", "approve", null, null, null);

        String json = mapper.writeValueAsString(frame);
        JsonNode tree = mapper.readTree(json);

        assertThat(tree.has("t")).isTrue();
        assertThat(tree.get("t").asText()).isEqualTo("review_response");
        assertThat(tree.has("backtrack_node")).isFalse();
        assertThat(tree.has("backtrack_reason")).isFalse();
        assertThat(tree.has("edited_context")).isFalse();
        assertThat(tree.has("review_payload")).isFalse();
    }

    // — PsEntry round-trip ————————————————————————————————————————————————————

    @Test
    void shouldRoundTripPsResponseFrame() throws Exception {
        DaemonFrame frame =
                DaemonFrame.psResponse(
                        List.of(
                                new DaemonFrame.PsEntry("e1", "wf-1", "RUNNING", "node-2", 1500),
                                new DaemonFrame.PsEntry(
                                        "e2", "wf-2", "AWAITING_REVIEW", "node-3", 30000)));

        String json = mapper.writeValueAsString(frame);
        DaemonFrame deserialized = mapper.readValue(json, DaemonFrame.class);

        assertThat(deserialized.executions).hasSize(2);
        assertThat(deserialized.executions.get(0).execId()).isEqualTo("e1");
        assertThat(deserialized.executions.get(1).status()).isEqualTo("AWAITING_REVIEW");
    }
}
