package io.hensu.dsl.builders

import io.hensu.core.workflow.node.Node

/**
 * Base interface for all node builders.
 *
 * Sealed to ensure all node types are known at compile time. Implementations:
 * [StandardNodeBuilder], [EndNodeBuilder], [ParallelNodeBuilder], [GenericNodeBuilder],
 * [ForkNodeBuilder], [JoinNodeBuilder], [ActionNodeBuilder]
 */
sealed interface BaseNodeBuilder {
    fun build(): Node
}
