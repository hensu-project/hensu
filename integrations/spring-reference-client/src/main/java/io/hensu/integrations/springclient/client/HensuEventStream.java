package io.hensu.integrations.springclient.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

/// Reactive SSE consumer for hensu-server execution events.
///
/// Connects to `GET /api/v1/executions/{executionId}/events` and dispatches
/// incoming events to caller-provided callbacks.
///
/// ### Event types
/// Each SSE data payload is a JSON object with a `type` discriminator:
///
/// | Type                  | Key fields                                         |
/// |-----------------------|----------------------------------------------------|
/// | `execution.started`   | executionId, workflowId, tenantId                  |
/// | `plan.created`        | planId, nodeId, source, steps[]                    |
/// | `step.started`        | planId, stepIndex, toolName, description           |
/// | `step.completed`      | planId, stepIndex, success, output, error          |
/// | `plan.revised`        | planId, reason, previousStepCount, newStepCount    |
/// | `plan.completed`      | planId, success, output                            |
/// | `execution.paused`    | nodeId, planId, reason                             |
/// | `execution.completed` | workflowId, success, finalNodeId, output{}         |
/// | `execution.error`     | errorType, message, nodeId                         |
///
/// @see HensuClient#startExecution for obtaining an executionId
@Component
public class HensuEventStream {

    private static final Logger LOG = LoggerFactory.getLogger(HensuEventStream.class);

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public HensuEventStream(WebClient hensuWebClient, ObjectMapper objectMapper) {
        this.webClient = hensuWebClient;
        this.objectMapper = objectMapper;
    }

    /// Subscribes to the SSE event stream for a running execution.
    ///
    /// The subscription is non-blocking. The returned `Disposable` can be used
    /// to cancel the stream when no longer needed. The stream terminates
    /// naturally when the server closes it (after `execution.completed` or
    /// `execution.error`).
    ///
    /// @param executionId the execution to monitor
    /// @param onEvent     callback invoked for every event (receives the full event map)
    /// @param onError     callback invoked on stream error or connection failure
    /// @return disposable handle to cancel the subscription
    public Disposable subscribe(
            String executionId,
            Consumer<Map<String, Object>> onEvent,
            Consumer<Throwable> onError) {

        return webClient
                .get()
                .uri("/api/v1/executions/{id}/events", executionId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE_TYPE)
                .doOnSubscribe(s -> LOG.debug("SSE stream connected for execution {}", executionId))
                .doOnTerminate(() -> LOG.debug("SSE stream closed for execution {}", executionId))
                .subscribe(
                        sse -> dispatch(sse, onEvent),
                        onError,
                        () -> LOG.info("SSE stream completed for execution {}", executionId));
    }

    private void dispatch(ServerSentEvent<String> sse, Consumer<Map<String, Object>> onEvent) {
        String data = sse.data();
        if (data == null || data.isBlank()) {
            return;
        }
        try {
            Map<String, Object> event = objectMapper.readValue(data, MAP_TYPE);
            String type = (String) event.getOrDefault("type", "unknown");
            LOG.debug("SSE event received: type={}", type);
            onEvent.accept(event);
        } catch (Exception e) {
            LOG.warn("Failed to parse SSE event data: {}", data, e);
        }
    }
}
