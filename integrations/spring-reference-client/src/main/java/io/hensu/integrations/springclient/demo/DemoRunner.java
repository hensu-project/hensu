package io.hensu.integrations.springclient.demo;

import io.hensu.integrations.springclient.client.HensuClient;
import io.hensu.integrations.springclient.client.HensuEventStream;
import io.hensu.integrations.springclient.config.HensuProperties;
import io.hensu.integrations.springclient.mcp.HensuMcpTransport;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/// Demo orchestrator — wires the full integration scenario on startup.
///
/// Active only when `hensu.demo.enabled=true` (set in `application.yml`).
///
/// ### Scenario: credit risk assessment with human review
/// ```
/// 1. MCP transport connects to /mcp/connect (stays open, auto-reconnects)
/// 2. Execution started: POST /api/v1/executions
/// 3. SSE subscribed: GET /api/v1/executions/{id}/events
///
/// Server executes the workflow asynchronously:
///   fetch-data node    → MCP tool call → fetch_customer_data (handled here)
///   calculate-risk node → MCP tool call → calculate_risk_score (handled here)
///   analyst-review node → workflow pauses, emits execution.paused
///
/// 4. execution.paused SSE event received → operator prompted to review
/// 5. Operator calls POST /demo/review/{executionId} (see ReviewController)
/// 6. execution.completed SSE event received → output logged
/// ```
///
/// @see io.hensu.integrations.springclient.review.ReviewController for step 5
/// @see io.hensu.integrations.springclient.mcp.HensuMcpTransport for steps 1 + tool calls
@Component
@ConditionalOnProperty(prefix = "hensu.demo", name = "enabled", havingValue = "true")
public class DemoRunner implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoRunner.class);

    private final HensuClient hensuClient;
    private final HensuEventStream eventStream;
    private final HensuMcpTransport mcpTransport;
    private final HensuProperties props;

    private final AtomicReference<Disposable> mcpDisposable = new AtomicReference<>();
    private final AtomicReference<Disposable> eventDisposable = new AtomicReference<>();

    public DemoRunner(
            HensuClient hensuClient,
            HensuEventStream eventStream,
            HensuMcpTransport mcpTransport,
            HensuProperties props) {
        this.hensuClient = hensuClient;
        this.eventStream = eventStream;
        this.mcpTransport = mcpTransport;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        // 1. Open MCP split-pipe (non-blocking, persists for the app lifetime)
        mcpDisposable.set(mcpTransport.connect());
        LOG.info("MCP transport connected as tenantId={}", props.tenantId());

        // 2. Start workflow execution
        String workflowId = props.demo().workflowId();
        Map<String, Object> context = Map.of(
                "customerId", "C-42",
                "requestType", "credit-limit-increase");

        LOG.info("Starting execution: workflowId={}, context={}", workflowId, context);

        HensuClient.StartResult started;
        try {
            started = hensuClient.startExecution(workflowId, context);
        } catch (Exception e) {
            LOG.error("""
                    Failed to start execution. Is hensu-server running at {}?
                    Make sure the '{}' workflow is pushed first:
                      ./hensu build risk-assessment -d integrations/spring-reference-client/working-dir
                      ./hensu push {} --server {}
                    """,
                    props.serverUrl(), workflowId, workflowId, props.serverUrl(), e);
            return;
        }

        String executionId = started.executionId();
        LOG.info("Execution started: executionId={}", executionId);

        // 3. Subscribe to SSE execution events
        eventDisposable.set(eventStream.subscribe(
                executionId,
                event -> handleEvent(event, executionId),
                error -> LOG.error("SSE stream error for execution {}", executionId, error)));
    }

    @PreDestroy
    void shutdown() {
        dispose(mcpDisposable);
        dispose(eventDisposable);
    }

    private static void dispose(AtomicReference<Disposable> ref) {
        Disposable d = ref.getAndSet(null);
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }

    private void handleEvent(Map<String, Object> event, String executionId) {
        String type = (String) event.getOrDefault("type", "unknown");

        switch (type) {
            case "execution.started" -> LOG.info(
                    "[{}] Execution started: workflowId={}",
                    executionId, event.get("workflowId"));

            case "plan.created" -> LOG.info(
                    "[{}] Plan created: planId={}, steps={}",
                    executionId, event.get("planId"),
                    event.get("steps") instanceof java.util.List<?> s ? s.size() : "?");

            case "step.started" -> LOG.info(
                    "[{}] Step started: tool={}, index={}",
                    executionId, event.get("toolName"), event.get("stepIndex"));

            case "step.completed" -> LOG.info(
                    "[{}] Step completed: index={}, success={}",
                    executionId, event.get("stepIndex"), event.get("success"));

            case "execution.paused" -> LOG.warn("""

                    ┌──────────────────────────────────────────────────────────
                    │          EXECUTION PAUSED — ACTION REQUIRED
                    ├──────────────────────────────────────────────────────────
                    │  Execution : {}
                    │  Node      : {}
                    │  Reason    : {}
                    ├──────────────────────────────────────────────────────────
                    │  To APPROVE:
                    │    curl -X POST http://localhost:8081/demo/review/{} \\
                    │         -H 'Content-Type: application/json' \\
                    │         -d '{"approved":true,"modifications":\\{}}'
                    │
                    │  To REJECT:
                    │    curl -X POST http://localhost:8081/demo/review/{} \\
                    │         -H 'Content-Type: application/json' \\
                    │         -d '{"approved":false,"modifications":\\{}}'
                    └──────────────────────────────────────────────────────────
                    """,
                    executionId,
                    event.get("nodeId"),
                    event.get("reason"),
                    executionId,
                    executionId);

            case "execution.completed" -> {
                boolean success = Boolean.TRUE.equals(event.get("success"));
                Object output = event.get("output");
                if (success) {
                    LOG.info("""

                            [{}] Execution COMPLETED successfully.
                            Final node : {}
                            Output     : {}
                            """, executionId, event.get("finalNodeId"), output);
                } else {
                    LOG.warn("""

                            [{}] Execution COMPLETED with failure.
                            Final node : {}
                            Output     : {}
                            """, executionId, event.get("finalNodeId"), output);
                }
            }

            case "execution.error" -> LOG.error(
                    "[{}] Execution error at node={}: [{}] {}",
                    executionId, event.get("nodeId"),
                    event.get("errorType"), event.get("message"));

            default -> {}
        }
    }
}
