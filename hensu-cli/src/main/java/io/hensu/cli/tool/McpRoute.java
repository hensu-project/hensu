package io.hensu.cli.tool;

import io.hensu.core.tool.ToolPreview;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpServerSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// A running MCP server together with the declaration it was started from.
///
/// Kept from launch rather than recovered from the connection later, so every
/// question about a published tool – which server owns it, what the server
/// calls it, what an operator approving it should be shown – is answered from
/// the declaration that actually started the server.
///
/// @param spec the declaration the server was started from, not null
/// @param connection the open connection to it, not null
record McpRoute(McpServerSpec spec, McpConnection connection) {

    McpRoute {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(connection, "connection must not be null");
    }

    /// Returns the declared server name.
    ///
    /// @return the name, never null
    String name() {
        return spec.name();
    }

    /// Maps a name the agent used back to the name its server knows.
    ///
    /// @param published the name as published to agents, not null
    /// @return the name without this server's prefix, never null
    String serverSideName(String published) {
        String prefix = spec.prefix();
        return prefix.isEmpty() || !published.startsWith(prefix)
                ? published
                : published.substring(prefix.length());
    }

    /// Describes a call to this server for an operator about to approve it.
    ///
    /// There is no argv to show: the process started before the agent chose
    /// anything, so the honest description is which server is being asked what,
    /// and with which argument names. Values stay out of the frame; the audit
    /// trail, by contrast, records the redacted argument values.
    ///
    /// @param toolName the published tool name, not null
    /// @param arguments the agent's arguments, may be null
    /// @return the description of the call, never null
    ToolPreview preview(String toolName, Map<String, Object> arguments) {
        String keys =
                arguments == null || arguments.isEmpty()
                        ? "no arguments"
                        : String.join(", ", arguments.keySet());
        return new ToolPreview(
                summary(toolName, keys),
                List.of(),
                containment(),
                spec.unattended(),
                spec.approvalRequired());
    }

    /// Names what the operator is about to allow.
    ///
    /// For a remote server that includes who receives it: approving a remote
    /// call is approving disclosure, and a frame that does not say where the
    /// arguments are going is asking for consent to something it has not shown.
    private String summary(String toolName, String keys) {
        return switch (spec) {
            case McpServerSpec.Http http ->
                    "MCP server '"
                            + http.name()
                            + "' at "
                            + http.url()
                            + " ("
                            + connection.serverInfo()
                            + "): "
                            + toolName
                            + " ("
                            + keys
                            + ") — this call leaves the machine";
            case McpServerSpec.Stdio stdio ->
                    "MCP server '" + stdio.name() + "': " + toolName + " (" + keys + ")";
        };
    }

    private String containment() {
        return switch (spec) {
            case McpServerSpec.Stdio stdio ->
                    "server launched with network: " + (stdio.sandbox().network() ? "on" : "off");
            case McpServerSpec.Http http ->
                    "no containment applies to a remote server; the declared hosts are the bound,"
                            + " and this one is "
                            + http.host();
        };
    }
}
