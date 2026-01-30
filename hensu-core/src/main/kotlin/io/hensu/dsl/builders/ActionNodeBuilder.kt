package io.hensu.dsl.builders

import io.hensu.core.execution.action.Action
import io.hensu.core.workflow.node.ActionNode

/**
 * DSL builder for action nodes.
 *
 * Action nodes execute commands, notifications, or HTTP calls at any point in the workflow, then
 * transition to the next node. Unlike end nodes, action nodes continue workflow execution.
 *
 * Example:
 * ```kotlin
 * action("commit") {
 *     execute("git-commit")
 *     onSuccess goto "pr"
 * }
 *
 * action("notify-team") {
 *     notify("Build completed: {result}")
 *     http("https://webhook.example.com", "build-done")
 *     onSuccess goto "deploy"
 *     onFailure retry 2 otherwise "error"
 * }
 * ```
 *
 * @property id unique identifier for this action node
 * @see ActionNode for the compiled node type
 * @see Action for available action types
 */
@WorkflowDsl
class ActionNodeBuilder(private val id: String) : BaseNodeBuilder, TransitionMarkers {
    private val actions = mutableListOf<Action>()
    private val transitionBuilder = TransitionBuilder()

    /**
     * Adds a notification action.
     *
     * @param message notification message, supports `{variable}` template syntax
     * @param channel notification channel identifier. Default: "default"
     */
    fun notify(message: String, channel: String = "default") {
        actions.add(Action.Notify(message, channel))
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

    /**
     * Adds an HTTP call action with minimal configuration.
     *
     * @param endpoint target URL for the HTTP request
     * @param commandId command identifier for this action
     */
    fun http(endpoint: String, commandId: String) {
        actions.add(Action.HttpCall(endpoint, commandId))
    }

    /**
     * Adds an HTTP call action with full configuration.
     *
     * Example:
     * ```kotlin
     * http {
     *     endpoint = "https://api.example.com/webhook"
     *     method = "POST"
     *     commandId = "workflow-complete"
     *     headers = mapOf("Authorization" to "Bearer token")
     *     body = """{"status": "success"}"""
     *     timeout = 30000
     * }
     * ```
     *
     * @param block configuration block for HTTP call properties
     */
    fun http(block: HttpCallBuilder.() -> Unit) {
        val builder = HttpCallBuilder()
        builder.apply(block)
        actions.add(builder.build())
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

/**
 * DSL builder for HTTP call action configuration.
 *
 * Configures HTTP requests to be executed as workflow actions, typically for webhook notifications
 * or API integrations.
 *
 * @see ActionNodeBuilder.http for usage
 */
@WorkflowDsl
class HttpCallBuilder {
    /** Target URL for the HTTP request. Required. */
    var endpoint: String = ""

    /** HTTP method to use. Default: "POST". */
    var method: String = "POST"

    /** Command identifier for this action. Required. */
    var commandId: String = ""

    /** HTTP headers to include in the request. */
    var headers: Map<String, String> = emptyMap()

    /** Request body content, may be null for requests without body. */
    var body: String? = null

    /** Request timeout in milliseconds. Default: 30000 (30 seconds). */
    var timeout: Long = 30000

    /**
     * Builds the [Action.HttpCall] from this builder.
     *
     * @return compiled HTTP call action
     * @throws IllegalArgumentException if [endpoint] or [commandId] is blank
     */
    fun build(): Action.HttpCall {
        require(endpoint.isNotBlank()) { "HTTP endpoint is required" }
        require(commandId.isNotBlank()) { "HTTP commandId is required" }
        return Action.HttpCall(endpoint, method, commandId, headers, body, timeout)
    }
}
