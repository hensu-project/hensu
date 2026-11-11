package io.hensu.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Sends bounded JSON-RPC exchanges to one Streamable HTTP endpoint.
///
/// Owns everything that touches the wire for one declared server: the bearer
/// credential and declared headers every request carries, the per-exchange
/// deadline, the single redirect {@link EndpointBound} permits, the single resend
/// a refused token's successor permits, reading the answer out of a JSON or SSE
/// body, a caller's {@link CallControl}, and the out-of-band messages –
/// cancellation and session release – that nobody waits for. It holds no
/// protocol state: the era and the session identifier are passed in by the
/// connection that owns them.
///
/// ### Delivery Classification
/// Every failure says whether the request can have reached the server (see
/// {@link McpException.Delivery}). A refused or timed-out connect, a refused
/// `307` or `308` redirect, a `401` or `403`, and a cancel or an expired budget
/// that stops an exchange before it began are `NOT_SENT`; anything that fails after the
/// request was handed to the client is `MAYBE_DELIVERED`, because the JDK client
/// cannot say how many bytes left.
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
    private final BearerCredential credential;
    private final EndpointBound bound;
    private final HttpClient client;
    private final JsonRpc jsonRpc;
    private final ExecutorService exchanges;

    /// Creates the exchanger for one declared server.
    ///
    /// @param spec the server declaration, not null
    /// @param credential the bearer token source, not null; {@link BearerCredential#NONE}
    ///     when no `Authorization` header is sent
    /// @param allowedHosts every host declared across the deployment, not null
    /// @param client the HTTP client to send on, not null; must not follow redirects
    /// @param jsonRpc the codec requests are built with, not null
    HttpExchanger(
            McpServerSpec.Http spec,
            BearerCredential credential,
            Set<String> allowedHosts,
            HttpClient client,
            JsonRpc jsonRpc) {
        this.spec = spec;
        this.credential = credential;
        this.bound = new EndpointBound(spec.name(), spec.url(), allowedHosts);
        this.client = client;
        this.jsonRpc = jsonRpc;
        this.exchanges =
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("mcp-http-" + spec.name() + "-", 0).factory());
    }

    /// Performs one bounded request, following at most one redirect to the same
    /// endpoint and resending at most once after a `401`.
    ///
    /// The resend happens only when the credential names a token different from
    /// the one refused. A request refused at authorization never reached the
    /// tool, so it cannot run twice; a `403` is a decision about who is asking,
    /// not about the token's age, and is never retried. A `401` or `403` that
    /// stands after that fails here, so no caller can read one as an ordinary
    /// status.
    ///
    /// @param using the era whose headers and params shape the request, not null
    /// @param requestId the JSON-RPC id, not null
    /// @param method the JSON-RPC method, not null
    /// @param targetName the tool a `tools/call` names, or null for any other method
    /// @param params the declared params, before the era adds its own, not null
    /// @param mirrored the `Mcp-Param-*` headers to send, not null (may be empty)
    /// @param session the session identifier to carry, or null
    /// @param budgetMs what remains of the caller's budget, in milliseconds
    /// @param control the caller's cancellation handle, not null
    /// @return the answer, never null; its status is neither 401 nor 403
    /// @throws McpException if the budget is spent, the server cannot be reached,
    ///     the answer stream is truncated, the caller cancelled, or the server
    ///     refused the credential
    /// @throws McpEgressDeniedException if the server redirects off its endpoint
    HttpReply request(
            McpEra using,
            String requestId,
            String method,
            String targetName,
            Map<String, Object> params,
            Map<String, String> mirrored,
            String session,
            long budgetMs,
            CallControl control) {
        if (budgetMs <= 0) {
            throw McpException.timedOut(
                    spec.name(), method, budgetMs, McpException.Delivery.NOT_SENT);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        String body = jsonRpc.createRequest(requestId, method, using.params(params));
        Outgoing outgoing =
                new Outgoing(using, requestId, method, targetName, mirrored, body, session);

        String token = credential.current().orElse(null);
        HttpReply reply = send(outgoing, token, budgetMs, control);
        if (reply.status() == 401) {
            Optional<String> successor = credential.afterRejection(token);
            if (successor.isPresent() && !successor.get().equals(token)) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    throw McpException.timedOut(
                            spec.name(), method, budgetMs, McpException.Delivery.NOT_SENT);
                }
                reply = send(outgoing, successor.get(), left, control);
            }
        }
        refuseOnAccessDenied(reply, method);
        return reply;
    }

    /// Posts one request, following at most one redirect to the same endpoint.
    private HttpReply send(Outgoing outgoing, String token, long budgetMs, CallControl control) {
        HttpReply reply = post(outgoing, token, budgetMs, spec.url(), control);
        if (reply.status() / 100 == 3) {
            reply = post(outgoing, token, budgetMs, bound.follow(reply), control);
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
            post(
                    new Outgoing(using, null, method, null, Map.of(), body, session),
                    credential.current().orElse(null),
                    budgetMs,
                    spec.url(),
                    CallControl.none());
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
        applyCommonHeaders(request, credential.current().orElse(null));
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
    /// Both are {@link McpException.Delivery#NOT_SENT}: the server declined the
    /// request before acting on it.
    ///
    /// @param reply the answer, not null
    /// @param method the JSON-RPC method it answered, not null
    /// @throws McpException when the status is 401 or 403
    private void refuseOnAccessDenied(HttpReply reply, String method) {
        int status = reply.status();
        if (status != 401 && status != 403) {
            return;
        }
        boolean sent = credential.current().isPresent();
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
                                    : "the server refuses unauthenticated access"),
                    McpException.Delivery.NOT_SENT);
        }
        throw new McpException(
                prefix
                        + (sent
                                ? "the server rejected the bearer token. Check the value stored"
                                        + " under the credential key its declaration names"
                                : "the server requires authorization and none was sent. Hensu"
                                        + " sends a static bearer token from the credential store"
                                        + " and runs no OAuth flow; if this server issues static"
                                        + " tokens, declare one with auth:"),
                McpException.Delivery.NOT_SENT);
    }

    private HttpReply post(
            Outgoing outgoing, String token, long budgetMs, URI target, CallControl control) {
        if (control.isCancelled()) {
            throw McpException.cancelled(
                    spec.name(), outgoing.method(), McpException.Delivery.NOT_SENT);
        }
        HttpRequest.Builder request =
                HttpRequest.newBuilder(target)
                        .header("Content-Type", "application/json")
                        .header("Accept", ACCEPT)
                        .timeout(Duration.ofMillis(budgetMs))
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        outgoing.body(), StandardCharsets.UTF_8));
        applyCommonHeaders(request, token);
        outgoing.era()
                .applyHeaders(
                        request,
                        outgoing.method(),
                        outgoing.targetName(),
                        outgoing.session(),
                        outgoing.mirrored());

        BodyHandoff live = new BodyHandoff();
        Sender sender = new Sender();
        CompletableFuture<HttpReply> pending =
                CompletableFuture.supplyAsync(
                                () -> {
                                    if (!sender.begin()) {
                                        throw new CancelledByCaller();
                                    }
                                    return read(request.build(), outgoing.requestId(), live);
                                },
                                exchanges)
                        .orTimeout(budgetMs, TimeUnit.MILLISECONDS);
        // Completing the future wakes the caller now; stopping the sender keeps a queued
        // exchange from starting and aborts one still waiting for its response headers.
        Runnable abort =
                () -> {
                    pending.completeExceptionally(new CancelledByCaller());
                    sender.stop();
                };
        control.bind(abort);
        try {
            return pending.join();
        } catch (CompletionException e) {
            // Stopping here as well as in the abort settles the race with a send that
            // has not begun yet: either it never starts, or it already has.
            if (e.getCause() instanceof CancelledByCaller) {
                if (sender.stop()) {
                    throw McpException.cancelled(
                            spec.name(), outgoing.method(), McpException.Delivery.NOT_SENT);
                }
                abandon(outgoing, live, "the caller cancelled the request");
                throw McpException.cancelled(
                        spec.name(), outgoing.method(), McpException.Delivery.MAYBE_DELIVERED);
            }
            if (e.getCause() instanceof TimeoutException || isRequestTimeout(e.getCause())) {
                if (sender.stop()) {
                    throw McpException.timedOut(
                            spec.name(),
                            outgoing.method(),
                            budgetMs,
                            McpException.Delivery.NOT_SENT);
                }
                abandon(outgoing, live, "the caller's request budget expired");
                throw McpException.timedOut(
                        spec.name(),
                        outgoing.method(),
                        budgetMs,
                        McpException.Delivery.MAYBE_DELIVERED);
            }
            throw e.getCause() instanceof McpException mcp
                    ? mcp
                    : McpException.failed(spec.name(), outgoing.method(), e.getCause());
        } finally {
            control.unbind(abort);
            live.abandon();
        }
    }

    /// Stops waiting for an answer and tells the server so, the way the era does.
    ///
    /// Closing the response stream is itself the cancellation in the modern era;
    /// the legacy era says so out of band instead.
    private void abandon(Outgoing outgoing, BodyHandoff live, String reason) {
        live.abandon();
        if (outgoing.requestId() != null && outgoing.era().announcesCancellation()) {
            announceCancellation(outgoing.era(), outgoing.requestId(), outgoing.session(), reason);
        }
    }

    private void applyCommonHeaders(HttpRequest.Builder request, String token) {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
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
            // A connect that was refused or timed out wrote nothing; anything later
            // may have delivered the request in whole or in part.
            boolean unsent =
                    e instanceof ConnectException || e instanceof HttpConnectTimeoutException;
            throw new McpException(
                    "Could not reach MCP server '" + spec.name() + "' at " + request.uri(),
                    e,
                    unsent
                            ? McpException.Delivery.NOT_SENT
                            : McpException.Delivery.MAYBE_DELIVERED);
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
    private void announceCancellation(
            McpEra using, String requestId, String session, String reason) {
        Map<String, Object> params = McpProtocol.cancellationParams(requestId, reason);
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

    /// One request as built once and sent up to three times: to the endpoint, to
    /// the one redirect it may follow, and once more with a refused token's
    /// successor.
    ///
    /// @param era the era whose headers and params shape the request, not null
    /// @param requestId the JSON-RPC id, or null for a notification
    /// @param method the JSON-RPC method, not null
    /// @param targetName the tool a `tools/call` names, or null
    /// @param mirrored the `Mcp-Param-*` headers to send, not null
    /// @param body the serialised JSON-RPC message, not null
    /// @param session the session identifier to carry, or null
    private record Outgoing(
            McpEra era,
            String requestId,
            String method,
            String targetName,
            Map<String, String> mirrored,
            String body,
            String session) {}

    /// The thread one exchange is sent from, and whether it was ever sent.
    ///
    /// The caller can give up before the exchange's Virtual Thread has even
    /// started, and a send that starts after that is a request nobody waits for.
    /// {@link #begin()} and {@link #stop()} race for one transition out of
    /// `IDLE`, so exactly one of them wins: a stop that wins keeps the send from
    /// ever starting, which is what lets the caller report the request as
    /// `NOT_SENT`; a stop that loses interrupts the send, which aborts a request
    /// still waiting for its response headers. Each exchange has a Virtual Thread
    /// of its own, so the thread interrupted never serves anybody else.
    ///
    /// @implNote **Thread-safe.** The phase is an atomic and the thread is
    ///     published before the transition that makes it readable; no lock is
    ///     held.
    static final class Sender {

        private static final int IDLE = 0;
        private static final int SENDING = 1;
        private static final int PREVENTED = 2;

        private final AtomicInteger phase = new AtomicInteger(IDLE);
        private volatile Thread thread;

        /// Registers the calling thread as the sender.
        ///
        /// @return false when the exchange was stopped before it began, in which
        ///     case it must not be sent
        boolean begin() {
            thread = Thread.currentThread();
            return phase.compareAndSet(IDLE, SENDING);
        }

        /// Keeps the send from starting, or interrupts it when it already has.
        ///
        /// Safe to call more than once and from any thread; every call after the
        /// first returns the same answer.
        ///
        /// @return true when the request was never handed to the client and never
        ///     will be
        boolean stop() {
            if (phase.compareAndExchange(IDLE, PREVENTED) != SENDING) {
                return true;
            }
            thread.interrupt();
            return false;
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
