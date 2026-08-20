package io.hensu.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.hensu.core.resume.ResumeInput;
import io.hensu.core.review.ReviewDecision;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/// Request body for resuming a paused execution.
///
/// Supports three resume modes:
/// - **Review decision**: set `decision` to `"approve"`, `"reject"`, or `"backtrack"`
/// - **Context edits**: set `contextEdits` without a `decision`
/// - **Plain resume**: empty body (recovery restart)
///
/// ### Unknown fields are rejected
/// A body shaped like a review decision but spelled differently — `{"approved": true}` instead
/// of `{"decision": "approve", "correlationId": …}` — must not deserialize into an empty
/// request that degrades to {@link ResumeInput#NONE}. For an execution paused at a review gate
/// that degradation produced an endless re-prompt: every malformed call returned 200 with a
/// fresh correlation id and no decision applied.
///
/// Unknown properties are collected into `unknownFields` via `@JsonAnySetter` and rejected in
/// {@link #toResumeInput()}. Collecting them rather than relying on
/// `@JsonIgnoreProperties(ignoreUnknown = false)` is deliberate: that is the annotation's
/// default value and it does not re-enable failure once the server-wide
/// `fail-on-unknown-properties=false` has disabled it. This way the rejection is a property of
/// the DTO, the error message can name the offending fields, and the server-wide setting stays
/// untouched.
///
/// @param correlationId correlation id from the paused execution phase, required when
///                      `decision` is set
/// @param decision      review decision type: `"approve"`, `"reject"`, or `"backtrack"`,
///                      may be null
/// @param reason        explanation for reject or backtrack, may be null
/// @param targetStep    node ID to backtrack to, required when `decision` is `"backtrack"`
/// @param contextEdits  context variable overrides, may be null
/// @param unknownFields properties in the body that match no component above; populated by
///                      Jackson, always empty for programmatically built requests
public record ResumeRequest(
        String correlationId,
        String decision,
        String reason,
        String targetStep,
        Map<String, Object> contextEdits,
        @JsonAnySetter Map<String, Object> unknownFields) {

    public ResumeRequest {
        // Not Map.copyOf: a body like {"approved": null} arrives as a null map value, which
        // Map.copyOf rejects with an NPE before the field can be named in the rejection.
        unknownFields =
                unknownFields == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new HashMap<>(unknownFields));
    }

    /// Creates a request with no unknown fields — the form used by callers that build a
    /// request directly rather than deserializing one.
    ///
    /// @param correlationId correlation id from the paused execution phase, may be null
    /// @param decision      review decision type, may be null
    /// @param reason        explanation for reject or backtrack, may be null
    /// @param targetStep    node ID to backtrack to, may be null
    /// @param contextEdits  context variable overrides, may be null
    public ResumeRequest(
            String correlationId,
            String decision,
            String reason,
            String targetStep,
            Map<String, Object> contextEdits) {
        this(correlationId, decision, reason, targetStep, contextEdits, Map.of());
    }

    /// Maps this request to a core {@link ResumeInput}.
    ///
    /// @return the resume input to hand to the executor, never null
    /// @throws IllegalArgumentException if the body carried unrecognized fields, if `decision`
    ///     is unrecognized, if `correlationId` is missing for a decision, or if `targetStep` is
    ///     missing for a backtrack
    public ResumeInput toResumeInput() {
        if (!unknownFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unrecognized field(s) in resume request: "
                            + String.join(", ", unknownFields.keySet())
                            + ". Expected correlationId, decision, reason, targetStep,"
                            + " contextEdits.");
        }
        if (decision == null) {
            if (contextEdits != null && !contextEdits.isEmpty()) {
                return new ResumeInput.ApplyContextEdits(contextEdits);
            }
            return ResumeInput.NONE;
        }
        // Without a correlation id the decision cannot be matched to the pause it answers,
        // and the core validation would report a mismatch against 'null'.
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException(
                    "correlationId is required when a review decision is submitted");
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
