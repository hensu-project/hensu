package io.hensu.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
///     the primed catalog and the latest listing. Callers on many
///     Virtual Threads may share one connection: the session identifier is an `AtomicReference` and
///     re-initialisation is single-flight under a {@link ReentrantLock} whose
///     acquisition is itself timed, so a caller never waits past its own budget.
/// @see McpServerSpec.Http for the declaration this dials
/// @see McpEra for what differs between the revisions
/// @see McpResultRenderer for turning a call result into text an agent reads
public final class StreamableHttpMcpConnection implements McpConnection {

    private static final Logger logger =
            Logger.getLogger(StreamableHttpMcpConnection.class.getName());

    private final McpServerSpec.Http spec;
    private final JsonRpc jsonRpc;
    private final ObjectMapper mapper;
    private final HttpExchanger exchanger;
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private final ReentrantLock reinitialization = new ReentrantLock();
    private final AtomicReference<List<McpToolDescriptor>> primed = new AtomicReference<>();

    private final List<String> notices = new CopyOnWriteArrayList<>();

    private volatile McpEra era;
    private volatile String serverInfo;
    private volatile ListedTools listed = ListedTools.NONE;
    private volatile boolean closed;

    private StreamableHttpMcpConnection(
            McpServerSpec.Http spec,
            String bearerToken,
            Set<String> allowedHosts,
            HttpClient client) {
        this.spec = spec;
        this.mapper = new ObjectMapper();
        this.jsonRpc = new JsonRpc(mapper);
        this.exchanger = new HttpExchanger(spec, bearerToken, allowedHosts, client, jsonRpc);
        this.serverInfo = spec.host();
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

        StreamableHttpMcpConnection connection =
                new StreamableHttpMcpConnection(spec, bearerToken, allowedHosts, client);
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
        if (listed.designations().get(toolName) instanceof McpParameterHeaders.Rejected rejected) {
            // Reachable only when a re-listing found the schema broken after the
            // tool was already offered: a tool rejected at launch is never offered.
            throw new McpException(ListedTools.rejectionNotice(spec.name(), toolName, rejected));
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
            exchanger.release(session);
        }
        exchanger.close();
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
    @Override
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
        HttpReply reply = probeModern();

        if (reply.status() == 200) {
            adoptModern(reply);
            return;
        }
        exchanger.refuseOnAccessDenied(reply, "tools/list");

        JsonNode error = reply.jsonRpcError();
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

    private HttpReply probeModern() {
        McpEra probe = new McpEra.Modern();
        HttpReply reply =
                exchange(probe, "tools/list", null, Map.of(), null, spec.startupTimeoutMs());
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

    private void adoptModern(HttpReply reply) {
        era = new McpEra.Modern();
        Map<String, Object> result = jsonRpc.parseResult(reply.body());
        readModernServerInfo(reply.body());
        primed.set(toDescriptors(result));
    }

    /// Completes the `initialize` handshake of the revision before 2026-07-28.
    private void settleLegacy() {
        McpEra legacy = new McpEra.Legacy();
        era = legacy;

        HttpReply reply =
                exchange(
                        legacy,
                        "initialize",
                        null,
                        initializeParams(),
                        null,
                        spec.startupTimeoutMs());

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
        exchanger.refuseOnAccessDenied(reply, "initialize");
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

        exchanger.notify(
                legacy,
                "notifications/initialized",
                Map.of(),
                sessionId.get(),
                spec.startupTimeoutMs());

        if (!announced.declaresTools()) {
            notice(
                    "MCP server '"
                            + spec.name()
                            + "' advertises no tools capability, so none were requested");
            primed.set(List.of());
            return;
        }

        HttpReply tools =
                exchange(
                        legacy,
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
        HttpReply reply = exchange(era, method, targetName, params, carried, budgetMs);

        if (reply.status() == 404 && era.sessionScoped() && carried != null) {
            refreshSession(carried, remaining(deadline));
            reply = exchange(era, method, targetName, params, sessionId.get(), remaining(deadline));
        }

        if (targetName != null && isHeaderMismatch(reply) && relistChanged(targetName, deadline)) {
            reply = exchange(era, method, targetName, params, sessionId.get(), remaining(deadline));
        }
        return readResult(reply, method);
    }

    /// Sends one request under a fresh id, mirroring whatever headers the
    /// current listing designates for its target.
    private HttpReply exchange(
            McpEra using,
            String method,
            String targetName,
            Map<String, Object> params,
            String session,
            long budgetMs) {
        Map<String, String> mirrored =
                targetName != null && using.mirrorsParameters()
                        ? listed.mirroredHeaders(targetName, params)
                        : Map.of();
        return exchanger.request(
                using, nextRequestId(), method, targetName, params, mirrored, session, budgetMs);
    }

    private boolean isHeaderMismatch(HttpReply reply) {
        if (reply.status() != 400 || !era.mirrorsParameters()) {
            return false;
        }
        JsonNode error = reply.jsonRpcError();
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
        McpParameterHeaders.Designation before = listed.designations().get(toolName);
        toDescriptors(
                readResult(
                        exchange(
                                era,
                                "tools/list",
                                null,
                                Map.of(),
                                sessionId.get(),
                                remaining(deadline)),
                        "tools/list"));
        McpParameterHeaders.Designation after = listed.designations().get(toolName);
        return switch (after) {
            case null ->
                    throw new McpException(
                            "MCP server '"
                                    + spec.name()
                                    + "' rejected the headers of '"
                                    + toolName
                                    + "' and no longer publishes it");
            case McpParameterHeaders.Rejected rejected ->
                    throw new McpException(
                            ListedTools.rejectionNotice(spec.name(), toolName, rejected));
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
            throw exchanger.timeout("session refresh", budgetMs);
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
            throw exchanger.timeout("session refresh", budgetMs);
        }
        try {
            if (!carried.equals(sessionId.get())) {
                // Somebody else already refreshed it. Reading the new identifier and
                // retrying is the ordinary outcome when several requests were in
                // flight together.
                return;
            }
            sessionId.set(null);
            HttpReply reply = exchange(era, "initialize", null, initializeParams(), null, budgetMs);
            if (reply.status() != 200) {
                throw new McpException(
                        "MCP server '"
                                + spec.name()
                                + "' rejected re-initialisation with HTTP "
                                + reply.status());
            }
            sessionId.set(reply.header(McpProtocol.HEADER_SESSION_ID));
            exchanger.notify(era, "notifications/initialized", Map.of(), sessionId.get(), budgetMs);
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

    private Map<String, Object> readResult(HttpReply reply, String method) {
        exchanger.refuseOnAccessDenied(reply, method);
        if (reply.status() != 200) {
            String excerpt = reply.excerpt();
            throw new McpException(
                    "MCP request '"
                            + method
                            + "' to server '"
                            + spec.name()
                            + "' answered HTTP "
                            + reply.status()
                            + (excerpt.isEmpty() ? "" : ": " + excerpt));
        }
        readModernServerInfo(reply.body());
        return jsonRpc.parseResult(reply.body());
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

    // ------------------------------------------------------------------ catalog

    /// Reads a `tools/list` result and makes it the listing calls mirror from,
    /// so a re-listing after a `-32020` takes effect on the very next request.
    private List<McpToolDescriptor> toDescriptors(Map<String, Object> result) {
        ListedTools listing = ListedTools.read(spec.name(), result, era.mirrorsParameters());
        listing.rejections().forEach(this::notice);
        listed = listing;
        return listing.descriptors();
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

    private String nextRequestId() {
        return String.valueOf(nextId.incrementAndGet());
    }

    private static long remaining(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }
}
