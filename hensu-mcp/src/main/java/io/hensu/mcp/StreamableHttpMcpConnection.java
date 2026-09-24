package io.hensu.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/// MCP client speaking Streamable HTTP to a server somebody else runs.
///
/// Every exchange is a POST to one endpoint, answered either with a single JSON
/// message or with an SSE stream carrying one. There is no socket held between
/// calls, and no process to contain, which is why several decisions here differ
/// from the stdio client:
///
/// - **Containment is not available, so the bound is the allowlist – and a
///   credential is bounded more tightly still.** The set of hosts named across
///   the deployment's MCP declarations is what the run may reach. Redirects are
///   never followed by the JDK client – that is pinned explicitly – so a 3xx is
///   read and its `Location` checked by hand. The check is against **this
///   connection's own endpoint** – scheme, host and port – not against the
///   run's allowlist: every request carries this server's bearer token and
///   declared headers, so following a redirect to a host declared for a
///   *different* server, or to a different port on the same host, would hand one
///   server's credential to another. Both refusals are
///   {@link McpEgressDeniedException}; only a redirect that stays on the same
///   endpoint is followed, once.
/// - **The era is probed, not assumed.** Revision 2026-07-28 removed
///   `initialize`, `notifications/initialized` and session identifiers. A hosted
///   endpoint deployed today is overwhelmingly on the revision before it, and
///   one deployed next year will not be, so the connection tries a modern
///   request first and falls back to the handshake when the answer identifies an
///   older server. {@link McpEra} owns every difference between the two.
/// - **A timed-out call is a failed call, not a failed connection.** The stdio
///   client answers a missed write deadline by closing, because its framing
///   cannot survive a half-written line. Here the exchange is self-contained: the
///   response stream is closed, the correlation entry dropped, and the next call
///   to this server proceeds normally.
/// - **The catalog is whatever was listed at launch.** Revision 2026-07-28
///   replaced the standalone `GET` stream with a long-lived `subscriptions/listen`
///   response stream, which this client never opens. A server that adds a tool
///   mid-run is therefore not noticed until the next run – the same behaviour the
///   stdio client already has.
/// - **Annotated parameters travel twice.** In the modern era a tool parameter
///   marked `x-mcp-header` is repeated in an `Mcp-Param-{Name}` header on every
///   call, so a gateway can route on it without reading the body. A tool whose
///   annotations break the spec's constraints is left out of the catalog, and
///   the reason is kept for the operator. A server that answers `-32020`
///   (`HeaderMismatch`) has changed a schema since it was listed, so the
///   catalog is read again and the call retried once. {@link McpParameterHeaders}
///   owns the rules.
///
/// ### Asymmetry Worth Knowing
/// In the legacy era the `initialize` result is read before anything else is
/// sent, so an unsupported revision or a server advertising no tools is known
/// before a single `tools/list` goes out. The modern era has no exchange before
/// the first request, so there is no equivalent pre-check: the first request *is*
/// the probe, and a revision disagreement comes back as a JSON-RPC error on it.
///
/// ### Contracts
/// - **Precondition**: {@link #open} settles the era before returning, so a
///   connection handed to a caller is one whose protocol is already agreed
/// - **Postcondition**: {@link #close} releases the session, where the era has one
/// - **Invariant**: no request carrying this server's credential ever leaves
///   for a host other than the one its declaration named
///
/// @implNote **Mutable.** Holds the settled era, the legacy session identifier,
///     the primed catalog and each tool's header designations. Callers on many
///     Virtual Threads may share one connection: the session identifier is an `AtomicReference` and
///     re-initialisation is single-flight under a {@link ReentrantLock} whose
///     acquisition is itself timed, so a caller never waits past its own budget.
/// @see McpServerSpec.Http for the declaration this dials
/// @see McpEra for what differs between the revisions
/// @see McpResultRenderer for turning a call result into text an agent reads
public final class StreamableHttpMcpConnection implements McpConnection {

    private static final Logger logger =
            Logger.getLogger(StreamableHttpMcpConnection.class.getName());

    /// Wall clock a best-effort cancellation gets, in milliseconds.
    ///
    /// Deliberately not `requestTimeoutMs`: that budget is what an operator set
    /// for getting an answer, not for announcing that nobody wants one, and
    /// spending it twice would leave them two budgets away from being told the
    /// call failed.
    static final long CANCELLATION_TIMEOUT_MS = 2_000L;

    private static final String ACCEPT = "application/json, text/event-stream";

    private final McpServerSpec.Http spec;
    private final String bearerToken;
    private final Set<String> allowedHosts;
    private final HttpClient client;
    private final JsonRpc jsonRpc;
    private final ObjectMapper mapper;
    private final ExecutorService exchanges;
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private final ReentrantLock reinitialization = new ReentrantLock();
    private final AtomicReference<List<McpToolDescriptor>> primed = new AtomicReference<>();

    private final List<String> notices = new CopyOnWriteArrayList<>();

    private volatile McpEra era;
    private volatile String serverInfo;
    private volatile Map<String, McpParameterHeaders.Designation> designations = Map.of();
    private volatile boolean closed;

    private StreamableHttpMcpConnection(
            McpServerSpec.Http spec,
            String bearerToken,
            Set<String> allowedHosts,
            HttpClient client) {
        this.spec = spec;
        this.bearerToken = bearerToken;
        this.allowedHosts = allowedHosts;
        this.client = client;
        this.mapper = new ObjectMapper();
        this.jsonRpc = new JsonRpc(mapper);
        this.serverInfo = spec.host();
        this.exchanges =
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("mcp-http-" + spec.name() + "-", 0).factory());
    }

    /// Dials a remote server and settles which protocol revision it speaks.
    ///
    /// ### Contracts
    /// - **Precondition**: `bearerToken` is already resolved by the caller; this
    ///   module never learns where credentials are kept
    /// - **Postcondition**: the era is known and, on a modern server, the
    ///   catalog is already in hand
    ///
    /// @param spec the server declaration, not null
    /// @param bearerToken the resolved bearer token, or null when the endpoint
    ///     needs no authentication
    /// @param allowedHosts every host declared across the deployment's MCP
    ///     document, not null. This is the run's outer bound and is used to tell
    ///     an operator *which kind* of refusal they are reading; the bound that
    ///     actually governs a redirect is this connection's own endpoint, because
    ///     every request carries this server's credential
    /// @param client the HTTP client to send on, not null; callers that do not
    ///     have one should use {@link #defaultClient()}
    /// @return a connection whose era is settled, never null
    /// @throws McpException if the endpoint speaks neither era, announces a
    ///     revision Hensu does not implement, or cannot be reached within
    ///     {@link McpServerSpec#startupTimeoutMs()}
    /// @throws NullPointerException if spec, allowedHosts or client is null
    public static StreamableHttpMcpConnection open(
            McpServerSpec.Http spec,
            String bearerToken,
            Set<String> allowedHosts,
            HttpClient client) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(allowedHosts, "allowedHosts must not be null");
        Objects.requireNonNull(client, "client must not be null");

        Set<String> hosts =
                allowedHosts.stream()
                        .map(host -> host.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        StreamableHttpMcpConnection connection =
                new StreamableHttpMcpConnection(spec, bearerToken, hosts, client);
        try {
            connection.settleEra();
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /// Builds the client this transport expects when the caller has none.
    ///
    /// Redirects are pinned to `NEVER` rather than left to the default. They are
    /// already the default, and pinning them is the point: the allowlist is
    /// enforced by reading a 3xx's `Location` and deciding, which only works
    /// while 3xx responses are handed back instead of followed. A later
    /// convenience edit would otherwise turn that check into dead code silently.
    ///
    /// @return a client on virtual threads that follows no redirects, never null
    public static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(
                        Executors.newThreadPerTaskExecutor(
                                Thread.ofVirtual().name("mcp-http-io-", 0).factory()))
                .build();
    }

    /// Lists the tools the server exposes.
    ///
    /// The first call answers with the catalog the era probe already fetched,
    /// because the probe is a real `tools/list` and asking twice would cost a
    /// round trip to learn nothing.
    ///
    /// @return the server's catalog, never null (may be empty)
    /// @throws McpException if the server does not answer within its request timeout
    @Override
    public List<McpToolDescriptor> listTools() {
        List<McpToolDescriptor> fromProbe = primed.getAndSet(null);
        if (fromProbe != null) {
            return fromProbe;
        }
        return toDescriptors(call("tools/list", Map.of(), null, spec.requestTimeoutMs()));
    }

    /// Invokes a tool on the server.
    ///
    /// @param toolName the tool the agent chose, not null
    /// @param arguments the agent's arguments, not null (may be empty)
    /// @return the raw MCP result, for {@link McpResultRenderer} to render, never null
    /// @throws McpException if the server does not answer within its request
    ///     timeout, or the tool's header annotations are invalid
    /// @throws McpEgressDeniedException if the server redirects the call off the allowlist
    /// @throws McpInvalidArgumentException if an argument the server mirrors into
    ///     a header has no header form; nothing is sent
    @Override
    public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (designations.get(toolName) instanceof McpParameterHeaders.Rejected rejected) {
            // Reachable only when a re-listing found the schema broken after the
            // tool was already offered: a tool rejected at launch is never offered.
            throw new McpException(rejectionNotice(toolName, rejected));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        return call("tools/call", params, toolName, spec.requestTimeoutMs());
    }

    /// Returns the endpoint every request is posted to.
    ///
    /// @return the declared URL, never null
    @Override
    public String getEndpoint() {
        return spec.url().toString();
    }

    /// Returns whether this connection may still be used.
    ///
    /// There is no socket held between calls, so this answers whether the
    /// connection has been closed rather than whether the server is reachable –
    /// which only a request can establish.
    ///
    /// @return true until {@link #close} runs
    @Override
    public boolean isConnected() {
        return !closed;
    }

    /// Releases the session, where the era has one, and stops accepting calls.
    ///
    /// @apiNote **Side effects**: in the legacy era with a session identifier,
    ///     issues one `DELETE`. A `405` to it is the documented answer of a server
    ///     that mints no sessions and is not treated as an error.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        String session = sessionId.getAndSet(null);
        if (session != null) {
            releaseSession(session);
        }
        exchanges.shutdownNow();
    }

    /// Returns the declaration this connection was opened from.
    ///
    /// @return the server declaration, never null
    public McpServerSpec.Http spec() {
        return spec;
    }

    /// Returns who is on the other end, for an operator about to approve disclosure.
    ///
    /// @return the server's announced name, or the endpoint host when it
    ///     announced none, never null
    public String serverInfo() {
        return serverInfo;
    }

    /// Returns why tools this server knows of are not in its catalog.
    ///
    /// Two causes land here: a legacy server that advertised no tools capability,
    /// and a tool left out because its `x-mcp-header` annotations are invalid.
    ///
    /// @return the reasons, in the order they were found, never null (may be empty)
    @Override
    public List<String> catalogNotices() {
        return List.copyOf(notices);
    }

    /// Returns the protocol revision this connection settled on.
    ///
    /// @return the revision string, never null
    public String revision() {
        return era.revision();
    }

    // ------------------------------------------------------------- era probing

    /// Sends one modern `tools/list` and reads the answer as an era decision.
    private void settleEra() {
        Reply reply = probeModern();

        if (reply.status() == 200) {
            adoptModern(reply);
            return;
        }
        refuseOnAccessDenied(reply, "tools/list");

        JsonNode error = jsonRpcError(reply.body());
        if (error != null) {
            int code = error.path("code").asInt();
            if (code == McpProtocol.UNSUPPORTED_PROTOCOL_VERSION) {
                retryAtCommonRevision(error);
                return;
            }
            if (code == McpProtocol.HEADER_MISMATCH || code == McpProtocol.METHOD_NOT_FOUND) {
                // A modern server rejecting our request shape is our defect, not a
                // signal to fall back: falling back would hide it behind a second
                // failure in a different dialect.
                throw new McpException(
                        "MCP server '"
                                + spec.name()
                                + "' at "
                                + spec.url()
                                + " refused the request: "
                                + error.path("message").asText("JSON-RPC error " + code));
            }
        }

        if (reply.status() == 400 || reply.status() == 404 || reply.status() == 405) {
            settleLegacy();
            return;
        }

        throw new McpException(
                "MCP server '"
                        + spec.name()
                        + "' at "
                        + spec.url()
                        + " answered HTTP "
                        + reply.status()
                        + " to tools/list");
    }

    private Reply probeModern() {
        McpEra probe = new McpEra.Modern();
        Reply reply =
                exchange(
                        probe,
                        nextRequestId(),
                        "tools/list",
                        null,
                        Map.of(),
                        null,
                        spec.startupTimeoutMs());
        if (reply.status() == 200) {
            era = probe;
        }
        return reply;
    }

    private void retryAtCommonRevision(JsonNode error) {
        List<String> offered = new ArrayList<>();
        for (JsonNode listed : error.path("data").path("supported")) {
            offered.add(listed.asText());
        }
        Optional<String> common = McpProtocol.newestCommon(offered);
        if (common.isEmpty()) {
            throw new McpException(
                    "MCP server '"
                            + spec.name()
                            + "' at "
                            + spec.url()
                            + " implements "
                            + (offered.isEmpty() ? "<none listed>" : String.join(", ", offered))
                            + "; Hensu implements "
                            + String.join(", ", McpProtocol.SUPPORTED)
                            + ", so there is no revision both sides speak");
        }
        if (McpProtocol.LEGACY_REVISION.equals(common.get())) {
            settleLegacy();
            return;
        }
        // Hensu implements exactly one modern revision, so the only way to land
        // here is a server that listed the revision it has just rejected. Retrying
        // would send the identical request a second time.
        throw new McpException(
                "MCP server '"
                        + spec.name()
                        + "' at "
                        + spec.url()
                        + " rejected protocol revision "
                        + common.get()
                        + " after listing it as supported");
    }

    private void adoptModern(Reply reply) {
        era = new McpEra.Modern();
        Map<String, Object> result = jsonRpc.parseResult(reply.body());
        readModernServerInfo(reply.body());
        primed.set(toDescriptors(result));
    }

    /// Completes the `initialize` handshake of the revision before 2026-07-28.
    private void settleLegacy() {
        McpEra legacy = new McpEra.Legacy();
        era = legacy;

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", McpProtocol.LEGACY_REVISION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", McpProtocol.CLIENT_INFO);

        String id = nextRequestId();
        Reply reply =
                exchange(legacy, id, "initialize", null, params, null, spec.startupTimeoutMs());

        if (reply.status() == 404 || reply.status() == 405) {
            // No GET is ever issued: the ladder that would follow one leads to the
            // deprecated two-endpoint HTTP+SSE transport, which Hensu does not
            // implement and this ticket deliberately declines to add.
            throw new McpException(
                    "MCP server '"
                            + spec.name()
                            + "' at "
                            + spec.url()
                            + " speaks neither revision of Streamable HTTP (HTTP "
                            + reply.status()
                            + " to both a modern request and initialize). The endpoint may speak"
                            + " the deprecated two-endpoint HTTP+SSE transport, which Hensu does"
                            + " not implement.");
        }
        refuseOnAccessDenied(reply, "initialize");
        if (reply.status() != 200) {
            throw new McpException(
                    "MCP server '"
                            + spec.name()
                            + "' at "
                            + spec.url()
                            + " answered HTTP "
                            + reply.status()
                            + " to initialize");
        }

        sessionId.set(reply.header(McpProtocol.HEADER_SESSION_ID));

        Map<String, Object> result = jsonRpc.parseResult(reply.body());
        McpProtocol.Handshake announced = McpProtocol.read(result);
        if (!McpProtocol.isSupported(announced.revision())) {
            throw new McpException(
                    McpProtocol.unsupportedRevision(spec.name(), announced.revision()));
        }
        if (announced.serverName() != null) {
            serverInfo = announced.serverName();
        }

        notification(legacy, "notifications/initialized", Map.of(), spec.startupTimeoutMs());

        if (!announced.declaresTools()) {
            notice(
                    "MCP server '"
                            + spec.name()
                            + "' advertises no tools capability, so none were requested");
            primed.set(List.of());
            return;
        }

        String listId = nextRequestId();
        Reply tools =
                exchange(
                        legacy,
                        listId,
                        "tools/list",
                        null,
                        Map.of(),
                        sessionId.get(),
                        spec.startupTimeoutMs());
        primed.set(toDescriptors(readResult(tools, "tools/list")));
    }

    // -------------------------------------------------------------- requesting

    /// Sends one request, re-initialising once if a held session was rejected.
    private Map<String, Object> call(
            String method, Map<String, Object> params, String targetName, long budgetMs) {
        if (closed) {
            throw new McpException("MCP server '" + spec.name() + "' connection is closed");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        String carried = sessionId.get();
        Reply reply = exchange(era, nextRequestId(), method, targetName, params, carried, budgetMs);

        if (reply.status() == 404 && era.sessionScoped() && carried != null) {
            refreshSession(carried, remaining(deadline));
            reply =
                    exchange(
                            era,
                            nextRequestId(),
                            method,
                            targetName,
                            params,
                            sessionId.get(),
                            remaining(deadline));
        }

        if (targetName != null && isHeaderMismatch(reply) && relistChanged(targetName, deadline)) {
            reply =
                    exchange(
                            era,
                            nextRequestId(),
                            method,
                            targetName,
                            params,
                            sessionId.get(),
                            remaining(deadline));
        }
        return readResult(reply, method);
    }

    private boolean isHeaderMismatch(Reply reply) {
        if (reply.status() != 400 || !era.mirrorsParameters()) {
            return false;
        }
        JsonNode error = jsonRpcError(reply.body());
        return error != null && error.path("code").asInt() == McpProtocol.HEADER_MISMATCH;
    }

    /// Reads the catalog again after a `-32020`, and says whether a retry could differ.
    ///
    /// The spec's prescribed answer to a header mismatch is to re-list and retry
    /// with the headers the current schema asks for. Retrying with the same
    /// designation would send the identical request a second time, so the
    /// retry happens only when the tool's designation actually changed.
    ///
    /// @throws McpException if the tool is no longer published, or its new
    ///     annotations are invalid
    private boolean relistChanged(String toolName, long deadline) {
        McpParameterHeaders.Designation before = designations.get(toolName);
        toDescriptors(
                readResult(
                        exchange(
                                era,
                                nextRequestId(),
                                "tools/list",
                                null,
                                Map.of(),
                                sessionId.get(),
                                remaining(deadline)),
                        "tools/list"));
        McpParameterHeaders.Designation after = designations.get(toolName);
        return switch (after) {
            case null ->
                    throw new McpException(
                            "MCP server '"
                                    + spec.name()
                                    + "' rejected the headers of '"
                                    + toolName
                                    + "' and no longer publishes it");
            case McpParameterHeaders.Rejected rejected ->
                    throw new McpException(rejectionNotice(toolName, rejected));
            case McpParameterHeaders.Accepted accepted -> !accepted.equals(before);
        };
    }

    /// Re-initialises a rejected session once, for whichever caller gets there first.
    ///
    /// Left unguarded this is a read-modify-write across two round trips: every
    /// concurrent caller that saw `404` would post its own `initialize`, the
    /// server would mint a session per caller, and all but the last would leak
    /// because nothing issues a `DELETE` for a session the connection has already
    /// forgotten – while each still-in-flight request drew its own `404`, feeding
    /// the storm. The lock is explicit rather than `synchronized` because it is
    /// held across two round trips and its acquisition has to be timed against
    /// what remains of the caller's budget.
    private void refreshSession(String carried, long budgetMs) {
        if (budgetMs <= 0) {
            throw timeout("session refresh", budgetMs);
        }
        boolean held;
        try {
            held = reinitialization.tryLock(budgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(
                    "Interrupted while re-initialising MCP server '" + spec.name() + "'", e);
        }
        if (!held) {
            throw timeout("session refresh", budgetMs);
        }
        try {
            if (!carried.equals(sessionId.get())) {
                // Somebody else already refreshed it. Reading the new identifier and
                // retrying is the ordinary outcome when several requests were in
                // flight together.
                return;
            }
            sessionId.set(null);
            Reply reply =
                    exchange(
                            era,
                            nextRequestId(),
                            "initialize",
                            null,
                            initializeParams(),
                            null,
                            budgetMs);
            if (reply.status() != 200) {
                throw new McpException(
                        "MCP server '"
                                + spec.name()
                                + "' rejected re-initialisation with HTTP "
                                + reply.status());
            }
            sessionId.set(reply.header(McpProtocol.HEADER_SESSION_ID));
            notification(era, "notifications/initialized", Map.of(), budgetMs);
        } finally {
            reinitialization.unlock();
        }
    }

    private Map<String, Object> initializeParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", McpProtocol.LEGACY_REVISION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", McpProtocol.CLIENT_INFO);
        return params;
    }

    private Map<String, Object> readResult(Reply reply, String method) {
        refuseOnAccessDenied(reply, method);
        if (reply.status() != 200) {
            throw new McpException(
                    "MCP request '"
                            + method
                            + "' to server '"
                            + spec.name()
                            + "' answered HTTP "
                            + reply.status()
                            + (reply.body().isBlank() ? "" : ": " + reply.body()));
        }
        readModernServerInfo(reply.body());
        return jsonRpc.parseResult(reply.body());
    }

    /// Fails with a sentence about the credential when the server refused it.
    ///
    /// A `401` or `403` is an answer, not an outage: the endpoint was reached and
    /// declined who was asking. Reporting it as a transport failure sends the
    /// operator to debug the network when the fix is in the credential store.
    /// Whether a token was sent decides which of the two fixes applies, and a
    /// `401` to a request that carried none is also how a server that requires an
    /// authorization flow answers – which this client does not run.
    ///
    /// @throws McpException when the status is 401 or 403
    private void refuseOnAccessDenied(Reply reply, String method) {
        int status = reply.status();
        if (status != 401 && status != 403) {
            return;
        }
        boolean sent = bearerToken != null && !bearerToken.isBlank();
        String prefix =
                "MCP server '"
                        + spec.name()
                        + "' at "
                        + spec.url()
                        + " answered HTTP "
                        + status
                        + " to "
                        + method
                        + ": ";
        if (status == 403) {
            throw new McpException(
                    prefix
                            + (sent
                                    ? "the bearer token was accepted as a credential but is not"
                                            + " permitted to do this"
                                    : "the server refuses unauthenticated access"));
        }
        throw new McpException(
                prefix
                        + (sent
                                ? "the server rejected the bearer token. Check the value stored"
                                        + " under the credential key its declaration names"
                                : "the server requires authorization and none was sent. Hensu"
                                        + " sends a static bearer token from the credential store"
                                        + " and runs no OAuth flow; if this server issues static"
                                        + " tokens, declare one with auth:"));
    }

    private void readModernServerInfo(String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        try {
            JsonNode meta = mapper.readTree(body).path("result").path("_meta");
            JsonNode name = meta.path(McpProtocol.META_SERVER_INFO).path("name");
            if (name.isTextual()) {
                serverInfo = name.asText();
            }
        } catch (IOException e) {
            logger.log(Level.FINE, "Could not read serverInfo from MCP response", e);
        }
    }

    // ----------------------------------------------------------------- transport

    /// Performs one bounded exchange, following at most one in-allowlist redirect.
    private Reply exchange(
            McpEra using,
            String requestId,
            String method,
            String targetName,
            Map<String, Object> params,
            String session,
            long budgetMs) {
        if (budgetMs <= 0) {
            throw timeout(method, budgetMs);
        }
        String body = jsonRpc.createRequest(requestId, method, using.params(params));
        Map<String, String> mirrored = mirroredHeaders(using, targetName, params);
        Reply reply =
                post(using, requestId, method, targetName, mirrored, body, session, budgetMs, true);
        if (reply.status() / 100 == 3) {
            URI moved = redirectTarget(reply);
            reply =
                    post(
                            using,
                            requestId,
                            method,
                            targetName,
                            mirrored,
                            body,
                            session,
                            budgetMs,
                            true,
                            moved);
        }
        return reply;
    }

    /// Derives a `tools/call`'s `Mcp-Param-*` headers from the same arguments
    /// the body carries, so the two cannot disagree.
    private Map<String, String> mirroredHeaders(
            McpEra using, String targetName, Map<String, Object> params) {
        if (targetName == null
                || !using.mirrorsParameters()
                || !(designations.get(targetName) instanceof McpParameterHeaders.Accepted accepted)
                || !(params.get("arguments") instanceof Map<?, ?> arguments)) {
            return Map.of();
        }
        return McpParameterHeaders.headers(accepted.mirrors(), castArguments(arguments));
    }

    private static Map<String, Object> castArguments(Map<?, ?> arguments) {
        return (Map<String, Object>) arguments;
    }

    private void notification(
            McpEra using, String method, Map<String, Object> params, long budgetMs) {
        String body = jsonRpc.createNotification(method, using.params(params));
        try {
            post(using, null, method, null, Map.of(), body, sessionId.get(), budgetMs, false);
        } catch (McpException e) {
            // A notification nobody read changes nothing the caller can act on.
            logger.log(
                    Level.FINE,
                    "MCP notification '" + method + "' to '" + spec.name() + "' was not delivered",
                    e);
        }
    }

    private Reply post(
            McpEra using,
            String requestId,
            String method,
            String targetName,
            Map<String, String> mirrored,
            String body,
            String session,
            long budgetMs,
            boolean awaitMessage) {
        return post(
                using,
                requestId,
                method,
                targetName,
                mirrored,
                body,
                session,
                budgetMs,
                awaitMessage,
                spec.url());
    }

    private Reply post(
            McpEra using,
            String requestId,
            String method,
            String targetName,
            Map<String, String> mirrored,
            String body,
            String session,
            long budgetMs,
            boolean awaitMessage,
            URI target) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(target)
                        .header("Content-Type", "application/json")
                        .header("Accept", ACCEPT)
                        .timeout(Duration.ofMillis(budgetMs))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        applyCommonHeaders(request);
        using.applyHeaders(request, method, targetName, session, mirrored);

        BodyHandoff live = new BodyHandoff();
        CompletableFuture<Reply> pending =
                CompletableFuture.supplyAsync(
                                () -> read(request.build(), requestId, awaitMessage, live),
                                exchanges)
                        .orTimeout(budgetMs, TimeUnit.MILLISECONDS);
        try {
            return pending.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException || isRequestTimeout(e.getCause())) {
                // Closing the response stream is itself the cancellation in the
                // modern era; the legacy era says so out of band instead.
                live.abandon();
                if (requestId != null) {
                    using.cancel(requestId, this::announceCancellation);
                }
                throw timeout(method, budgetMs);
            }
            throw e.getCause() instanceof McpException mcp
                    ? mcp
                    : new McpException(
                            "MCP request '"
                                    + method
                                    + "' to server '"
                                    + spec.name()
                                    + "' failed: "
                                    + e.getCause().getMessage(),
                            e.getCause());
        } finally {
            live.abandon();
        }
    }

    private void applyCommonHeaders(HttpRequest.Builder request) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        spec.headers().forEach(request::header);
    }

    private Reply read(
            HttpRequest request, String requestId, boolean awaitMessage, BodyHandoff live) {
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new McpException(
                    "Could not reach MCP server '" + spec.name() + "' at " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted while calling MCP server '" + spec.name() + "'", e);
        }
        live.offer(response.body());
        String contentType =
                response.headers()
                        .firstValue("content-type")
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .orElse("");
        String body =
                contentType.startsWith("text/event-stream")
                        ? frame(response.body(), requestId, awaitMessage)
                        : drain(response.body());
        return new Reply(response.statusCode(), response.headers(), body);
    }

    /// Reads an SSE body until the message this request is waiting for arrives.
    ///
    /// Frames are `data:` lines accumulated until a blank line. Comment lines
    /// beginning with `:` and the `event:` and `id:` fields carry nothing this
    /// client needs. Notifications and messages correlated to another request are
    /// skipped. A result whose `resultType` is absent is `"complete"`, which the
    /// spec requires of a client talking to an earlier-protocol server.
    private String frame(InputStream body, String requestId, boolean awaitMessage) {
        if (!awaitMessage) {
            return drain(body);
        }
        StringBuilder data = new StringBuilder();
        String candidate = "";
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    String message = data.toString();
                    data.setLength(0);
                    if (message.isBlank()) {
                        continue;
                    }
                    if (!matchesRequest(message, requestId)) {
                        continue;
                    }
                    candidate = message;
                    if (isComplete(message)) {
                        return candidate;
                    }
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).stripLeading());
                }
            }
        } catch (IOException e) {
            throw truncated(candidate, e);
        }
        // Falling out of the loop means end of stream. An empty candidate is left
        // to the caller, which has a status code and can say something better
        // than "stream ended". A candidate that arrived but never said
        // `resultType: complete` is a different thing: the server began an answer
        // and did not finish it, and returning the fragment would report a
        // truncated call as a successful one.
        if (!candidate.isEmpty()) {
            throw truncated(candidate, null);
        }
        return candidate;
    }

    /// Fails a call whose answer began and never completed.
    private McpException truncated(String candidate, IOException cause) {
        String message =
                "MCP server '"
                        + spec.name()
                        + "' closed its response stream before the result was complete";
        if (candidate.isEmpty() && cause != null) {
            return new McpException(
                    "MCP server '" + spec.name() + "' closed its response stream early", cause);
        }
        return cause == null ? new McpException(message) : new McpException(message, cause);
    }

    private boolean matchesRequest(String message, String requestId) {
        String id = jsonRpc.extractId(message);
        return id != null && id.equals(requestId);
    }

    private boolean isComplete(String message) {
        try {
            JsonNode resultType = mapper.readTree(message).path("result").path("resultType");
            return !resultType.isTextual() || "complete".equals(resultType.asText());
        } catch (IOException e) {
            return true;
        }
    }

    private static String drain(InputStream body) {
        try (InputStream stream = body) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // -------------------------------------------------------------- egress bound

    /// Resolves a redirect and refuses one that would leave the declared hosts.
    private URI redirectTarget(Reply reply) {
        String location = reply.header("location");
        if (location == null || location.isBlank()) {
            throw new McpException(
                    "MCP server '"
                            + spec.name()
                            + "' answered HTTP "
                            + reply.status()
                            + " with no Location header");
        }
        URI moved;
        try {
            moved = spec.url().resolve(new URI(location));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new McpException(
                    "MCP server '"
                            + spec.name()
                            + "' redirected to an unusable location '"
                            + location
                            + "'",
                    e);
        }
        String host = moved.getHost() == null ? "" : moved.getHost().toLowerCase(Locale.ROOT);
        if (!sameEndpoint(moved)) {
            // Being declared somewhere in mcp.yaml is not permission to receive
            // *this* server's credential. The allowlist bounds the run; this
            // connection's own endpoint bounds what its bearer token may reach.
            throw new McpEgressDeniedException(
                    "MCP server '"
                            + spec.name()
                            + "' redirected to "
                            + moved
                            + ", which is not "
                            + authority(spec.url())
                            + ". "
                            + (allowedHosts.contains(host)
                                    ? "That host is declared in mcp.yaml for a different server,"
                                            + " and following the redirect would send this"
                                            + " server's credential and headers to it."
                                    : "That host is declared in mcp.yaml for no server at all;"
                                            + " declared hosts are "
                                            + String.join(", ", allowedHosts)
                                            + "."));
        }
        return moved;
    }

    /// Returns whether a redirect target is the same server this connection dials.
    ///
    /// Scheme, host **and port** all have to match. Host alone is not enough:
    /// two servers on one host at different ports is the ordinary local case,
    /// and they are as separate as two hosts are — a token minted for one is
    /// not a token for the other. Nor is a downgrade from `https` to `http` a
    /// redirect this client follows.
    private boolean sameEndpoint(URI moved) {
        return authority(moved).equals(authority(spec.url()));
    }

    /// Renders scheme, host and effective port, so a default port compares equal
    /// to the same port written out.
    private static String authority(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
        return scheme + "://" + host + ":" + port;
    }

    // ------------------------------------------------------------- housekeeping

    /// Tells the server nobody wants an answer, without making the caller wait.
    ///
    /// The notification is dispatched and forgotten. Joining it would charge the
    /// caller {@link #CANCELLATION_TIMEOUT_MS} *on top of* the budget that has
    /// already expired — which is the outcome that budget exists to prevent: an
    /// operator who set a one-second timeout would be told three seconds later.
    /// The budget still bounds the detached request, so a server that never
    /// reads it costs one short-lived Virtual Thread rather than a leak.
    private void announceCancellation(String requestId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", requestId);
        params.put("reason", "the caller's request budget expired");
        McpEra settled = era;
        try {
            exchanges.execute(
                    () ->
                            notification(
                                    settled,
                                    "notifications/cancelled",
                                    params,
                                    CANCELLATION_TIMEOUT_MS));
        } catch (RejectedExecutionException e) {
            // The connection closed underneath us. There is nothing left to tell
            // the server that closing the stream has not already said.
            logger.log(Level.FINE, "Cancellation for '" + spec.name() + "' was not dispatched", e);
        }
    }

    private void releaseSession(String session) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(spec.url())
                        .header("Accept", ACCEPT)
                        .header(McpProtocol.HEADER_SESSION_ID, session)
                        .timeout(Duration.ofMillis(CANCELLATION_TIMEOUT_MS))
                        .DELETE();
        applyCommonHeaders(request);
        try {
            // A 405 is the documented answer of a server that mints no sessions,
            // and any other failure here costs the run nothing: the connection is
            // already closed.
            client.send(request.build(), BodyHandlers.ofString());
        } catch (IOException e) {
            logger.log(Level.FINE, "Could not release MCP session for '" + spec.name() + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String nextRequestId() {
        return String.valueOf(nextId.incrementAndGet());
    }

    private static long remaining(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    /// Whether the request's own timer, rather than `orTimeout`, ended the call.
    ///
    /// Both timers carry the same budget, so either can fire first. The request
    /// timer's expiry is the same event and must take the same path – reported
    /// as a timeout and, in the legacy era, announced as a cancellation – rather
    /// than as an unreachable server. A connect timeout is excluded: nothing was
    /// sent, so the server genuinely could not be reached.
    private static boolean isRequestTimeout(Throwable cause) {
        return cause instanceof McpException
                && cause.getCause() instanceof HttpTimeoutException expired
                && !(expired instanceof HttpConnectTimeoutException);
    }

    private McpException timeout(String method, long budgetMs) {
        return new McpException(
                "MCP request '"
                        + method
                        + "' to server '"
                        + spec.name()
                        + "' timed out after "
                        + budgetMs
                        + "ms");
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            logger.log(Level.FINE, "Could not close MCP response stream", e);
        }
    }

    /// Reads a `tools/list` result, leaving out tools whose header annotations
    /// the settled era refuses.
    ///
    /// Also replaces the designations {@link #callTool} mirrors from, so a
    /// re-listing after a `-32020` takes effect on the very next request.
    private List<McpToolDescriptor> toDescriptors(Map<String, Object> result) {
        if (!(result.get("tools") instanceof List<?> published)) {
            designations = Map.of();
            return List.of();
        }
        boolean mirrors = era.mirrorsParameters();
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        Map<String, McpParameterHeaders.Designation> read = new LinkedHashMap<>();
        for (Object entry : published) {
            if (!(entry instanceof Map<?, ?> tool && tool.get("name") instanceof String name)) {
                continue;
            }
            Map<String, Object> schema =
                    tool.get("inputSchema") instanceof Map<?, ?> declared
                            ? castSchema(declared)
                            : Map.of();
            if (mirrors) {
                McpParameterHeaders.Designation designation = McpParameterHeaders.read(schema);
                read.put(name, designation);
                if (designation instanceof McpParameterHeaders.Rejected rejected) {
                    notice(rejectionNotice(name, rejected));
                    continue;
                }
            }
            descriptors.add(
                    new McpToolDescriptor(
                            name,
                            tool.get("description") instanceof String description
                                    ? description
                                    : "",
                            schema));
        }
        designations = Map.copyOf(read);
        return List.copyOf(descriptors);
    }

    private String rejectionNotice(String toolName, McpParameterHeaders.Rejected rejected) {
        return "MCP server '"
                + spec.name()
                + "' publishes '"
                + toolName
                + "' with an invalid x-mcp-header annotation, so it is not offered to agents: "
                + rejected.reason();
    }

    /// Records an operator-facing reason once, however many listings find it.
    ///
    /// Logged at `FINE` only: the runtime that adopted this connection reads
    /// {@link #catalogNotices()} and reports it where its operator looks, and a
    /// warning here as well would say everything twice.
    private void notice(String message) {
        if (!notices.contains(message)) {
            logger.fine(message);
            notices.add(message);
        }
    }

    private static Map<String, Object> castSchema(Map<?, ?> schema) {
        return (Map<String, Object>) schema;
    }

    /// Hands the live response body from the sending thread to the caller.
    ///
    /// `orTimeout` completes the *dependent* future; the task on
    /// {@link #exchanges} keeps running, so a body may arrive after the caller
    /// has already given up on it. A plain reference loses that one: the caller
    /// clears it, the sender then writes a stream nobody will ever close, and
    /// the connection behind it leaks for the life of the run. Abandonment is
    /// therefore sticky – a body offered after it closes immediately.
    ///
    /// @implNote **Mutable.** Guarded by an explicit {@link ReentrantLock} rather
    ///     than `synchronized`, for the reason the enclosing class gives: the
    ///     critical section performs a socket `close()`.
    private static final class BodyHandoff {

        private final ReentrantLock guard = new ReentrantLock();
        private InputStream body;
        private boolean abandoned;

        /// Publishes the body the sender just received.
        ///
        /// @param stream the live response body, not null
        void offer(InputStream stream) {
            guard.lock();
            try {
                if (abandoned) {
                    closeQuietly(stream);
                    return;
                }
                body = stream;
            } finally {
                guard.unlock();
            }
        }

        /// Closes whatever is held and refuses anything offered later.
        void abandon() {
            guard.lock();
            try {
                abandoned = true;
                closeQuietly(body);
                body = null;
            } finally {
                guard.unlock();
            }
        }
    }

    /// One HTTP answer, already reduced to the JSON-RPC message it carried.
    private record Reply(int status, java.net.http.HttpHeaders headers, String body) {

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    private JsonNode jsonRpcError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode error = mapper.readTree(body).path("error");
            return error.isObject() && error.has("code") ? error : null;
        } catch (IOException e) {
            return null;
        }
    }
}
