package io.hensu.core.review;

/// Configuration for human review checkpoints in workflow execution.
///
/// Controls when and how human review is requested during workflow execution,
/// including whether reviewers can backtrack to previous steps or edit the
/// current state.
///
/// @param mode when to request review (ALWAYS, OPTIONAL, DISABLED)
/// @param allowBacktrack whether reviewer can return to a previous step
/// @param allowEdit whether reviewer can modify state or output
///
/// @see ReviewHandler for review implementation
/// @see ReviewMode for review trigger conditions
/// @see ReviewDecision for possible review outcomes
public record ReviewConfig(ReviewMode mode, boolean allowBacktrack, boolean allowEdit) {

    /// Returns the review trigger mode.
    ///
    /// @return ALWAYS, OPTIONAL, or DISABLED, never null
    public ReviewMode getMode() {
        return mode;
    }

    /// Checks if backtracking is allowed during review.
    ///
    /// @return true if reviewer can return to previous steps
    public boolean isAllowBacktrack() {
        return allowBacktrack;
    }

    /// Checks if state editing is allowed during review.
    ///
    /// @return true if reviewer can modify state or output
    public boolean isAllowEdit() {
        return allowEdit;
    }
}
