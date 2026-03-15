package io.hensu.core.review;

import java.util.Map;

/// Sealed interface representing possible outcomes of a human review.
///
/// ### Permitted Subtypes
/// - {@link Approve} - Continue workflow execution, optionally with context edits
/// - {@link Backtrack} - Return to a previous step to retry
/// - {@link Reject} - Abort workflow execution entirely
///
/// @see ReviewHandler for review implementation
/// @see ReviewConfig for review configuration
public sealed interface ReviewDecision {

    /// Approval decision indicating the reviewer accepts the current output.
    ///
    /// @param contextEdits optional context variable overrides to merge, may be null
    record Approve(Map<String, Object> contextEdits) implements ReviewDecision {

        /// Creates an approval with no context modifications.
        public Approve() {
            this(null);
        }

        /// Checks if context edits were provided.
        ///
        /// @return true if edits are non-null and non-empty
        public boolean hasContextEdits() {
            return contextEdits != null && !contextEdits.isEmpty();
        }
    }

    /// Backtrack decision indicating the reviewer wants to retry from a previous step.
    ///
    /// @param targetStep the node ID to return to, not null
    /// @param contextEdits optional context variable overrides to merge before re-execution,
    /// may be null
    /// @param reason explanation for backtracking, not null
    record Backtrack(String targetStep, Map<String, Object> contextEdits, String reason)
            implements ReviewDecision {

        /// Creates a backtrack without context edits.
        ///
        /// @param targetStep the node ID to return to, not null
        /// @param reason explanation for backtracking, not null
        public Backtrack(String targetStep, String reason) {
            this(targetStep, null, reason);
        }

        /// Returns the target step to backtrack to.
        ///
        /// @return node ID, never null
        public String getTargetStep() {
            return targetStep;
        }

        /// Returns the backtrack reason.
        ///
        /// @return explanation text, never null
        public String getReason() {
            return reason;
        }

        /// Checks if context edits were provided.
        ///
        /// @return true if edits are non-null and non-empty
        public boolean hasContextEdits() {
            return contextEdits != null && !contextEdits.isEmpty();
        }
    }

    /// Rejection decision indicating the reviewer wants to abort the workflow.
    ///
    /// @param reason explanation for rejection, not null
    record Reject(String reason) implements ReviewDecision {

        /// Returns the rejection reason.
        ///
        /// @return explanation text, never null
        public String getReason() {
            return reason;
        }
    }
}
