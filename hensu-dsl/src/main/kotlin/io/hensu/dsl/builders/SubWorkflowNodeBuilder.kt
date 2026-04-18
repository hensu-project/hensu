package io.hensu.dsl.builders

import io.hensu.core.execution.EngineVariables
import io.hensu.core.workflow.node.SubWorkflowNode

/**
 * DSL builder for sub-workflow delegation nodes.
 *
 * A sub-workflow node delegates execution to a nested workflow identified by [target]. State
 * variables named in [imports] are copied from parent to child under the same name; variables named
 * in [writes] are mirrored back from child to parent under the same name. Same-name discipline: the
 * child's state schema is the contract; parents adapt.
 *
 * Example:
 * ```kotlin
 * subWorkflow("delegate_summary") {
 *     target        = "sub-summarizer"
 *     targetVersion = "1.0.0"   // optional, forward-compat with workflow versioning
 *     imports("draft")
 *     writes("tl_dr")
 *     onSuccess goto "publish"
 *     onFailure goto "publish"
 * }
 * ```
 *
 * @property id unique node identifier within the parent workflow
 * @see io.hensu.core.workflow.node.SubWorkflowNode for the compiled node
 */
@WorkflowDsl
class SubWorkflowNodeBuilder(private val id: String) : BaseNodeBuilder, TransitionMarkers {
    /** Required: id of the child workflow to invoke. */
    var target: String? = null

    /**
     * Optional pinned version of the child workflow.
     *
     * Stored on the compiled [SubWorkflowNode] and round-tripped through serialization for forward
     * compatibility with the workflow-versioning feature. Not enforced at runtime yet.
     */
    var targetVersion: String? = null

    private var imports: List<String> = emptyList()
    private var writes: List<String> = emptyList()
    private val transitionBuilder = TransitionBuilder()

    /**
     * Declares parent state variables to copy into the child workflow under the same name.
     *
     * Each name must be declared in the parent workflow's `state {}` block, must not be an engine
     * variable, and must not overlap with [writes].
     */
    fun imports(vararg names: String) {
        for (name in names) {
            require(name.isNotBlank()) { "subWorkflow('$id'): imports name must not be blank" }
            require(!EngineVariables.isEngineVar(name)) {
                "subWorkflow('$id'): imports field '$name' is a reserved engine variable."
            }
        }
        require(names.toSet().size == names.size) {
            "subWorkflow('$id'): duplicate names in imports(${names.joinToString()})"
        }
        imports = names.toList()
    }

    /**
     * Declares state variables the child workflow writes, mirrored back into the parent state.
     *
     * Each name must be declared in the parent's `state {}` block, must not be an engine variable,
     * and must not overlap with [imports].
     */
    fun writes(vararg names: String) {
        for (name in names) {
            require(name.isNotBlank()) { "subWorkflow('$id'): writes name must not be blank" }
            require(!EngineVariables.isEngineVar(name)) {
                "subWorkflow('$id'): writes field '$name' is a reserved engine variable."
            }
        }
        require(names.toSet().size == names.size) {
            "subWorkflow('$id'): duplicate names in writes(${names.joinToString()})"
        }
        writes = names.toList()
    }

    /** Define transition on success. Usage: `onSuccess goto "next_node"` */
    infix fun onSuccess.goto(targetNode: String) {
        transitionBuilder.addSuccessTransition(targetNode)
    }

    /** Define transition on failure. Usage: `onFailure goto "error_node"` */
    infix fun onFailure.goto(targetNode: String) {
        transitionBuilder.addFailureTransition(targetNode)
    }

    override fun build(): SubWorkflowNode {
        val resolvedTarget =
            target ?: throw IllegalStateException("subWorkflow('$id'): target is required")
        require(resolvedTarget.isNotBlank()) { "subWorkflow('$id'): target must not be blank" }
        val overlap = imports.toSet().intersect(writes.toSet())
        require(overlap.isEmpty()) {
            "subWorkflow('$id'): imports and writes must not overlap: $overlap"
        }
        // Same-name discipline: identity maps both directions.
        val inputMapping = imports.associateWith { it }
        val outputMapping = writes.associateWith { it }

        return SubWorkflowNode.builder()
            .id(id)
            .workflowId(resolvedTarget)
            .targetVersion(targetVersion)
            .inputMapping(inputMapping)
            .outputMapping(outputMapping)
            .transitionRules(transitionBuilder.build())
            .build()
    }
}
