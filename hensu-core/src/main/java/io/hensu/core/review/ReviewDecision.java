package io.hensu.core.review;

import io.hensu.core.state.HensuState;

/// Sealed interface representing possible outcomes of a human review.
///
/// ### Permitted Subtypes
/// - {@link Approve} - Continue workflow execution, optionally with edited state
/// - {@link Backtrack} - Return to a previous step to retry
/// - {@link Reject} - Abort workflow execution entirely
///
/// @see ReviewHandler for review implementation
/// @see ReviewConfig for review configuration
public sealed interface ReviewDecision {

    /// Approval decision indicating the reviewer accepts the current output.
    ///
    /// @param editedState optional modified state to use going forward, may be null
    record Approve(HensuState editedState) implements ReviewDecision {

        /// Creates an approval with no state modifications.
        public Approve() {
            this(null);
        }

        /// Returns the edited state, if any.
        ///
        /// @return modified state, or null if unchanged
        public HensuState getEditedState() {
            return editedState;
        }
    }

    /// Backtrack decision indicating the reviewer wants to retry from a previous step.
    ///
    /// @param targetStep the node ID to return to, not null
    /// @param editedState optional modified state to use at target, may be null
    /// @param reason explanation for backtracking, not null
    /// @param editedPrompt optional modified prompt for target node, may be null
    record Backtrack(String targetStep, HensuState editedState, String reason, String editedPrompt)
            implements ReviewDecision {

        /// Creates a backtrack without edited prompt.
        ///
        /// @param targetStep the node ID to return to, not null
        /// @param editedState optional modified state, may be null
        /// @param reason explanation for backtracking, not null
        public Backtrack(String targetStep, HensuState editedState, String reason) {
            this(targetStep, editedState, reason, null);
        }

        /// Returns the target step to backtrack to.
        ///
        /// @return node ID, never null
        public String getTargetStep() {
            return targetStep;
        }

        /// Returns the edited state, if any.
        ///
        /// @return modified state, or null if unchanged
        public HensuState getEditedState() {
            return editedState;
        }

        /// Returns the backtrack reason.
        ///
        /// @return explanation text, never null
        public String getReason() {
            return reason;
        }

        /// Returns the edited prompt for re-execution.
        ///
        /// If null, the original prompt from the workflow definition is used.
        ///
        /// @return modified prompt, or null for original
        public String getEditedPrompt() {
            return editedPrompt;
        }

        /// Checks if an edited prompt was provided.
        ///
        /// @return true if edited prompt is non-null and non-blank
        public boolean hasEditedPrompt() {
            return editedPrompt != null && !editedPrompt.isBlank();
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
