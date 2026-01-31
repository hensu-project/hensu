package io.hensu.dsl.builders

/**
 * Marker objects and mixin interfaces for DSL transition syntax.
 *
 * These objects enable infix function syntax for defining workflow transitions:
 * - `start at "node"` - set workflow entry point
 * - `onSuccess goto "node"` - success transition
 * - `onFailure retry N otherwise "node"` - failure with retry
 * - `whenScore greaterThan 80.0 goto "node"` - score-based routing
 * - `onConsensus goto "node"` - parallel consensus reached
 * - `onComplete goto "node"` - fork completion
 */

/** Marker for workflow start point syntax: `start at "nodeId"`. */
object start

/** Marker for success transition syntax: `onSuccess goto "nodeId"`. */
object onSuccess

/** Marker for failure transition syntax: `onFailure retry N otherwise "nodeId"`. */
object onFailure

/** Marker for score condition syntax: `whenScore greaterThan 80.0 goto "nodeId"`. */
object whenScore

/** Marker for consensus reached syntax: `onConsensus goto "nodeId"`. */
object onConsensus

/** Marker for no consensus syntax: `onNoConsensus goto "nodeId"`. */
object onNoConsensus

/** Marker for fork completion syntax: `onComplete goto "nodeId"`. */
object onComplete

/**
 * Mixin interface providing standard transition markers for node builders.
 *
 * Implement this interface to enable `onSuccess` and `onFailure` transition syntax within a node
 * builder scope.
 *
 * @see StandardNodeBuilder
 * @see GenericNodeBuilder
 */
interface TransitionMarkers {
    /** Access to [onSuccess] marker for transition syntax. */
    val onSuccess: onSuccess
        get() = io.hensu.dsl.builders.onSuccess

    /** Access to [onFailure] marker for transition syntax. */
    val onFailure: onFailure
        get() = io.hensu.dsl.builders.onFailure
}

/**
 * Mixin interface providing consensus-specific transition markers.
 *
 * Extends [TransitionMarkers] with markers for parallel node consensus outcomes.
 *
 * @see ParallelNodeBuilder
 */
interface ConsensusMarkers : TransitionMarkers {
    /** Access to [onConsensus] marker for consensus-reached transitions. */
    val onConsensus: onConsensus
        get() = io.hensu.dsl.builders.onConsensus

    /** Access to [onNoConsensus] marker for consensus-failed transitions. */
    val onNoConsensus: onNoConsensus
        get() = io.hensu.dsl.builders.onNoConsensus
}

/**
 * Mixin interface providing fork/join transition markers.
 *
 * Extends [TransitionMarkers] with markers for fork node completion.
 *
 * @see ForkNodeBuilder
 */
interface ForkJoinMarkers : TransitionMarkers {
    /** Access to [onComplete] marker for fork completion transitions. */
    val onComplete: onComplete
        get() = io.hensu.dsl.builders.onComplete
}
