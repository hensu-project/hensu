package io.hensu.integrations.springclient.review;

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
@RestController
@RequestMapping("/demo")
public class ReviewController {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewController.class);

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
    /// @return 200 OK with submission confirmation
    @PostMapping("/review/{executionId}")
    public ResponseEntity<Map<String, String>> submitReview(
            @PathVariable String executionId,
            @RequestBody ReviewRequest request) {

        LOG.info(
                "Human review submitted: executionId={}, approved={}, correlationId={}",
                executionId,
                request.approved(),
                request.correlationId());

        String decision = request.approved() ? "approve" : "reject";
        Map<String, Object> contextEdits =
                request.modifications() != null ? request.modifications() : Map.of();

        // Re-subscribe to SSE before resuming so the subscription is ready
        // when the server starts emitting post-resume events.
        eventHandler.resubscribe(executionId);

        hensuClient.resume(executionId, request.correlationId(), decision, contextEdits);

        return ResponseEntity.ok(Map.of(
                "executionId", executionId,
                "status", "submitted",
                "approved", String.valueOf(request.approved())));
    }

    /// Request body for a human review decision.
    ///
    /// @param correlationId opaque identifier from the `execution.paused` SSE event;
    ///                      must match the server's expected correlation
    /// @param approved      true to approve the workflow, false to reject
    /// @param modifications optional context variable overrides applied before resuming;
    ///                      values here are merged into the workflow context
    public record ReviewRequest(
            String correlationId,
            boolean approved,
            Map<String, Object> modifications) {}
}
