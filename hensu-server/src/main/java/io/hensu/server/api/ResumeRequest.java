package io.hensu.server.api;

import io.hensu.core.resume.ResumeInput;
import io.hensu.core.review.ReviewDecision;
import java.util.Map;

/// Request body for resuming a paused execution.
///
/// Supports three resume modes:
/// - **Review decision**: set `decision` to `"approve"`, `"reject"`, or `"backtrack"`
/// - **Context edits**: set `contextEdits` without a `decision`
/// - **Plain resume**: empty body (recovery restart)
///
/// @param correlationId correlation id from the paused execution phase, required when
///                      `decision` is set
/// @param decision      review decision type: `"approve"`, `"reject"`, or `"backtrack"`,
///                      may be null
/// @param reason        explanation for reject or backtrack, may be null
/// @param targetStep    node ID to backtrack to, required when `decision` is `"backtrack"`
/// @param contextEdits  context variable overrides, may be null
public record ResumeRequest(
        String correlationId,
        String decision,
        String reason,
        String targetStep,
        Map<String, Object> contextEdits) {

    /// Maps this request to a core {@link ResumeInput}.
    public ResumeInput toResumeInput() {
        if (decision == null) {
            if (contextEdits != null && !contextEdits.isEmpty()) {
                return new ResumeInput.ApplyContextEdits(contextEdits);
            }
            return ResumeInput.NONE;
        }
        return switch (decision) {
            case "approve" ->
                    new ResumeInput.ApplyReview(
                            correlationId, new ReviewDecision.Approve(contextEdits));
            case "reject" ->
                    new ResumeInput.ApplyReview(
                            correlationId,
                            new ReviewDecision.Reject(
                                    reason != null ? reason : "Rejected via API"));
            case "backtrack" -> {
                if (targetStep == null || targetStep.isBlank()) {
                    throw new IllegalArgumentException(
                            "targetStep is required for backtrack decisions");
                }
                yield new ResumeInput.ApplyReview(
                        correlationId,
                        new ReviewDecision.Backtrack(
                                targetStep,
                                contextEdits,
                                reason != null ? reason : "Backtrack via API"));
            }
            default ->
                    throw new IllegalArgumentException(
                            "Invalid decision: "
                                    + decision
                                    + ". Must be approve, reject, or backtrack.");
        };
    }
}
