package io.hensu.core.execution.action;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/// Actions that can be executed when an action node is reached.
///
/// ### Supported action types
///
/// {@link Send} - Send data to a registered handler (HTTP, messaging, events, etc.)
/// {@link Execute} - Execute a local command from the command registry
public abstract sealed class Action {

    private Action() {}

    /// Send action - invokes a registered {@link ActionHandler} by handler ID.
    ///
    /// ### Use Cases
    /// - HTTP calls (REST APIs, webhooks)
    /// - Messaging (Slack, Teams, email)
    /// - Event publishing (Kafka, RabbitMQ)
    /// - Any external integration
    ///
    /// ### Security Model
    /// All configuration (endpoints, auth, headers) is encapsulated in user-implemented
    /// {@link ActionHandler} instances. Workflows only reference handlers by ID,
    /// keeping sensitive data out of DSL files.
    ///
    /// ### Usage
    /// {@snippet lang=kotlin :
    /// action("notify") {
    ///     send("slack")  // simple call
    ///     send("github-api", mapOf("repo" to "hensu", "action" to "dispatch"))  // with payload
    /// }
    /// }
    ///
    /// @see ActionHandler
    /// @see ActionExecutor#registerHandler(ActionHandler)
    public static final class Send extends Action {
        private final String handlerId;
        private final Map<String, Object> payload;
        private final boolean rawPayload;

        /// Creates a send action with payload and raw-payload flag.
        ///
        /// @param handlerId the registered action handler ID, not null or blank
        /// @param payload action-specific data passed to the handler, not null
        /// @param rawPayload `true` for agent-originated payloads that must NOT be
        ///        template-resolved; `false` for DSL-authored payloads
        public Send(String handlerId, Map<String, Object> payload, boolean rawPayload) {
            this.handlerId = handlerId;
            this.payload =
                    payload != null
                            ? Collections.unmodifiableMap(new HashMap<>(payload))
                            : Map.of();
            this.rawPayload = rawPayload;
        }

        /// Returns the action handler ID.
        ///
        /// @return the handler ID, never null
        public String getHandlerId() {
            return handlerId;
        }

        /// Returns the payload data for this action.
        ///
        /// @return immutable payload map, never null (may be empty)
        public Map<String, Object> getPayload() {
            return payload;
        }

        /// Returns whether this payload is agent-originated and must skip template resolution.
        ///
        /// @return `true` if the payload should not be template-resolved
        public boolean isRawPayload() {
            return rawPayload;
        }
    }

    /// Execute action - runs a command by ID from the command registry.
    ///
    /// ### Security
    /// Commands are defined in a separate `commands.yaml` file, not in the workflow DSL.
    ///
    /// This prevents command injection attacks. The actual command is resolved from CommandRegistry
    /// at execution time.
    public static final class Execute extends Action {
        private final String commandId;

        public Execute(String commandId) {
            this.commandId = commandId;
        }

        public String getCommandId() {
            return commandId;
        }
    }

    /// Parse action from string format.
    ///
    /// ### Supported Formats
    /// - `"send:handlerId"` — Triggers a **Send** action via registered handler.
    /// - `"exec:command"` — Triggers a **Local command execution** action.
    ///
    public static Action fromString(String str) {
        if (str.startsWith("send:")) {
            String handlerId = str.substring("send:".length()).trim();
            return new Send(handlerId, Map.of(), false);
        } else if (str.startsWith("exec:")) {
            return new Execute(str.substring("exec:".length()).trim());
        } else {
            return new Send("default", Map.of("message", str), false);
        }
    }
}
