package io.hensu.server.mcp;

import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolProvider;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpResultRenderer;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/// Exposes the calling tenant's MCP server as a source of tools.
///
/// This is the server's single contribution to the engine's tool seam: the
/// catalog comes from {@link McpToolDiscovery} and invocation goes through the
/// {@link McpConnectionPool}, both scoped to whichever tenant is bound to the
/// calling thread. Nothing about the workflow engine changes between runtimes –
/// the CLI contributes its own providers and the router composes whatever it is
/// given.
///
/// ### Tenant Scoping
/// {@link TenantContext} is a `ScopedValue`, and parallel branches fork through
/// `StructuredTaskScope`, which inherits scoped-value bindings. A branch
/// therefore sees the same tenant as the execution that forked it, so resolving
/// the tenant inside {@link #call} is safe under fan-out.
///
/// ### Empty Catalog Outside a Tenant
/// Startup validation of duplicate tool names asks every provider for its
/// catalog before any request is bound, so {@link #tools()} answers with an
/// empty list there. That is expected, not a failure: the authoritative
/// duplicate check runs again on every catalog read, when a tenant is bound.
///
/// ### Deadline
/// The engine runs no watchdog around a tool call, and the SSE transport waits
/// indefinitely for a response, so this provider bounds every call itself with
/// the configured read timeout and reports {@link ToolCallStatus#TIMEOUT} when
/// it expires.
///
/// @implNote Uses Java 25 preview API ({@code StructuredTaskScope}).
/// @see ToolProvider for the seam contract
/// @see McpResultRenderer for how a response becomes agent-readable text
@Singleton
public class McpToolProvider implements ToolProvider {

    private static final Logger LOG = Logger.getLogger(McpToolProvider.class);

    private final McpToolDiscovery discovery;
    private final McpConnectionPool connectionPool;
    private final Duration callTimeout;

    /// Creates the provider over the server's MCP infrastructure.
    ///
    /// @param discovery tenant-scoped tool discovery, not null
    /// @param connectionPool pool supplying live MCP connections, not null
    /// @param callTimeout wall clock imposed on a single tool call, not null
    @Inject
    public McpToolProvider(
            McpToolDiscovery discovery,
            McpConnectionPool connectionPool,
            @ConfigProperty(name = "hensu.mcp.read-timeout", defaultValue = "60s")
                    Duration callTimeout) {
        this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
        this.connectionPool =
                Objects.requireNonNull(connectionPool, "connectionPool must not be null");
        this.callTimeout = Objects.requireNonNull(callTimeout, "callTimeout must not be null");
    }

    @Override
    public List<ToolDefinition> tools() {
        TenantInfo tenant = TenantContext.currentOrNull();
        if (tenant == null || !tenant.hasMcp()) {
            return List.of();
        }
        try {
            return discovery.discoverTools();
        } catch (RuntimeException e) {
            // A provider that throws is treated as absent by the router, which would
            // hide the outage behind a missing tool. Report the empty catalog
            // deliberately and say why.
            LOG.warnv(
                    "MCP tool discovery failed for tenant {0}, exposing no tools: {1}",
                    tenant.tenantId(), e.getMessage());
            return List.of();
        }
    }

    @Override
    public ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context) {
        TenantInfo tenant = TenantContext.currentOrNull();
        if (tenant == null) {
            return ToolCallResult.failure(
                    toolName, "No tenant context bound. MCP tool calls require tenant context.");
        }
        if (!tenant.hasMcp()) {
            return ToolCallResult.failure(
                    toolName, "Tenant '" + tenant.tenantId() + "' has no MCP endpoint configured.");
        }

        String endpoint = tenant.mcpEndpoint();

        try {
            return McpResultRenderer.toResult(
                    toolName, callWithDeadline(toolName, endpoint, arguments));
        } catch (StructuredTaskScope.TimeoutException e) {
            LOG.warnv("MCP tool call {0} exceeded {1}", toolName, callTimeout);
            return ToolCallResult.of(
                    toolName,
                    ToolCallStatus.TIMEOUT,
                    null,
                    "MCP tool '" + toolName + "' did not respond within " + callTimeout,
                    null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolCallResult.failure(toolName, "MCP tool call was interrupted: " + toolName);
        } catch (RuntimeException e) {
            LOG.warnv("MCP tool call failed for {0}: {1}", toolName, e.getMessage());
            return ToolCallResult.failure(toolName, e.getMessage());
        }
    }

    /// Runs one tool call on a forked virtual thread bounded by the configured timeout.
    ///
    /// The fork exists purely for the deadline: the SSE transport blocks until a
    /// response arrives, so the only way to stop waiting is to cancel the scope,
    /// which interrupts the waiting thread.
    private Map<String, Object> callWithDeadline(
            String toolName, String endpoint, Map<String, Object> arguments)
            throws InterruptedException {
        var threadFactory = Thread.ofVirtual().name("mcp-tool-" + toolName + "-", 0).factory();
        try (var scope =
                StructuredTaskScope.open(
                        StructuredTaskScope.Joiner.<Map<String, Object>>awaitAll(),
                        cf ->
                                cf.withThreadFactory(threadFactory)
                                        .withTimeout(callTimeout)
                                        .withName("mcp-tool-" + toolName))) {

            Subtask<Map<String, Object>> subtask =
                    scope.fork(
                            () -> {
                                McpConnection connection = connectionPool.get(endpoint);
                                return connection.callTool(toolName, arguments);
                            });

            scope.join();

            if (subtask.state() == Subtask.State.FAILED) {
                throw asRuntime(subtask.exception());
            }
            return subtask.get();
        }
    }

    private static RuntimeException asRuntime(Throwable failure) {
        return failure instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException(failure.getMessage(), failure);
    }
}
