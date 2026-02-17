package io.hensu.dsl.builders

import io.hensu.core.execution.action.Action
import io.hensu.core.workflow.node.ActionNode

/**
 * DSL builder for action nodes.
 *
 * Action nodes execute commands or send data to external systems at any point in the workflow, then
 * transition to the next node. Unlike end nodes, action nodes continue workflow execution.
 *
 * ### Action Handler Pattern
 * Send actions use registered [io.hensu.core.execution.action.ActionHandler] implementations. All
 * configuration (endpoints, auth, protocols) is encapsulated in handlers.
 *
 * Example:
 * ```kotlin
 * action("commit") {
 *     execute("git-commit")
 *     onSuccess goto "pr"
 * }
 *
 * action("notify-team") {
 *     send("slack", mapOf("message" to "Build completed: {result}"))
 *     send("email", mapOf("to" to "team@example.com", "subject" to "Build done"))
 *     onSuccess goto "deploy"
 *     onFailure retry 2 otherwise "error"
 * }
 *
 * action("trigger-deploy") {
 *     send("github-dispatch", mapOf("event_type" to "deploy", "ref" to "{branch}"))
 *     onSuccess goto "monitor"
 * }
 * ```
 *
 * @property id unique identifier for this action node
 * @see ActionNode for the compiled node type
 * @see Action for available action types
 * @see io.hensu.core.execution.action.ActionHandler for action handler implementation
 */
@WorkflowDsl
class ActionNodeBuilder(private val id: String) : BaseNodeBuilder, TransitionMarkers {
    private val actions = mutableListOf<Action>()
    private val transitionBuilder = TransitionBuilder()

    /**
     * Adds a send action by handler ID.
     *
     * The handler ID must match a registered [io.hensu.core.execution.action.ActionHandler]. All
     * configuration (endpoint, auth, protocol) is encapsulated in the handler.
     *
     * Example:
     * ```kotlin
     * send("slack")
     * send("webhook")
     * ```
     *
     * @param handlerId identifier of the registered action handler
     */
    fun send(handlerId: String) {
        actions.add(Action.Send(handlerId))
    }

    /**
     * Adds a send action with payload data.
     *
     * The payload is passed to the [io.hensu.core.execution.action.ActionHandler] which decides how
     * to use it (e.g., as HTTP body, message content, event data).
     *
     * Payload values support `{variable}` template syntax, resolved from workflow context
     * including:
     * - Initial context passed at workflow start
     * - Output parameters from previous agent steps via `outputParams`
     * - Values stored by previous nodes
     *
     * Example:
     * ```kotlin
     * send("slack", mapOf("message" to "Build status: {status}"))
     * send("github-dispatch", mapOf(
     *     "repo" to "myrepo",
     *     "event_type" to "deploy",
     *     "client_payload" to mapOf("env" to "{environment}")
     * ))
     * ```
     *
     * @param handlerId identifier of the registered action handler
     * @param payload action-specific data passed to the handler
     */
    fun send(handlerId: String, payload: Map<String, Any>) {
        actions.add(Action.Send(handlerId, payload))
    }

    /**
     * Adds a command execution action by ID.
     *
     * The command ID must be defined in `commands.yaml` in the working directory.
     *
     * @param commandId identifier of the command to execute
     */
    fun execute(commandId: String) {
        actions.add(Action.Execute(commandId))
    }

    /** Define transition on success. Usage: `onSuccess goto "next_node"` */
    infix fun onSuccess.goto(targetNode: String) {
        transitionBuilder.addSuccessTransition(targetNode)
    }

    /** Define transition on failure with retry. Usage: `onFailure retry 3 otherwise "fallback"` */
    infix fun onFailure.retry(count: Int): RetryBuilder =
        transitionBuilder.createRetryBuilder(count)

    /**
     * Builds the immutable [ActionNode] from this builder.
     *
     * @return compiled action node
     * @throws IllegalStateException if no actions are defined
     */
    override fun build(): ActionNode {
        require(actions.isNotEmpty()) { "Action node '$id' must have at least one action" }

        return ActionNode.builder()
            .id(id)
            .actions(actions.toList())
            .transitionRules(transitionBuilder.build())
            .build()
    }
}
