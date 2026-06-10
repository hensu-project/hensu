package io.hensu.server.api;

import io.quarkus.runtime.annotations.RegisterForReflection;

/// MCP gateway health status.
///
/// @param connectedClients number of active SSE connections
/// @param pendingRequests number of JSON-RPC requests awaiting a response
@RegisterForReflection
record GatewayStatusResponse(int connectedClients, int pendingRequests) {}
