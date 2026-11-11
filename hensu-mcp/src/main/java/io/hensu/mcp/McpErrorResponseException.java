package io.hensu.mcp;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Thrown when the server answered a request with a JSON-RPC `error` object.
///
/// An answer is not an outage: the server received the request and said why it
/// would not, or could not, complete it. A caller that relays the outcome
/// somewhere else – the tenant's relay reporting a call back to the Hensu server
/// – has to forward that object as the server wrote it, which a message string
/// cannot carry. {@link #error()} keeps it whole: `code`, `message` and `data`.
///
/// The request reached the server, so {@link #delivery()} is
/// {@link McpException.Delivery#MAYBE_DELIVERED}: an error answer to a
/// `tools/call` does not prove the tool did nothing.
///
/// @implNote **Immutable after construction.** The error object is copied into
///     an unmodifiable map.
/// @see JsonRpc#parseResult for where an error answer is read
public class McpErrorResponseException extends McpException {

    @Serial private static final long serialVersionUID = -6230145962870374520L;

    private final transient Map<String, Object> error;

    /// Creates an exception for an error answer.
    ///
    /// @param message the operator-facing description, not null
    /// @param error the JSON-RPC `error` object as received, not null
    /// @throws NullPointerException if error is null
    public McpErrorResponseException(String message, Map<String, Object> error) {
        super(message, Delivery.MAYBE_DELIVERED);
        this.error =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                Objects.requireNonNull(error, "error must not be null")));
    }

    /// Returns the JSON-RPC error code.
    ///
    /// @return the `code` the server sent, or `-1` when it sent none that is a number
    public int code() {
        return error.get("code") instanceof Number number ? number.intValue() : -1;
    }

    /// Returns the JSON-RPC `error` object as the server wrote it.
    ///
    /// @return the object with `code`, `message` and any `data`, never null
    public Map<String, Object> error() {
        return error;
    }
}
