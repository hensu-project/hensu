package io.hensu.integrations.springclient.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/// Externalized configuration for the Hensu reference client.
///
/// Bound from the `hensu.*` namespace in `application.yml`.
///
/// @param serverUrl base URL of the hensu-server instance (e.g. `http://localhost:8080`)
/// @param token     JWT bearer token; empty string disables auth header injection
/// @param tenantId  tenant identifier sent as `?clientId=` when connecting to `/mcp/connect`;
///                  must match the tenant JWT claim used by the server to route MCP tool calls
/// @param demo      demo-specific settings controlling auto-start behavior
@ConfigurationProperties(prefix = "hensu")
public record HensuProperties(
        String serverUrl,
        String token,
        String tenantId,
        Demo demo) {

    /// Demo execution settings.
    ///
    /// @param enabled    whether to auto-start a demo execution on application startup
    /// @param workflowId ID of the workflow to execute (must exist on the server)
    public record Demo(boolean enabled, String workflowId) {}
}
