package io.hensu.mcp;

import io.hensu.core.execution.action.SandboxPolicy;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// One locally launched MCP server, as a deployment declared it.
///
/// A stdio server is a process the engine starts and keeps, so the declaration
/// carries everything a launch needs: the argv, the environment the process is
/// allowed to add to the hermetic base, the containment it runs under, and how
/// long a single JSON-RPC request may take before the client stops waiting.
///
/// ### Policy is per server, not per tool
/// `unattended` and `approvalRequired` mirror the same flags on a command
/// entry, and they apply to every tool the server exposes. A server is one
/// trust decision: an operator who is willing to launch it is willing to let it
/// answer, and per-tool grain would ask them to reason about a catalog they do
/// not control and that can change when the server updates.
///
/// @param name the identifier the deployment gave this server, not null or blank
/// @param command the argv to launch, not null and not empty, already resolved
/// @param env variables added to the hermetic base, not null (may be empty)
/// @param sandbox the containment the launch runs under, not null
/// @param requestTimeoutMs wall clock for one JSON-RPC request, positive
/// @param unattended whether the server may answer in a run with no human present
/// @param approvalRequired whether every call needs a reviewer's consent first
/// @see StdioMcpConnection for the client that launches this
/// @see io.hensu.core.execution.action.HermeticEnvironment for the base environment
public record McpServerSpec(
        String name,
        List<String> command,
        Map<String, String> env,
        SandboxPolicy sandbox,
        long requestTimeoutMs,
        boolean unattended,
        boolean approvalRequired) {

    /// Wall clock a request gets when the declaration names none.
    ///
    /// Long enough for a server that has to reach a network service, short
    /// enough that a wedged server fails the node rather than the run.
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 30_000L;

    /// Compact constructor taking defensive copies and validating the launch.
    public McpServerSpec {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("server name must not be blank");
        }
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("server '" + name + "' declares no command");
        }
        if (requestTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "server '" + name + "' declares a non-positive timeout: " + requestTimeoutMs);
        }
        command = List.copyOf(command);
        env = env != null ? Map.copyOf(env) : Map.of();
        sandbox = sandbox != null ? sandbox : SandboxPolicy.restrictive();
    }

    /// Creates a server declaration with the default timeout and closed policy.
    ///
    /// @param name the identifier for this server, not null or blank
    /// @param command the argv to launch, not null and not empty
    /// @return the declaration, never null
    public static McpServerSpec of(String name, List<String> command) {
        return new McpServerSpec(
                name,
                command,
                Map.of(),
                SandboxPolicy.restrictive(),
                DEFAULT_REQUEST_TIMEOUT_MS,
                false,
                false);
    }
}
