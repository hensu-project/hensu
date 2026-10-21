package io.hensu.server.mcp;

import java.util.Objects;

/// CDI event fired when a tenant's MCP session begins or ends on this node.
///
/// {@link McpSessionManager} fires it when a session registers – including a
/// session that supersedes an earlier one – and when the session that is
/// current for a client terminates. A displaced session's termination fires
/// nothing, because the session that replaced it is still live.
///
/// The event exists so that state derived from a session can follow the
/// session's lifetime without the session manager depending on its consumers:
/// {@link McpToolDiscovery} observes it to drop the client's cached catalog,
/// which a direct reference would make into a constructor cycle
/// (`McpToolDiscovery → McpConnections → McpSessionManager`).
///
/// Observers are notified synchronously on the thread that changed the
/// session, which is usually the Vert.x event loop. An observer must not block.
///
/// @param clientId the client whose session changed, not null
/// @see McpSessionManager#createSession(String) for when the event fires
/// @see McpToolDiscovery for the observer that invalidates the tool catalog
public record McpSessionChanged(String clientId) {

    /// Validates the client id.
    ///
    /// @throws NullPointerException if `clientId` is null
    public McpSessionChanged {
        Objects.requireNonNull(clientId, "clientId must not be null");
    }
}
