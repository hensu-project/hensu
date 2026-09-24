package io.hensu.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/// A loopback MCP endpoint the Streamable HTTP tests drive.
///
/// Built on the JDK's own `com.sun.net.httpserver`, so no dependency is added
/// anywhere and nothing here can reach a native image. Every request is recorded
/// – method, path, headers and body – so a test can assert on what actually went
/// out rather than on what the client meant to send.
///
/// ### Selecting A Behaviour
/// The fixture is configured by fluent setters rather than by subclassing,
/// because most tests want one deviation from an otherwise normal server:
///
/// {@snippet :
/// try (FakeHttpMcpServer server = FakeHttpMcpServer.start().legacy().mintingSessions()) {
///     // ...
/// }
/// }
///
/// ### Header Validation
/// In the modern era the fixture checks a `tools/call` the way revision
/// 2026-07-28 obliges a real server to: `Mcp-Name` must equal the body's tool
/// name, and every `x-mcp-header` parameter present in the body must arrive
/// as a matching `Mcp-Param-{Name}` header – and must not arrive when the body
/// has no value. Any disagreement is `400` with `-32020`. The decoding here is
/// written independently of the client's encoder, so a round trip proves the
/// two agree rather than that one function agrees with itself.
///
/// @implNote **Mutable and thread-safe.** Handlers run on virtual threads and
///     read volatile configuration; recordings are on concurrent collections.
public final class FakeHttpMcpServer implements AutoCloseable {

    /// One request the fixture saw.
    ///
    /// @param method the HTTP method, never null
    /// @param path the request path, never null
    /// @param headers the request headers, lower-cased keys, never null
    /// @param body the request body, never null (may be empty)
    public record Recorded(String method, String path, Map<String, String> headers, String body) {

        /// Reads one header.
        ///
        /// @param name the header name, case-insensitive, not null
        /// @return the value, or null when the request did not carry it
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /// Returns the JSON-RPC method the body named, by simple text match.
        ///
        /// @return the method name, or empty when the body named none
        public String rpcMethod() {
            int at = body.indexOf("\"method\":\"");
            if (at < 0) {
                return "";
            }
            int from = at + "\"method\":\"".length();
            int to = body.indexOf('"', from);
            return to < 0 ? "" : body.substring(from, to);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final Map<String, Map<String, Object>> extraTools =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final List<String> sessions = new CopyOnWriteArrayList<>();
    private final CountDownLatch cancellationArrived = new CountDownLatch(1);
    private final AtomicInteger initializes = new AtomicInteger();
    private final AtomicInteger sessionSeq = new AtomicInteger();
    private final AtomicInteger staleThrough = new AtomicInteger();

    private volatile boolean legacy;
    private volatile boolean sse;
    private volatile boolean mintSessions;
    private volatile boolean rejectEverySession;
    private volatile boolean declaresToolsCapability = true;
    private volatile String announcedRevision = McpProtocol.LEGACY_REVISION;
    private volatile List<String> unsupportedVersionOffer;
    private volatile int legacyProbeStatus = 405;
    private volatile int initializeStatus = 200;
    private volatile String redirectLocation;
    private volatile long answerDelayMs;
    private volatile long cancellationDelayMs;
    private volatile boolean truncate;
    private volatile String requiredBearer;
    private volatile Map<String, Object> toolSchema =
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("path", Map.of("type", "string")),
                    "required",
                    List.of("path"));

    private FakeHttpMcpServer(HttpServer server) {
        this.server = server;
    }

    /// Starts a fixture on an ephemeral loopback port.
    ///
    /// @return the running fixture, never null
    /// @throws IOException if the port cannot be bound
    public static FakeHttpMcpServer start() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeHttpMcpServer fixture = new FakeHttpMcpServer(http);
        http.createContext("/mcp", fixture::handle);
        http.setExecutor(
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("fake-mcp-", 0).factory()));
        http.start();
        return fixture;
    }

    /// The endpoint a declaration should point at.
    ///
    /// @return the absolute URL, never null
    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    /// The host a declaration must allowlist to reach this fixture.
    ///
    /// @return the loopback host, never null
    public String host() {
        return "127.0.0.1";
    }

    /// Every request the fixture saw, in arrival order.
    ///
    /// @return the recordings, never null
    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    /// How many `initialize` requests arrived.
    ///
    /// @return the count
    public int initializeCount() {
        return initializes.get();
    }

    /// Every session identifier the fixture minted.
    ///
    /// @return the identifiers in mint order, never null
    public List<String> mintedSessions() {
        return List.copyOf(sessions);
    }

    /// Blocks until the first `notifications/cancelled` arrives.
    ///
    /// The signal fires on arrival, before any delay set by
    /// [#readingCancellationsAfter(long)], so a caller that sent the
    /// notification without waiting for its answer can still observe it.
    ///
    /// @param timeout how long to wait
    /// @param unit the unit of `timeout`, not null
    /// @return true if a cancellation arrived within the timeout
    /// @throws InterruptedException if the waiting thread is interrupted
    public boolean awaitCancellation(long timeout, TimeUnit unit) throws InterruptedException {
        return cancellationArrived.await(timeout, unit);
    }

    // ------------------------------------------------------------- behaviours

    /// Answers the pre-2026-07-28 way: `initialize` first, no modern headers.
    ///
    /// @return this fixture, never null
    public FakeHttpMcpServer legacy() {
        this.legacy = true;
        return this;
    }

    /// Answers every request as an SSE stream rather than one JSON body.
    ///
    /// @return this fixture, never null
    public FakeHttpMcpServer streaming() {
        this.sse = true;
        return this;
    }

    /// Mints a session identifier on `initialize` and returns it as a header.
    ///
    /// @return this fixture, never null
    public FakeHttpMcpServer mintingSessions() {
        this.mintSessions = true;
        return this;
    }

    /// Marks every session minted so far stale, so the next request carrying
    /// one draws a `404`.
    ///
    /// @return this fixture, never null
    public FakeHttpMcpServer invalidateSessions() {
        staleThrough.set(sessions.size());
        return this;
    }

    /// Answers `404` to every session-carrying request, forever.
    ///
    /// Stands in for a server that will never accept whatever the client holds,
    /// which must fail the call rather than start a re-initialisation loop.
    ///
    /// @return this fixture, never null
    public FakeHttpMcpServer rejectingEverySession() {
        this.rejectEverySession = true;
        return this;
    }

    /// Answers the modern probe with `-32022` listing the given revisions.
    ///
    /// @param supported the revisions to advertise, not null
    /// @return this fixture, never null
    public FakeHttpMcpServer offeringVersions(List<String> supported) {
        this.unsupportedVersionOffer = List.copyOf(supported);
        return this;
    }

    /// Chooses the status a legacy server answers the modern probe with.
    ///
    /// @param status the HTTP status, typically 400, 404 or 405
    /// @return this fixture, never null
    public FakeHttpMcpServer probeStatus(int status) {
        this.legacyProbeStatus = status;
        return this;
    }

    /// Chooses the status `initialize` is answered with.
    ///
    /// @param status the HTTP status
    /// @return this fixture, never null
    public FakeHttpMcpServer initializeStatus(int status) {
        this.initializeStatus = status;
        return this;
    }

    /// Announces a protocol revision in the `initialize` result.
    ///
    /// @param revision the revision to announce, not null
    /// @return this fixture, never null
    public FakeHttpMcpServer announcing(String revision) {
        this.announcedRevision = revision;
        return this;
    }

    /// Declares, or withholds, a `tools` capability in the `initialize` result.
    ///
    /// @param declares whether the capability is advertised
    /// @return this fixture, never null
    public FakeHttpMcpServer declaringTools(boolean declares) {
        this.declaresToolsCapability = declares;
        return this;
    }

    /// Redirects every request to the given absolute location.
    ///
    /// @param location the `Location` header value, not null
    /// @return this fixture, never null
    public FakeHttpMcpServer redirectingTo(String location) {
        this.redirectLocation = location;
        return this;
    }

    /// Holds every `tools/call` for this long before answering.
    ///
    /// @param millis the delay in milliseconds
    /// @return this fixture, never null
    public FakeHttpMcpServer answeringAfter(long millis) {
        this.answerDelayMs = millis;
        return this;
    }

    /// Holds every cancellation notification for this long before answering.
    ///
    /// @param millis the delay in milliseconds
    /// @return this fixture, never null
    public FakeHttpMcpServer readingCancellationsAfter(long millis) {
        this.cancellationDelayMs = millis;
        return this;
    }

    /// Answers `tools/call` with an SSE frame marked `resultType: partial` and
    /// then closes, having never sent a complete one.
    ///
    /// @return this fixture, never null
    public FakeHttpMcpServer truncatingAfterPartial() {
        this.sse = true;
        this.truncate = true;
        return this;
    }

    /// Publishes this schema as the one tool's `inputSchema`.
    ///
    /// @param schema the JSON Schema to publish, not null
    /// @return this fixture, never null
    public FakeHttpMcpServer publishingSchema(Map<String, Object> schema) {
        this.toolSchema = Map.copyOf(schema);
        return this;
    }

    /// Answers `401` to every POST not carrying exactly `Bearer {token}`.
    ///
    /// @param token the only token accepted, not null
    /// @return this fixture, never null
    public FakeHttpMcpServer requiringBearer(String token) {
        this.requiredBearer = token;
        return this;
    }

    /// Publishes one more tool beside `read_file`.
    ///
    /// @param name the tool name, not null
    /// @param schema the tool's `inputSchema`, not null
    /// @return this fixture, never null
    public FakeHttpMcpServer publishingTool(String name, Map<String, Object> schema) {
        this.extraTools.put(name, Map.copyOf(schema));
        return this;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- handling

    private void handle(HttpExchange exchange) throws IOException {
        String body = read(exchange.getRequestBody());
        record(exchange, body);
        try (exchange) {
            dispatch(exchange, body);
        }
    }

    private void dispatch(HttpExchange exchange, String body) throws IOException {
        String httpMethod = exchange.getRequestMethod();
        if ("DELETE".equals(httpMethod) || "GET".equals(httpMethod)) {
            respond(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }

        if (requiredBearer != null
                && !("Bearer " + requiredBearer)
                        .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"fixture\"");
            respond(exchange, 401, "", "text/plain");
            return;
        }

        if (redirectLocation != null) {
            exchange.getResponseHeaders().add("Location", redirectLocation);
            respond(exchange, 302, "", "text/plain");
            return;
        }

        String id = jsonString(body, "id");
        String rpcMethod = jsonString(body, "method");

        if ("notifications/cancelled".equals(rpcMethod)) {
            cancellationArrived.countDown();
            sleep(cancellationDelayMs);
            respond(exchange, 202, "", "text/plain");
            return;
        }
        if ("notifications/initialized".equals(rpcMethod)) {
            respond(exchange, 202, "", "text/plain");
            return;
        }

        boolean modernShaped =
                exchange.getRequestHeaders().getFirst(McpProtocol.HEADER_PROTOCOL_VERSION) != null;

        if (legacy && modernShaped) {
            if (unsupportedVersionOffer != null) {
                respond(exchange, 400, unsupportedVersionError(id), "application/json");
                return;
            }
            // A server that predates the header answers with whatever its router
            // does for an unknown shape, which is not JSON-RPC.
            respond(exchange, legacyProbeStatus, "<html>Not Found</html>", "text/html");
            return;
        }
        if (!legacy && unsupportedVersionOffer != null) {
            respond(exchange, 400, unsupportedVersionError(id), "application/json");
            return;
        }

        if ("initialize".equals(rpcMethod)) {
            initializes.incrementAndGet();
            if (initializeStatus != 200) {
                respond(exchange, initializeStatus, "", "text/plain");
                return;
            }
            if (mintSessions) {
                String session = "session-" + sessionSeq.incrementAndGet();
                sessions.add(session);
                exchange.getResponseHeaders().add(McpProtocol.HEADER_SESSION_ID, session);
            }
            send(exchange, id, initializeResult());
            return;
        }

        String carried = exchange.getRequestHeaders().getFirst(McpProtocol.HEADER_SESSION_ID);
        if (carried != null && (rejectEverySession || isStale(carried))) {
            respond(exchange, 404, "", "text/plain");
            return;
        }

        if ("tools/list".equals(rpcMethod)) {
            send(exchange, id, toolsResult());
            return;
        }
        if ("tools/call".equals(rpcMethod)) {
            sleep(answerDelayMs);
            if (truncate) {
                respond(
                        exchange,
                        200,
                        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\""
                                + id
                                + "\",\"result\":{\"resultType\":\"partial\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"half\"}]}}\n\n",
                        "text/event-stream");
                return;
            }
            if (!legacy) {
                String mismatch = headerMismatch(exchange, body);
                if (mismatch != null) {
                    respond(exchange, 400, headerMismatchError(id, mismatch), "application/json");
                    return;
                }
            }
            send(exchange, id, callResult());
            return;
        }
        respond(exchange, 400, methodNotFound(id), "application/json");
    }

    /// A session is stale once {@link #invalidateSessions()} has passed it.
    private boolean isStale(String carried) {
        int index = sessions.indexOf(carried);
        return index < 0 || index < staleThrough.get();
    }

    // ---------------------------------------------------------------- payloads

    private String initializeResult() {
        String capabilities = declaresToolsCapability ? "{\"tools\":{}}" : "{}";
        return "{\"protocolVersion\":\""
                + announcedRevision
                + "\",\"capabilities\":"
                + capabilities
                + ",\"serverInfo\":{\"name\":\"fake-http\",\"version\":\"1\"}}";
    }

    private String toolsResult() {
        StringBuilder tools =
                new StringBuilder(
                        "{\"name\":\"read_file\",\"description\":\"Reads a file\","
                                + "\"inputSchema\":"
                                + json(toolSchema)
                                + "}");
        extraTools.forEach(
                (name, schema) ->
                        tools.append(",{\"name\":")
                                .append(json(name))
                                .append(",\"description\":\"extra\",\"inputSchema\":")
                                .append(json(schema))
                                .append('}'));
        return "{\"tools\":["
                + tools
                + "],\"_meta\":{\""
                + McpProtocol.META_SERVER_INFO
                + "\":{\"name\":\"fake-http\"}}}";
    }

    // ------------------------------------------------------ header validation

    /// Returns why a modern `tools/call`'s headers disagree with its body, or null.
    private String headerMismatch(HttpExchange exchange, String body) throws IOException {
        JsonNode params = MAPPER.readTree(body).path("params");
        String name = params.path("name").asText();
        String carriedName = decode(exchange.getRequestHeaders().getFirst(McpProtocol.HEADER_NAME));
        if (!name.equals(carriedName)) {
            return "Mcp-Name header '" + carriedName + "' does not match body value '" + name + "'";
        }
        Map<String, Object> schema =
                "read_file".equals(name) ? toolSchema : extraTools.getOrDefault(name, Map.of());
        List<String[]> annotated = new ArrayList<>();
        annotations(schema, "", annotated);
        for (String[] entry : annotated) {
            JsonNode value = params.path("arguments");
            for (String step : entry[1].split("\\.")) {
                value = value.path(step);
            }
            String carried = decode(exchange.getRequestHeaders().getFirst("Mcp-Param-" + entry[0]));
            boolean present = !value.isMissingNode() && !value.isNull();
            if (!present) {
                if (carried != null) {
                    return "Mcp-Param-" + entry[0] + " sent for an absent argument";
                }
                continue;
            }
            if (carried == null) {
                return "Mcp-Param-" + entry[0] + " is missing";
            }
            boolean equal =
                    value.isNumber()
                            ? isNumber(carried)
                                    && new BigDecimal(carried).compareTo(value.decimalValue()) == 0
                            : carried.equals(value.asText());
            if (!equal) {
                return "Mcp-Param-" + entry[0] + " '" + carried + "' does not match the body";
            }
        }
        return null;
    }

    /// Collects `{name, dotted path}` for every annotation reached through `properties`.
    private static void annotations(Map<?, ?> schema, String path, List<String[]> into) {
        if (!(schema.get("properties") instanceof Map<?, ?> properties)) {
            return;
        }
        properties.forEach(
                (key, child) -> {
                    if (child instanceof Map<?, ?> property) {
                        String here = path.isEmpty() ? key.toString() : path + "." + key;
                        if (property.get("x-mcp-header") instanceof String header) {
                            into.add(new String[] {header, here});
                        }
                        annotations(property, here, into);
                    }
                });
    }

    private static String decode(String header) {
        if (header != null && header.startsWith("=?base64?") && header.endsWith("?=")) {
            String encoded = header.substring("=?base64?".length(), header.length() - 2);
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        }
        return header;
    }

    private static boolean isNumber(String text) {
        try {
            new BigDecimal(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String headerMismatchError(String id, String reason) {
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + quoted(id)
                + ",\"error\":{\"code\":"
                + McpProtocol.HEADER_MISMATCH
                + ",\"message\":"
                + json("Header mismatch: " + reason)
                + "}}";
    }

    private String callResult() {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"file data over http\"}]}";
    }

    private String unsupportedVersionError(String id) {
        StringBuilder listed = new StringBuilder();
        for (String version : unsupportedVersionOffer) {
            if (!listed.isEmpty()) {
                listed.append(',');
            }
            listed.append('"').append(version).append('"');
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + quoted(id)
                + ",\"error\":{\"code\":"
                + McpProtocol.UNSUPPORTED_PROTOCOL_VERSION
                + ",\"message\":\"unsupported protocol version\",\"data\":{\"supported\":["
                + listed
                + "]}}}";
    }

    private String methodNotFound(String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + quoted(id)
                + ",\"error\":{\"code\":"
                + McpProtocol.METHOD_NOT_FOUND
                + ",\"message\":\"no such method\"}}";
    }

    private void send(HttpExchange exchange, String id, String result) throws IOException {
        String message =
                "{\"jsonrpc\":\"2.0\",\"id\":" + quoted(id) + ",\"result\":" + result + "}";
        if (sse) {
            respond(
                    exchange,
                    200,
                    ": keep-alive\nevent: message\ndata: " + message + "\n\n",
                    "text/event-stream");
            return;
        }
        respond(exchange, 200, message, "application/json");
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private void record(HttpExchange exchange, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders()
                .forEach(
                        (name, values) ->
                                headers.put(
                                        name.toLowerCase(Locale.ROOT),
                                        values.isEmpty() ? "" : values.getFirst()));
        requests.add(
                new Recorded(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        Map.copyOf(headers),
                        body));
    }

    private static String read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String quoted(String id) {
        return id == null ? "null" : "\"" + id + "\"";
    }

    /// Reads one top-level string field without binding the document.
    private static String jsonString(String body, String field) {
        String needle = "\"" + field + "\":";
        int at = body.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int from = at + needle.length();
        while (from < body.length() && body.charAt(from) == ' ') {
            from++;
        }
        if (from >= body.length() || body.charAt(from) != '"') {
            return null;
        }
        int to = body.indexOf('"', from + 1);
        return to < 0 ? null : body.substring(from + 1, to);
    }

    /// Renders a small schema map as JSON, enough for the shapes tests declare.
    private static String json(Object value) {
        return switch (value) {
            case null -> "null";
            case Map<?, ?> map -> {
                List<String> entries = new ArrayList<>();
                map.forEach((key, child) -> entries.add("\"" + key + "\":" + json(child)));
                yield "{" + String.join(",", entries) + "}";
            }
            case List<?> list -> {
                List<String> entries = new ArrayList<>();
                list.forEach(child -> entries.add(json(child)));
                yield "[" + String.join(",", entries) + "]";
            }
            case String text -> "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            default -> String.valueOf(value);
        };
    }
}
