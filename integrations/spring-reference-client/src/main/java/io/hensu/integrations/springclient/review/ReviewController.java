package io.hensu.integrations.springclient.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.integrations.springclient.client.HensuClient;
import io.hensu.integrations.springclient.demo.ExecutionEventHandler;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

/// Human-in-the-loop review endpoint.
///
/// When hensu-server pauses a workflow at a human review node, it emits an
/// `execution.paused` SSE event containing a `correlationId`. An operator
/// (or automated system) calls this endpoint to submit their decision, which
/// is forwarded to the server as a resume request.
///
/// After submitting the review, the controller re-subscribes to the SSE
/// stream via {@link ExecutionEventHandler}. The server closes the original
/// SSE connection on pause (pauses can last days/weeks), so a fresh
/// subscription is needed to receive post-resume events like
/// {@code execution.completed}.
///
/// ### Workflow
/// ```
/// 1. SSE: execution.paused → {executionId, nodeId, correlationId, reason}
/// 2. Operator calls POST /demo/review/{executionId}
/// 3. This controller calls POST /api/v1/executions/{id}/resume on hensu-server
/// 4. Client re-subscribes to SSE for post-resume events
/// 5. Workflow unblocks and continues (or terminates on rejection)
/// ```
///
/// ### Example
/// Approve without modifications:
/// ```
/// curl -X POST http://localhost:8081/demo/review/exec-abc \
///      -H 'Content-Type: application/json' \
///      -d '{"correlationId": "corr-123", "approved": true, "modifications": {}}'
/// ```
///
/// Reject:
/// ```
/// curl -X POST http://localhost:8081/demo/review/exec-abc \
///      -H 'Content-Type: application/json' \
///      -d '{"correlationId": "corr-123", "approved": false, "modifications": {}}'
/// ```
///
/// Approve with context override:
/// ```
/// curl -X POST http://localhost:8081/demo/review/exec-abc \
///      -H 'Content-Type: application/json' \
///      -d '{"correlationId": "corr-123", "approved": true, "modifications": {"approvedLimit": 75000}}'
/// ```
///
/// `correlationId` and `approved` are both required. A body missing either is answered with a
/// 400 naming the field:
/// ```
/// {"executionId": "exec-abc", "error": "'correlationId' is required — use the value from the execution.paused event"}
/// ```
///
/// When the server refuses the resume — a stale correlation id, an execution that already
/// finished, a decision it cannot apply — the server's status code and error message are
/// propagated in the same shape, rather than surfacing as an opaque 500 from this client:
/// ```
/// {"executionId": "exec-abc", "error": "Cannot apply review decision: execution is not awaiting review"}
/// ```
@RestController
@RequestMapping("/demo")
public class ReviewController {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewController.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HensuClient hensuClient;
    private final ExecutionEventHandler eventHandler;

    public ReviewController(HensuClient hensuClient, ExecutionEventHandler eventHandler) {
        this.hensuClient = hensuClient;
        this.eventHandler = eventHandler;
    }

    /// Submits a human review decision for a paused execution.
    ///
    /// After forwarding the decision to the server, re-subscribes to SSE
    /// to receive post-resume events (completion, error, or re-pause).
    ///
    /// @param executionId the execution awaiting review
    /// @param request     the review decision
    /// @return 200 OK with submission confirmation, 400 when the body is incomplete, or the
    ///     server's own status and error message when it refuses the resume
    @PostMapping("/review/{executionId}")
    public ResponseEntity<Map<String, String>> submitReview(
            @PathVariable String executionId,
            @RequestBody ReviewRequest request) {

        String problem = validate(request);
        if (problem != null) {
            LOG.warn("Rejected review submission for executionId={}: {}", executionId, problem);
            return ResponseEntity.badRequest()
                    .body(Map.of("executionId", executionId, "error", problem));
        }

        LOG.info(
                "Human review submitted: executionId={}, approved={}, correlationId={}",
                executionId,
                request.approved(),
                request.correlationId());

        String decision = request.approved() ? "approve" : "reject";
        Map<String, Object> contextEdits =
                request.modifications() != null ? request.modifications() : Map.of();

        // Re-subscribe to SSE only once the body is known to be usable, so a malformed
        // request does not leave a subscription open for a resume that never happens.
        eventHandler.resubscribe(executionId);

        try {
            hensuClient.resume(executionId, request.correlationId(), decision, contextEdits);
        } catch (RestClientResponseException e) {
            String error = serverError(e);
            LOG.warn(
                    "Server refused review for executionId={}: {} {}",
                    executionId,
                    e.getStatusCode().value(),
                    error);
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("executionId", executionId, "error", error));
        }

        return ResponseEntity.ok(Map.of(
                "executionId", executionId,
                "status", "submitted",
                "approved", String.valueOf(request.approved())));
    }

    /// Extracts the server's error message from a refused resume response.
    ///
    /// The server answers refusals with `{"error": "...", "status": ...}`; the `error` value is
    /// what the operator needs to see. Falls back to the raw body, then the status text, when
    /// the body is not in that shape.
    ///
    /// @param e the response the server refused the resume with, not null
    /// @return a human-readable reason for the refusal, never null or blank
    private static String serverError(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        try {
            JsonNode error = MAPPER.readTree(body).path("error");
            if (error.isTextual() && !error.asText().isBlank()) {
                return error.asText();
            }
        } catch (Exception ignored) {
            // Not JSON — fall through to the raw body.
        }
        return body.isBlank() ? e.getStatusText() : body;
    }

    /// Describes what is wrong with a review submission, or null when it is usable.
    ///
    /// Both fields are required and neither has a safe default. An absent `approved` used to
    /// deserialize into `false` and silently reject the workflow; an absent `correlationId`
    /// travelled on to the server and came back as a 500.
    ///
    /// @param request the submitted body, not null
    /// @return a message naming the missing field, or null when the request is complete
    private String validate(ReviewRequest request) {
        if (request.approved() == null) {
            return "'approved' is required and must be true or false";
        }
        if (request.correlationId() == null || request.correlationId().isBlank()) {
            return "'correlationId' is required — use the value from the execution.paused event";
        }
        return null;
    }

    /// Request body for a human review decision.
    ///
    /// @param correlationId opaque identifier from the `execution.paused` SSE event;
    ///                      must match the server's expected correlation, required
    /// @param approved      true to approve the workflow, false to reject; required, and boxed
    ///                      so that an absent field is distinguishable from an explicit `false`
    /// @param modifications optional context variable overrides applied before resuming;
    ///                      values here are merged into the workflow context
    public record ReviewRequest(
            String correlationId,
            Boolean approved,
            Map<String, Object> modifications) {}
}
