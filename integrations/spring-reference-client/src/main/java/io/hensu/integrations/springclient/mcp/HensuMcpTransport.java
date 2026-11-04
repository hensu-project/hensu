package io.hensu.integrations.springclient.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.integrations.springclient.config.HensuProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

/// The tenant's tool relay: pulls hensu-server's tool calls and runs them on local handlers.
///
/// ### Relay protocol
/// The server never connects out and holds no connection to this relay. The relay makes
/// ordinary requests – each one may reach any server node behind the load balancer:
///
/// ```
/// +——————————————————+  PUT  /mcp/v1/catalog            +——————————————————————+
/// │                  │ <—————————————————————————————————  │                      │
/// │   hensu-server   │  POST /mcp/v1/poll  (long poll)    │  HensuMcpTransport   │
/// │   (any node)     │ <—————————————————————————————————  │  (this component)    │
/// │                  │  POST /tasks/{token}/ack           │          │           │
/// │                  │ <—————————————————————————————————  │          V           │
/// │                  │  POST /tasks/{token}/complete      │   ToolDispatcher     │
/// │                  │ <—————————————————————————————————  │  (local execution)   │
/// +——————————————————+                                    +——————————————————————+
/// ```
///
/// ### Contract
/// - **Acknowledge before executing, execute only on 204.** The server may offer a call
///   more than once – a poll response can be lost – but accepts exactly one
///   acknowledgement per call, so a relay that follows this rule runs each call at most
///   once. A 409 means the offer is no longer current: drop it.
/// - **Retry `ack` and `complete` until a node answers.** Both are safe to repeat; any node
///   can serve them.
/// - **Publish the catalog before polling, and again when a poll answers 409.** The poll
///   carries the catalog's version, a digest of the tool list.
/// - The call's `key` names the execution and the call within it, so it is unique across
///   the tenant's executions and stable across server-side recoveries; a relay that
///   persists keys can refuse to repeat a call even across its own restarts. This one
///   keeps the keys of calls in flight only.
@Component
public class HensuMcpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(HensuMcpTransport.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Duration POLL_WAIT = Duration.ofSeconds(25);
    private static final int POLLERS = 2;

    private final WebClient webClient;
    private final ToolDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final HensuProperties props;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public HensuMcpTransport(
            WebClient hensuWebClient,
            ToolDispatcher dispatcher,
            ObjectMapper objectMapper,
            HensuProperties props) {
        this.webClient = hensuWebClient;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /// Publishes the catalog and starts polling for tool calls in the background.
    ///
    /// @return handle that stops the relay when disposed
    public Disposable connect() {
        AtomicBoolean running = new AtomicBoolean(true);
        String version = catalogVersion();
        List<Thread> pollers =
                java.util.stream.IntStream.range(0, POLLERS)
                        .mapToObj(
                                i ->
                                        Thread.ofVirtual()
                                                .name("hensu-relay-" + i)
                                                .start(() -> pollLoop(running, version)))
                        .toList();
        LOG.info("Relay started for tenantId={}, catalog {}", props.tenantId(), version);
        return new Disposable() {
            @Override
            public void dispose() {
                running.set(false);
                pollers.forEach(Thread::interrupt);
            }

            @Override
            public boolean isDisposed() {
                return !running.get();
            }
        };
    }

    private void pollLoop(AtomicBoolean running, String version) {
        publish(running, version);
        while (running.get()) {
            try {
                JsonNode response =
                        webClient
                                .post()
                                .uri("/mcp/v1/poll")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(
                                        Map.of(
                                                "catalogVersion", version,
                                                "max", 8,
                                                "waitMs", POLL_WAIT.toMillis()))
                                .exchangeToMono(
                                        r ->
                                                r.statusCode().value() == 409
                                                        ? r.releaseBody().thenReturn(objectMapper.createObjectNode().put("stale", true))
                                                        : r.bodyToMono(JsonNode.class))
                                .block(POLL_WAIT.plusSeconds(5));
                if (response == null) {
                    continue;
                }
                if (response.path("stale").asBoolean(false)) {
                    publish(running, version);
                    continue;
                }
                for (JsonNode task : response.path("tasks")) {
                    Thread.ofVirtual().start(() -> handle(running, task));
                }
            } catch (Exception e) {
                if (running.get()) {
                    LOG.warn("Poll failed, retrying: {}", e.getMessage());
                    sleep(1000);
                }
            }
        }
    }

    private void handle(AtomicBoolean running, JsonNode task) {
        String token = task.path("token").asText();
        String key = task.path("key").asText();
        String tool = task.path("tool").asText();
        if (!inFlight.add(key)) {
            // Re-offered while still running here: the server will settle it from our completion.
            return;
        }
        try {
            int ack = untilAnswered(running, "/mcp/v1/tasks/" + token + "/ack", null);
            if (ack != 204) {
                LOG.debug("Offer of {} no longer current ({}); not executing", key, ack);
                return;
            }
            Map<String, Object> arguments =
                    objectMapper.convertValue(task.path("arguments"), MAP_TYPE);
            LOG.info("Tool call {}: {} {}", key, tool, arguments.keySet());
            Map<String, Object> outcome = new LinkedHashMap<>();
            try {
                outcome.put("result", dispatcher.dispatch(tool, arguments));
            } catch (IllegalArgumentException e) {
                LOG.warn("Tool call {} refused: {}", key, e.getMessage());
                outcome.put("error", Map.of("code", -32602, "message", e.getMessage()));
            } catch (Exception e) {
                LOG.error("Tool execution failed for '{}': {}", tool, e.getMessage());
                outcome.put("error", Map.of("code", -32000, "message", "Execution error: " + e.getMessage()));
            }
            int complete = untilAnswered(running, "/mcp/v1/tasks/" + token + "/complete", outcome);
            if (complete == 409) {
                LOG.warn("Call {} had already settled (deadline passed); outcome not recorded", key);
            }
        } finally {
            inFlight.remove(key);
        }
    }

    private void publish(AtomicBoolean running, String version) {
        Map<String, Object> catalog = Map.of("version", version, "tools", dispatcher.listTools());
        while (running.get()) {
            try {
                HttpStatusCode status =
                        webClient
                                .put()
                                .uri("/mcp/v1/catalog")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(catalog)
                                .exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode()))
                                .block(Duration.ofSeconds(10));
                if (status != null && status.is2xxSuccessful()) {
                    return;
                }
                LOG.warn("Catalog publish answered {}", status);
            } catch (Exception e) {
                LOG.warn("Catalog publish failed: {}", e.getMessage());
            }
            sleep(2000);
        }
    }

    /// Sends a request until some node gives a definite (non-5xx) answer.
    private int untilAnswered(AtomicBoolean running, String path, Object body) {
        long backoff = 200;
        while (running.get()) {
            try {
                WebClient.RequestBodySpec request = webClient.post().uri(path);
                WebClient.RequestHeadersSpec<?> spec =
                        body == null ? request : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
                HttpStatusCode status =
                        spec.exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode()))
                                .block(Duration.ofSeconds(10));
                if (status != null && !status.is5xxServerError()) {
                    return status.value();
                }
            } catch (Exception e) {
                LOG.debug("{} failed, retrying: {}", path, e.getMessage());
            }
            sleep(backoff);
            backoff = Math.min(backoff * 2, 5000);
        }
        return -1;
    }

    private String catalogVersion() {
        try {
            byte[] tools = objectMapper.writeValueAsString(dispatcher.listTools()).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tools)).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint the tool catalog", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
