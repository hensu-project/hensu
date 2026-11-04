package io.hensu.server.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.tool.ToolCall;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolProvider;
import io.hensu.mcp.McpResultRenderer;
import io.hensu.server.execution.RunContext;
import io.hensu.server.execution.RunHandle;
import io.hensu.server.mcp.ToolIntents.Intent;
import io.hensu.server.mcp.ToolIntents.Settlement;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/// Exposes the calling tenant's relay as a source of tools.
///
/// The server's single contribution to the engine's tool seam. The catalog is whatever
/// the tenant's relay published ({@link McpCatalogs}); a call becomes an intent in
/// {@link ToolIntents}, submitted under the run's claim, which the relay pulls from any
/// node, executes once, and completes through any node. This provider waits for the
/// intent to settle. No node holds a connection to the relay, so no node is special to a
/// tenant, and nothing here depends on which node the run or the relay reached.
///
/// ### Replay
/// A recovered run reaches the calls of its predecessor's attempt under the same keys.
/// Submitting finds the original intent: an intent that settled hands back its outcome
/// without the relay seeing the call again, and one still in flight is waited on rather
/// than issued twice.
///
/// ### Deadlines
/// The provider imposes none of its own. The store settles an intent as `TIMEOUT` when the
/// relay acknowledged it and did not complete it in time – the call may have run – and as
/// `UNAVAILABLE` when no relay acknowledged it before the dispatch deadline – it did not.
/// Both bound every wait, on whichever node waits.
///
/// @implNote Thread-safe. Parallel branches inherit the run through
///     {@link RunContext}, a `ScopedValue`.
@Singleton
public class McpToolProvider implements ToolProvider {

    private static final Logger LOG = Logger.getLogger(McpToolProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Duration RECHECK = Duration.ofSeconds(1);

    private final McpCatalogs catalogs;
    private final ToolIntents intents;
    private final ClusterSignals signals;
    private final Duration callDeadline;
    private final Duration dispatchDeadline;

    @Inject
    public McpToolProvider(
            McpCatalogs catalogs,
            ToolIntents intents,
            ClusterSignals signals,
            @ConfigProperty(name = "hensu.mcp.call-deadline", defaultValue = "60s")
                    Duration callDeadline,
            @ConfigProperty(name = "hensu.mcp.dispatch-deadline", defaultValue = "5m")
                    Duration dispatchDeadline) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs must not be null");
        this.intents = Objects.requireNonNull(intents, "intents must not be null");
        this.signals = Objects.requireNonNull(signals, "signals must not be null");
        this.callDeadline = Objects.requireNonNull(callDeadline, "callDeadline must not be null");
        this.dispatchDeadline =
                Objects.requireNonNull(dispatchDeadline, "dispatchDeadline must not be null");
    }

    @Override
    public List<ToolDefinition> tools() {
        TenantInfo tenant = TenantContext.currentOrNull();
        if (tenant == null) {
            return List.of();
        }
        try {
            return catalogs.tools(tenant.tenantId());
        } catch (RuntimeException e) {
            // A provider that throws is treated as absent by the router, which would hide
            // the outage behind a missing tool. Report the empty catalog and say why.
            LOG.warnv(
                    "Tool catalog of tenant {0} unreadable, exposing no tools: {1}",
                    tenant.tenantId(), e.getMessage());
            return List.of();
        }
    }

    @Override
    public ToolCallResult call(ToolCall call, Map<String, Object> context) {
        RunHandle run = RunContext.current();
        Intent intent = intents.submit(run.claim(), call);
        while (true) {
            ClusterSignals.Waiter waiter = signals.outcomeWaiter(intent.id());
            Optional<Settlement> settlement = intents.settlement(intent);
            if (settlement.isPresent()) {
                return render(call.toolName(), settlement.get());
            }
            run.checkLive();
            try {
                waiter.await(RECHECK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RunPreemptedException("Interrupted while waiting for " + call.key(), e);
            }
        }
    }

    private ToolCallResult render(String toolName, Settlement settlement) {
        return switch (settlement.status()) {
            case "SUCCESS", "FAILURE" -> fromRelay(toolName, settlement);
            case "TIMEOUT" ->
                    ToolCallResult.of(
                            toolName,
                            ToolCallStatus.TIMEOUT,
                            null,
                            "'"
                                    + toolName
                                    + "' started but did not complete within "
                                    + callDeadline.toSeconds()
                                    + " s; it may have run, so its outcome is unknown",
                            null);
            case "UNAVAILABLE" ->
                    ToolCallResult.of(
                            toolName,
                            ToolCallStatus.UNAVAILABLE,
                            null,
                            "No relay took '"
                                    + toolName
                                    + "' within "
                                    + dispatchDeadline.toSeconds()
                                    + " s; it did not run",
                            null);
            // The execution finished under another claim while this run waited.
            case "CANCELLED" ->
                    throw new RunPreemptedException("Call to '" + toolName + "' was cancelled");
            default ->
                    ToolCallResult.failure(
                            toolName, "Unrecognized tool outcome: " + settlement.status());
        };
    }

    private static ToolCallResult fromRelay(String toolName, Settlement settlement) {
        if (settlement.outcome() == null) {
            return ToolCallResult.failure(toolName, "The relay reported no outcome");
        }
        try {
            JsonNode outcome = JSON.readTree(settlement.outcome());
            if (outcome.has("error")) {
                return ToolCallResult.failure(
                        toolName, outcome.path("error").path("message").asText("Tool error"));
            }
            return McpResultRenderer.toResult(
                    toolName, JSON.convertValue(outcome.path("result"), MAP_TYPE));
        } catch (Exception e) {
            return ToolCallResult.failure(toolName, "Unreadable tool outcome: " + e.getMessage());
        }
    }
}
