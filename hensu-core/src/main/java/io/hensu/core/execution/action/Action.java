package io.hensu.core.execution.action;

import java.util.Map;

/// Actions that can be executed when an action node is reached.
///
/// ### Supported action types
///
/// {@link Notify} - Send a notification message
/// {@link Execute} - Execute a local command
/// {@link HttpCall} - Make an HTTP call (REST, webhooks, etc.)
public abstract sealed class Action {

    private Action() {}

    /// Notification action - sends a message to configured channels.
    public static final class Notify extends Action {
        private final String message;
        private final String channel;

        public Notify(String message) {
            this(message, "default");
        }

        public Notify(String message, String channel) {
            this.message = message;
            this.channel = channel;
        }

        public String getMessage() {
            return message;
        }

        public String getChannel() {
            return channel;
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

    /// HTTP call action - makes an HTTP request to an endpoint.
    ///
    /// Used for triggering external systems, CI/CD pipelines, webhooks, etc. Supports any HTTP
    /// method (GET, POST, PUT, DELETE, etc.).
    public static final class HttpCall extends Action {
        private final String endpoint;
        private final String method;
        private final String commandId;
        private final Map<String, String> headers;
        private final String body;
        private final long timeoutMs;

        public HttpCall(String endpoint, String commandId) {
            this(endpoint, "POST", commandId, Map.of(), null, 30000);
        }

        public HttpCall(
                String endpoint,
                String method,
                String commandId,
                Map<String, String> headers,
                String body,
                long timeoutMs) {
            this.endpoint = endpoint;
            this.method = method;
            this.commandId = commandId;
            this.headers = headers != null ? Map.copyOf(headers) : Map.of();
            this.body = body;
            this.timeoutMs = timeoutMs;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public String getMethod() {
            return method;
        }

        public String getCommandId() {
            return commandId;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public String getBody() {
            return body;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }
    }

    /// Parse action from string format.
    ///
    /// ### Supported Formats
    /// - `"notify:message"` — Triggers a **Notification** action.
    /// - `"exec:command"` — Triggers a **Local command execution** action.
    /// - `"http:endpoint:commandId"` — Triggers a **Remote HTTP call** action.
    ///
    public static Action fromString(String str) {
        if (str.startsWith("notify:")) {
            return new Notify(str.substring("notify:".length()).trim());
        } else if (str.startsWith("exec:")) {
            return new Execute(str.substring("exec:".length()).trim());
        } else if (str.startsWith("http:")) {
            String rest = str.substring("http:".length()).trim();
            String[] parts = rest.split(":", 2);
            if (parts.length == 2) {
                return new HttpCall(parts[0].trim(), parts[1].trim());
            } else {
                return new HttpCall(rest, "default");
            }
        } else {
            return new Notify(str);
        }
    }
}
