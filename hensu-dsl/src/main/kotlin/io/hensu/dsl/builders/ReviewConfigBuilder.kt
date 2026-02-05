package io.hensu.dsl.builders

import io.hensu.core.review.ReviewConfig
import io.hensu.core.review.ReviewMode

/**
 * DSL builder for human review configuration on workflow nodes.
 *
 * Configures how human review is handled at node boundaries, including whether review is required,
 * and what actions the reviewer can take.
 *
 * Example:
 * ```kotlin
 * review {
 *     mode = ReviewMode.REQUIRED
 *     allowBacktrack = true
 *     allowEdit = true
 * }
 * ```
 *
 * @see ReviewMode for available review modes
 * @see ReviewConfig for the compiled configuration
 */
@WorkflowDsl
class ReviewConfigBuilder {
    /** Review mode determining when review is requested. Default: [ReviewMode.REQUIRED]. */
    var mode: ReviewMode = ReviewMode.REQUIRED

    /** Whether the reviewer can request backtracking to a previous node. Default: false. */
    var allowBacktrack: Boolean = false

    /** Whether the reviewer can edit the output before proceeding. Default: false. */
    var allowEdit: Boolean = false

    /**
     * Builds the immutable [ReviewConfig] from this builder.
     *
     * @return compiled review configuration, never null
     */
    fun build(): ReviewConfig = ReviewConfig(mode, allowBacktrack, allowEdit)
}
