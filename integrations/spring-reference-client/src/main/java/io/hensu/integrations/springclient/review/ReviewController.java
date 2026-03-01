package io.hensu.integrations.springclient.review;

import io.hensu.integrations.springclient.client.HensuClient;
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
/// `execution.paused` SSE event. An operator (or automated system) calls this
/// endpoint to submit their decision, which is forwarded to the server as a
/// resume request.
///
/// ### Workflow
/// ```
/// 1. SSE: execution.paused → {executionId, nodeId, reason}
/// 2. Operator calls POST /demo/review/{executionId}
/// 3. This controller calls POST /api/v1/executions/{id}/resume on hensu-server
/// 4. Workflow unblocks and continues (or terminates on rejection)
/// ```
///
/// ### Example
/// Approve without modifications:
/// ```
/// curl -X POST http://localhost:8081/demo/review/exec-abc \
///      -H 'Content-Type: application/json' \
///      -d '{"approved": true, "modifications": {}}'
/// ```
///
/// Reject:
/// ```
/// curl -X POST http://localhost:8081/demo/review/exec-abc \
///      -H 'Content-Type: application/json' \
///      -d '{"approved": false, "modifications": {}}'
/// ```
///
/// Approve with context override:
/// ```
/// curl -X POST http://localhost:8081/demo/review/exec-abc \
///      -H 'Content-Type: application/json' \
///      -d '{"approved": true, "modifications": {"approvedLimit": 75000}}'
/// ```
@RestController
@RequestMapping("/demo")
public class ReviewController {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewController.class);

    private final HensuClient hensuClient;

    public ReviewController(HensuClient hensuClient) {
        this.hensuClient = hensuClient;
    }

    /// Submits a human review decision for a paused execution.
    ///
    /// @param executionId the execution awaiting review
    /// @param request     the review decision
    /// @return 200 OK with submission confirmation
    @PostMapping("/review/{executionId}")
    public ResponseEntity<Map<String, String>> submitReview(
            @PathVariable String executionId,
            @RequestBody ReviewRequest request) {

        LOG.info(
                "Human review submitted: executionId={}, approved={}",
                executionId,
                request.approved());

        Map<String, Object> modifications =
                request.modifications() != null ? request.modifications() : Map.of();

        hensuClient.resume(executionId, request.approved(), modifications);

        return ResponseEntity.ok(Map.of(
                "executionId", executionId,
                "status", "submitted",
                "approved", String.valueOf(request.approved())));
    }

    /// Request body for a human review decision.
    ///
    /// @param approved      true to approve the workflow, false to reject
    /// @param modifications optional context variable overrides applied before resuming;
    ///                      values here are merged into the workflow context
    public record ReviewRequest(boolean approved, Map<String, Object> modifications) {}
}
