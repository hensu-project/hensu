package io.hensu.core.execution.action;

import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import java.util.Map;

/// Interface for handling custom actions in workflows.
///
/// Users implement this interface to define custom action logic with full control
/// over execution behavior. Handlers are registered with {@link ActionExecutor}
/// by handler ID and invoked when an `Action.Send` with matching ID is executed.
///
/// ### Use Cases
/// - HTTP calls (REST APIs, webhooks)
/// - Messaging (Slack, Teams, email)
/// - Event publishing (Kafka, RabbitMQ)
/// - Database operations
/// - File operations
/// - Any external integration
///
/// ### Security Model
/// This pattern keeps sensitive data (API keys, endpoints, credentials) on the user side.
/// Authentication and configuration are encapsulated in the handler implementation,
/// which can load credentials from secure sources.
///
/// ### Example Implementation
/// {@snippet :
/// public class SlackHandler implements ActionHandler {
///     private final String webhookUrl;
///     private final HttpClient client = HttpClient.newHttpClient();
///
///     public SlackHandler(String webhookUrl) {
///         this.webhookUrl = webhookUrl;
///     }
///
///     @Override
///     public String getHandlerId() {
///         return "slack";
///     }
///
///     @Override
///     public ActionResult execute(Map<String, Object> payload, Map<String, Object> context) {
///         String message = payload.getOrDefault("message", "Workflow event").toString();
///         // ... build and send HTTP request
///         return ActionResult.success("Slack notification sent");
///     }
/// }
/// }
///
/// ### Registration
/// {@snippet :
/// actionExecutor.registerHandler(new SlackHandler(webhookUrl));
/// }
///
/// ### DSL Usage
/// {@snippet lang=kotlin :
/// action("notify") {
///     send("slack", mapOf("message" to "Build completed: {status}"))
///     onSuccess goto "next"
/// }
/// }
///
/// @implNote Implementations should be thread-safe if workflows execute actions concurrently.
///
/// @see ActionExecutor#registerHandler(ActionHandler)
/// @see Action.Send
public interface ActionHandler {

    /// Returns the unique handler identifier.
    ///
    /// This ID is used to match handlers with `Action.Send` requests.
    /// Choose descriptive IDs that indicate the target service (e.g., `"slack"`,
    /// `"github-api"`, `"deploy-webhook"`, `"kafka-publish"`).
    ///
    /// @return the handler ID, not null or blank
    String getHandlerId();

    /// Executes the action with the given payload and workflow context.
    ///
    /// The implementation has full control over:
    /// - Communication protocol (HTTP, messaging, events)
    /// - Authentication (API keys, OAuth, mTLS)
    /// - Request body construction from payload
    /// - Response parsing and error handling
    /// - Timeout and retry logic
    ///
    /// ### Payload
    /// The payload contains data from the DSL with template variables already resolved.
    /// For example, `mapOf("message" to "{status}")` becomes `Map.of("message", "SUCCESS")`
    /// where `status` was extracted from a previous agent's output via `outputParams`.
    ///
    /// ### Context
    /// The context contains the full workflow state including:
    /// - Initial context passed at workflow start
    /// - Output parameters extracted from previous agent steps
    /// - Any values stored by previous nodes
    ///
    /// Use context for additional data access beyond what's in the payload.
    ///
    /// @param payload action-specific data from the DSL with templates resolved, never null
    /// @param context full workflow context including agent outputs, not null
    /// @return the result of the action, never null
    ActionResult execute(Map<String, Object> payload, Map<String, Object> context);
}
