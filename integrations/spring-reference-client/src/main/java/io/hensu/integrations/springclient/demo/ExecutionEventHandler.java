package io.hensu.integrations.springclient.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.integrations.springclient.client.HensuEventStream;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

/// Manages SSE subscriptions and handles execution events.
///
/// Extracted from {@link DemoRunner} so that both the initial startup flow and
/// the post-review re-subscribe flow can share the same subscription lifecycle
/// and event handling logic without coupling to {@link DemoRunner}'s
/// {@link org.springframework.boot.CommandLineRunner} role.
///
/// ### Lifecycle
/// - {@link #subscribe(String)} — connects to the SSE stream for an execution
/// - {@link #resubscribe(String)} — disposes the current subscription and opens a fresh one
///   (called after submitting a review, since the server closes the SSE stream on pause)
/// - {@link #shutdown()} — disposes the active subscription on application stop
///
/// @see HensuEventStream for the reactive SSE consumer
@Service
public class ExecutionEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionEventHandler.class);

    private final HensuEventStream eventStream;
    private final ObjectMapper objectMapper;

    private final AtomicReference<Disposable> eventDisposable = new AtomicReference<>();

    public ExecutionEventHandler(HensuEventStream eventStream, ObjectMapper objectMapper) {
        this.eventStream = eventStream;
        this.objectMapper = objectMapper;
    }

    /// Subscribes to SSE events for the given execution.
    ///
    /// Disposes any existing subscription before opening a new one.
    ///
    /// @param executionId the execution to monitor
    public void subscribe(String executionId) {
        dispose();
        eventDisposable.set(eventStream.subscribe(
                executionId,
                event -> handleEvent(event, executionId),
                error -> LOG.error("SSE stream error for execution {}", executionId, error)));
    }

    /// Re-subscribes to SSE events after a review decision is submitted.
    ///
    /// The server closes the SSE stream when an execution pauses, so a fresh
    /// subscription is needed to receive post-resume events like
    /// {@code execution.completed}.
    ///
    /// @param executionId the execution to re-subscribe to
    public void resubscribe(String executionId) {
        subscribe(executionId);
        LOG.info("Re-subscribed to SSE for execution {}", executionId);
    }

    @PreDestroy
    void shutdown() {
        dispose();
    }

    private void dispose() {
        Disposable d = eventDisposable.getAndSet(null);
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
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

            case "execution.paused" -> {
                String corrId = String.valueOf(event.getOrDefault("correlationId", ""));
                String output = toJson(event.get("output"));
                String curlApprove = String.format(
                        """
                                curl -X POST http://localhost:8081/demo/review/%s \\
                                    -H 'Content-Type: application/json' \\
                                    -d '{"correlationId":"%s","approved":true,"modifications":{}}'""",
                        executionId, corrId);
                String curlReject = String.format(
                        """
                                curl -X POST http://localhost:8081/demo/review/%s \\
                                    -H 'Content-Type: application/json' \\
                                    -d '{"correlationId":"%s","approved":false,"modifications":{}}'""",
                        executionId, corrId);
                LOG.warn("""

                        ┌──────────────────────────────────────────────────────────
                                  EXECUTION PAUSED — ACTION REQUIRED
                         ──────────────────────────────────────────────────────────
                           Execution     : {}
                           Node          : {}
                           Reason        : {}
                           CorrelationId : {}
                         ──────────────────────────────────────────────────────────
                           Output to review:
                        {}
                         ──────────────────────────────────────────────────────────
                           To APPROVE:
                             {}

                           To REJECT:
                             {}
                        └──────────────────────────────────────────────────────────
                        """,
                        executionId,
                        event.get("nodeId"),
                        event.get("reason"),
                        corrId,
                        output,
                        curlApprove,
                        curlReject);
            }

            case "execution.completed" -> {
                boolean success = Boolean.TRUE.equals(event.get("success"));
                String output = toJson(event.get("output"));
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
