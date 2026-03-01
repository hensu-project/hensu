package io.hensu.integrations.springclient.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.integrations.springclient.config.HensuProperties;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

/// MCP split-pipe transport: bridges hensu-server's SSE-based tool dispatch to local handlers.
///
/// ### Split-pipe protocol
/// Hensu-server cannot call tools directly — it pushes JSON-RPC requests via SSE and
/// expects responses via HTTP POST. This component implements both sides:
///
/// ```
/// +——————————————————+      SSE stream       +——————————————————————+
/// │   hensu-server   │ ————————————————————> │  HensuMcpTransport   │
/// │  /mcp/connect    │  tools/call requests  │  (this component)    │
/// +——————————————————+                       +——————————————————————+
///         ^                                           │
///         │           HTTP POST                       │  dispatch()
///         │       /mcp/message                        V
/// +——————————————————+                       +——————————————————————+
/// │   hensu-server   │ <———————————————————— │   ToolDispatcher     │
/// │  (result routed  │  JSON-RPC response    │  (local execution)   │
/// │  to workflow)    │                       +——————————————————————+
/// +——————————————————+
/// ```
///
/// ### Reconnection
/// The SSE connection is automatically re-established with exponential backoff
/// if the server disconnects or an error occurs. The `clientId` remains stable
/// across reconnects so in-flight requests are re-matched if the server buffers them.
///
/// @see ToolDispatcher for tool routing
/// @see io.hensu.integrations.springclient.tools for concrete tool implementations
@Component
public class HensuMcpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(HensuMcpTransport.class);

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final WebClient webClient;
    private final ToolDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final HensuProperties props;

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

    /// Connects to hensu-server's MCP SSE endpoint and starts processing tool calls.
    ///
    /// Non-blocking — the SSE subscription runs on a Reactor scheduler thread.
    /// Reconnects automatically with exponential backoff (5s base, 60s max) on any error.
    ///
    /// @return disposable handle to cancel the transport (for graceful shutdown)
    public Disposable connect() {
        String tenantId = props.tenantId();

        LOG.info("Connecting MCP transport: tenantId={}", tenantId);

        return webClient
                .get()
                .uri("/mcp/connect?clientId={clientId}", tenantId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE_TYPE)
                .doOnSubscribe(s -> LOG.info("MCP SSE stream established: tenantId={}", tenantId))
                .doOnTerminate(() -> LOG.warn("MCP SSE stream terminated: tenantId={}", tenantId))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                        .maxBackoff(Duration.ofSeconds(60))
                        .doBeforeRetry(signal -> LOG.warn(
                                "MCP transport reconnecting (attempt {}): {}",
                                signal.totalRetries() + 1,
                                signal.failure().getMessage())))
                .subscribe(
                        this::handleEvent,
                        e -> LOG.error("MCP transport fatal error", e));
    }

    private void handleEvent(ServerSentEvent<String> sse) {
        String data = sse.data();
        if (data == null || data.isBlank()) {
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            String method = node.path("method").asText(null);

            if (method == null) {
                // Response or notification without method — not a tool call
                return;
            }

            switch (method) {
                case "tools/list" ->
                    // Discovery request: called at execution start so LlmPlanner
                    // knows what tools are available to include in the planning prompt.
                    // Without responding here the planner sees zero tools and the LLM
                    // will never generate a plan that calls fetch_customer_data or
                    // calculate_risk_score.
                        handleToolsList(node);
                case "tools/call" -> handleToolCall(node);
                case "ping" -> LOG.debug("MCP ping received from server");
                default -> LOG.debug("Unhandled MCP method: {}", method);
            }

        } catch (Exception e) {
            LOG.error("Failed to parse MCP event: {}", data, e);
        }
    }

    private void handleToolsList(JsonNode node) {
        String requestId = node.path("id").asText(null);
        LOG.info("MCP tools/list request received: requestId={}", requestId);

        Map<String, Object> result = Map.of("tools", dispatcher.listTools());
        postResponse(requestId, result);
    }

    private void handleToolCall(JsonNode node) {
        String requestId = node.path("id").asText(null);
        JsonNode params = node.path("params");
        String toolName = params.path("name").asText();

        Map<String, Object> arguments;
        try {
            arguments = objectMapper.convertValue(params.path("arguments"), MAP_TYPE);
        } catch (Exception e) {
            LOG.warn("Failed to parse tool arguments for tool={}: {}", toolName, e.getMessage());
            arguments = Map.of();
        }

        LOG.info("MCP tool call received: tool={}, requestId={}", toolName, requestId);

        Map<String, Object> result = dispatcher.dispatch(toolName, arguments);
        postResponse(requestId, result);
    }

    private void postResponse(String requestId, Map<String, Object> result) {
        Map<String, Object> response = Map.of(
                "jsonrpc", "2.0",
                "id", requestId,
                "result", result);

        webClient.post()
                .uri("/mcp/message")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(response)
                .retrieve()
                .toBodilessEntity()
                .doOnSuccess(r -> LOG.debug("MCP response posted: requestId={}", requestId))
                .doOnError(e -> LOG.error(
                        "Failed to post MCP response: requestId={}", requestId, e))
                .subscribe();
    }
}
