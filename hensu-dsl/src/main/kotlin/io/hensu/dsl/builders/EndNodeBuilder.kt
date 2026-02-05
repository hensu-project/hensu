package io.hensu.dsl.builders

import io.hensu.core.execution.result.ExitStatus
import io.hensu.core.workflow.node.EndNode

/**
 * DSL builder for end nodes.
 *
 * End nodes represent workflow end states with an exit status.
 *
 * Example:
 * ```kotlin
 * end("success", ExitStatus.SUCCESS)
 * end("failure", ExitStatus.FAILURE)
 * ```
 *
 * @property id unique identifier for this end node, not null
 * @property status exit status (SUCCESS, FAILURE, CANCEL)
 * @see EndNode for the compiled node type
 */
@WorkflowDsl
class EndNodeBuilder(private val id: String, private val status: ExitStatus) : BaseNodeBuilder {
    /**
     * Builds the immutable [EndNode] from this builder.
     *
     * @return compiled end node, never null
     */
    override fun build(): EndNode = EndNode.builder().id(id).status(status).build()
}
