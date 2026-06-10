package io.hensu.server.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/// Connection status of a specific MCP client.
///
/// When the client is not connected, {@code connectedDurationMs} is {@code null}
/// and excluded from the JSON response.
///
/// @param clientId the client identifier, never null
/// @param connected whether the client has an active SSE connection
/// @param connectedDurationMs milliseconds since connection, null when disconnected
@RegisterForReflection
record ClientStatusResponse(
        String clientId,
        boolean connected,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long connectedDurationMs) {

    static ClientStatusResponse connected(String clientId, long durationMs) {
        return new ClientStatusResponse(clientId, true, durationMs);
    }

    static ClientStatusResponse disconnected(String clientId) {
        return new ClientStatusResponse(clientId, false, null);
    }
}
