package io.hensu.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Sends bounded JSON-RPC exchanges to one Streamable HTTP endpoint.
///
/// Owns everything that touches the wire for one declared server: the bearer
/// token and declared headers every request carries, the per-exchange deadline,
/// the single redirect {@link EndpointBound} permits, reading the answer out of a
/// JSON or SSE body, and the out-of-band messages – cancellation and session
/// release – that nobody waits for. It holds no protocol state: the era and the
/// session identifier are passed in by the connection that owns them.
///
/// @implNote **Thread-safe.** Holds only immutable configuration and an executor;
///     each exchange runs on its own Virtual Thread.
final class HttpExchanger {

    private static final Logger logger = Logger.getLogger(HttpExchanger.class.getName());

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
    private final EndpointBound bound;
    private final HttpClient client;
    private final JsonRpc jsonRpc;
    private final ExecutorService exchanges;

    /// Creates the exchanger for one declared server.
    ///
    /// @param spec the server declaration, not null
    /// @param bearerToken the resolved bearer token, or null when none is sent
    /// @param allowedHosts every host declared across the deployment, not null
    /// @param client the HTTP client to send on, not null; must not follow redirects
    /// @param jsonRpc the codec requests are built with, not null
    HttpExchanger(
            McpServerSpec.Http spec,
            String bearerToken,
            Set<String> allowedHosts,
            HttpClient client,
            JsonRpc jsonRpc) {
        this.spec = spec;
        this.bearerToken = bearerToken;
        this.bound = new EndpointBound(spec.name(), spec.url(), allowedHosts);
        this.client = client;
        this.jsonRpc = jsonRpc;
        this.exchanges =
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("mcp-http-" + spec.name() + "-", 0).factory());
    }

    /// Performs one bounded request, following at most one redirect to the same endpoint.
    ///
    /// @param using the era whose headers and params shape the request, not null
    /// @param requestId the JSON-RPC id, not null
    /// @param method the JSON-RPC method, not null
    /// @param targetName the tool a `tools/call` names, or null for any other method
    /// @param params the declared params, before the era adds its own, not null
    /// @param mirrored the `Mcp-Param-*` headers to send, not null (may be empty)
    /// @param session the session identifier to carry, or null
    /// @param budgetMs what remains of the caller's budget, in milliseconds
    /// @return the answer, never null
    /// @throws McpException if the budget is spent, the server cannot be reached,
    ///     or the answer stream is truncated
    /// @throws McpEgressDeniedException if the server redirects off its endpoint
    HttpReply request(
            McpEra using,
            String requestId,
            String method,
            String targetName,
            Map<String, Object> params,
            Map<String, String> mirrored,
            String session,
            long budgetMs) {
        if (budgetMs <= 0) {
            throw timeout(method, budgetMs);
        }
        String body = jsonRpc.createRequest(requestId, method, using.params(params));
        HttpReply reply =
                post(
                        using,
                        requestId,
                        method,
                        targetName,
                        mirrored,
                        body,
                        session,
                        budgetMs,
                        spec.url());
        if (reply.status() / 100 == 3) {
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
                            bound.follow(reply));
        }
        return reply;
    }

    /// Sends one notification and swallows any failure to deliver it.
    ///
    /// A notification nobody read changes nothing the caller can act on.
    ///
    /// @param using the era whose headers and params shape the notification, not null
    /// @param method the JSON-RPC method, not null
    /// @param params the declared params, not null
    /// @param session the session identifier to carry, or null
    /// @param budgetMs the deadline, in milliseconds
    void notify(
            McpEra using,
            String method,
            Map<String, Object> params,
            String session,
            long budgetMs) {
        String body = jsonRpc.createNotification(method, using.params(params));
        try {
            post(using, null, method, null, Map.of(), body, session, budgetMs, spec.url());
        } catch (McpException e) {
            logger.log(
                    Level.FINE,
                    "MCP notification '" + method + "' to '" + spec.name() + "' was not delivered",
                    e);
        }
    }

    /// Releases a legacy session with one `DELETE`.
    ///
    /// A `405` is the documented answer of a server that mints no sessions, and
    /// any other failure here costs the run nothing: the connection is already
    /// closed.
    ///
    /// @param session the session identifier, not null
    void release(String session) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(spec.url())
                        .header("Accept", ACCEPT)
                        .header(McpProtocol.HEADER_SESSION_ID, session)
                        .timeout(Duration.ofMillis(CANCELLATION_TIMEOUT_MS))
                        .DELETE();
        applyCommonHeaders(request);
        try {
            client.send(request.build(), BodyHandlers.ofString());
        } catch (IOException e) {
            logger.log(Level.FINE, "Could not release MCP session for '" + spec.name() + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Stops every exchange still in flight.
    void close() {
        exchanges.shutdownNow();
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
    /// @param reply the answer, not null
    /// @param method the JSON-RPC method it answered, not null
    /// @throws McpException when the status is 401 or 403
    void refuseOnAccessDenied(HttpReply reply, String method) {
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

    /// Builds the failure a spent budget reports.
    ///
    /// @param method what was being waited for, not null
    /// @param budgetMs the budget that ran out, in milliseconds
    /// @return the failure, never null
    McpException timeout(String method, long budgetMs) {
        return new McpException(
                "MCP request '"
                        + method
                        + "' to server '"
                        + spec.name()
                        + "' timed out after "
                        + budgetMs
                        + "ms");
    }

    private HttpReply post(
            McpEra using,
            String requestId,
            String method,
            String targetName,
            Map<String, String> mirrored,
            String body,
            String session,
            long budgetMs,
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
        CompletableFuture<HttpReply> pending =
                CompletableFuture.supplyAsync(
                                () -> read(request.build(), requestId, live), exchanges)
                        .orTimeout(budgetMs, TimeUnit.MILLISECONDS);
        try {
            return pending.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException || isRequestTimeout(e.getCause())) {
                // Closing the response stream is itself the cancellation in the
                // modern era; the legacy era says so out of band instead.
                live.abandon();
                if (requestId != null) {
                    using.cancel(requestId, id -> announceCancellation(using, id, session));
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

    /// Sends and reads one request; a `requestId` of null marks a notification,
    /// whose answer carries nothing to wait for.
    private HttpReply read(HttpRequest request, String requestId, BodyHandoff live) {
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
                contentType.startsWith("text/event-stream") && requestId != null
                        ? SseFrames.read(response.body(), requestId, spec.name())
                        : drain(response.body());
        return new HttpReply(response.statusCode(), response.headers(), body);
    }

    /// Tells the server nobody wants an answer, without making the caller wait.
    ///
    /// The notification is dispatched and forgotten. Joining it would charge the
    /// caller {@link #CANCELLATION_TIMEOUT_MS} *on top of* the budget that has
    /// already expired — which is the outcome that budget exists to prevent: an
    /// operator who set a one-second timeout would be told three seconds later.
    /// The budget still bounds the detached request, so a server that never
    /// reads it costs one short-lived Virtual Thread rather than a leak.
    private void announceCancellation(McpEra using, String requestId, String session) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", requestId);
        params.put("reason", "the caller's request budget expired");
        try {
            exchanges.execute(
                    () ->
                            notify(
                                    using,
                                    "notifications/cancelled",
                                    params,
                                    session,
                                    CANCELLATION_TIMEOUT_MS));
        } catch (RejectedExecutionException e) {
            // The connection closed underneath us. There is nothing left to tell
            // the server that closing the stream has not already said.
            logger.log(Level.FINE, "Cancellation for '" + spec.name() + "' was not dispatched", e);
        }
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

    private static String drain(InputStream body) {
        try (InputStream stream = body) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
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
    ///     than `synchronized`, because the critical section performs a socket
    ///     `close()`.
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
}
