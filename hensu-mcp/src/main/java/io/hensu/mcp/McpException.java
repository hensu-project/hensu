package io.hensu.mcp;

import java.io.Serial;
import java.util.Objects;

/// Exception thrown when MCP operations fail.
///
/// Wraps errors from MCP protocol communication including:
/// - Connection failures
/// - Tool invocation errors
/// - Protocol errors
/// - Timeout errors
///
/// ### Whether The Server Can Have Acted
/// Every failure also answers one question a caller that must not run a call
/// twice depends on: could the request have reached the server? {@link #delivery()}
/// is {@link Delivery#NOT_SENT} only where that is certain – nothing was written,
/// or the server refused the request at the door – and
/// {@link Delivery#MAYBE_DELIVERED} everywhere else. A failure that cannot tell
/// is classified as delivered, because reporting a call that ran as one that did
/// not is the error a retry turns into a second execution.
public class McpException extends RuntimeException {

    @Serial private static final long serialVersionUID = 5486229795475543465L;

    /// Whether a failed request can have reached the server.
    ///
    /// ### Permitted Values
    /// - {@link #NOT_SENT} – the server certainly did not act on the request
    /// - {@link #MAYBE_DELIVERED} – the server may have acted on it, fully or in part
    public enum Delivery {

        /// Nothing reached the server, or it refused the request before acting.
        ///
        /// A refused connection, a connect timeout, a refused `307` or `308`
        /// redirect, a `401` or `403`, an argument with no wire form and a
        /// process found dead before the write all land here.
        NOT_SENT,

        /// The request may have reached the server and been acted on.
        ///
        /// Any failure after sending began: a dropped connection, a timeout, a
        /// truncated answer, an error status or a JSON-RPC error. The transport
        /// cannot count the bytes that left, so this is the only safe answer.
        MAYBE_DELIVERED
    }

    private final Delivery delivery;

    /// Creates an MCP exception with a message, classified as possibly delivered.
    ///
    /// @param message the error message
    public McpException(String message) {
        this(message, Delivery.MAYBE_DELIVERED);
    }

    /// Creates an MCP exception with a message and a delivery classification.
    ///
    /// @param message the error message
    /// @param delivery whether the request can have reached the server, not null
    /// @throws NullPointerException if delivery is null
    public McpException(String message, Delivery delivery) {
        super(message);
        this.delivery = Objects.requireNonNull(delivery, "delivery must not be null");
    }

    /// Creates an MCP exception with a cause, classified as possibly delivered.
    ///
    /// @param message the error message
    /// @param cause the underlying cause
    public McpException(String message, Throwable cause) {
        this(message, cause, Delivery.MAYBE_DELIVERED);
    }

    /// Creates an MCP exception with a cause and a delivery classification.
    ///
    /// @param message the error message
    /// @param cause the underlying cause
    /// @param delivery whether the request can have reached the server, not null
    /// @throws NullPointerException if delivery is null
    public McpException(String message, Throwable cause, Delivery delivery) {
        super(message, cause);
        this.delivery = Objects.requireNonNull(delivery, "delivery must not be null");
    }

    /// Returns whether the failed request can have reached the server.
    ///
    /// @return the classification, never null
    public Delivery delivery() {
        return delivery;
    }

    /// Builds the failure of a request its caller cancelled.
    ///
    /// @param serverName the declared name of the server, not null
    /// @param method the JSON-RPC method that was cancelled, not null
    /// @param delivery whether anything of the request had been sent, not null
    /// @return the failure, never null
    static McpException cancelled(String serverName, String method, Delivery delivery) {
        return new McpException(
                "MCP request '"
                        + method
                        + "' to server '"
                        + serverName
                        + "' was cancelled by the caller",
                delivery);
    }

    /// Builds the failure of a request whose budget ran out.
    ///
    /// @param serverName the declared name of the server, not null
    /// @param method what was being waited for, not null
    /// @param budgetMs the budget that ran out, in milliseconds
    /// @param delivery whether anything of the request had been sent, not null
    /// @return the failure, never null
    static McpException timedOut(
            String serverName, String method, long budgetMs, Delivery delivery) {
        return new McpException(
                "MCP request '"
                        + method
                        + "' to server '"
                        + serverName
                        + "' timed out after "
                        + budgetMs
                        + "ms",
                delivery);
    }

    /// Builds the failure of a request that ended for any other reason.
    ///
    /// Classified as possibly delivered: the cause names no point the request
    /// can be shown not to have passed.
    ///
    /// @param serverName the declared name of the server, not null
    /// @param method the JSON-RPC method that failed, not null
    /// @param cause the underlying cause, may be null
    /// @return the failure, never null
    static McpException failed(String serverName, String method, Throwable cause) {
        return new McpException(
                "MCP request '"
                        + method
                        + "' to server '"
                        + serverName
                        + "' failed: "
                        + (cause != null ? cause.getMessage() : "unknown cause"),
                cause);
    }
}
