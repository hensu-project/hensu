package io.hensu.core.review;

/// Review trigger mode for human review checkpoints.
///
/// Controls when the workflow executor requests human review for a node.
///
/// @see ReviewConfig for review configuration
/// @see ReviewHandler for review implementation
public enum ReviewMode {

    /// Always request human review, regardless of execution result.
    REQUIRED,

    /// Request review only on failure; skip on success.
    OPTIONAL,

    /// Never request human review; always auto-approve.
    DISABLED
}
