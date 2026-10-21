package io.hensu.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.mcp.JsonRpc;
import io.hensu.mcp.McpException;
import io.hensu.server.mcp.McpSessionManager.ResponseOutcome;
import io.hensu.server.persistence.ExecutionLeaseManager;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class McpSessionManagerTest {

    private McpSessionManager sessionManager;
    private JsonRpc jsonRpc;
    private List<McpSessionChanged> sessionEvents;
    private ExecutorService callers;
    private ExecutionLeaseManager leaseManager;

    /// Session-row writes queued in submission order, drained explicitly by tests
    /// that assert on the order the database would see them.
    private List<Runnable> queuedWrites;

    @BeforeEach
    void setUp() {
        jsonRpc = new JsonRpc(new ObjectMapper());
        sessionEvents = new CopyOnWriteArrayList<>();
        leaseManager = mock(ExecutionLeaseManager.class);
        when(leaseManager.getServerNodeId()).thenReturn("node-a");
        queuedWrites = new ArrayList<>();
        sessionManager =
                new McpSessionManager(jsonRpc, sessionEvents::add, leaseManager, queuedWrites::add);
        callers = Executors.newVirtualThreadPerTaskExecutor();
    }

    /// Runs queued writes first-in first-out, including any a write queues itself.
    private void drainWrites() {
        while (!queuedWrites.isEmpty()) {
            queuedWrites.removeFirst().run();
        }
    }

    private AssertSubscriber<String> connect(String clientId) {
        AssertSubscriber<String> subscriber =
                sessionManager
                        .createSession(clientId)
                        .subscribe()
                        .withSubscriber(AssertSubscriber.create(10));
        subscriber.awaitItems(1); // ping
        return subscriber;
    }

    /// Issues a request from a virtual thread, as the tool loop does, and waits until
    /// it has been pushed down the subscriber's stream.
    private CompletableFuture<String> callAndAwaitPush(
            String clientId, AssertSubscriber<String> stream, Duration timeout) {
        int before = stream.getItems().size();
        CompletableFuture<String> call =
                CompletableFuture.supplyAsync(
                        () ->
                                sessionManager
                                        .sendRequest(
                                                clientId,
                                                "tools/call",
                                                Map.of("name", "test"),
                                                timeout)
                                        .await()
                                        .indefinitely(),
                        callers);
        stream.awaitItems(before + 1);
        return call;
    }

    @Nested
    class CreateSession {

        @Test
        void shouldCreateSessionAndEmitPing() {
            AssertSubscriber<String> subscriber =
                    sessionManager
                            .createSession("client-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            // Should receive ping immediately
            subscriber.awaitItems(1);
            assertThat(subscriber.getItems()).hasSize(1);
            assertThat(subscriber.getItems().getFirst()).contains("\"method\":\"ping\"");
        }

        @Test
        void shouldMarkClientAsConnected() {
            sessionManager
                    .createSession("client-1")
                    .subscribe()
                    .withSubscriber(AssertSubscriber.create(10));

            assertThat(sessionManager.isConnected("client-1")).isTrue();
            assertThat(sessionManager.connectedClientCount()).isEqualTo(1);
        }

        @Test
        void shouldTrackClientInfo() {
            sessionManager
                    .createSession("client-1")
                    .subscribe()
                    .withSubscriber(AssertSubscriber.create(10));

            McpSessionManager.ClientInfo info = sessionManager.getClientInfo("client-1");
            assertThat(info).isNotNull();
            assertThat(info.clientId()).isEqualTo("client-1");
            assertThat(info.connectedAt()).isGreaterThan(0);
        }
    }

    @Nested
    class SendRequest {

        @Test
        void shouldSendRequestAndReceiveResponse() {
            // Connect client
            AssertSubscriber<String> subscriber =
                    sessionManager
                            .createSession("client-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            // Await ping
            subscriber.awaitItems(1);

            // Send request in background
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CompletableFuture<String> resultFuture =
                    CompletableFuture.supplyAsync(
                            () ->
                                    sessionManager
                                            .sendRequest(
                                                    "client-1",
                                                    "tools/call",
                                                    Map.of("name", "test"))
                                            .await()
                                            .atMost(Duration.ofSeconds(5)),
                            executor);

            // Wait for request to be sent
            subscriber.awaitItems(2);
            String request = subscriber.getItems().get(1);
            assertThat(request).contains("\"method\":\"tools/call\"");

            // Extract request ID and send response
            String requestId = jsonRpc.extractId(request);
            String response = jsonRpc.createResponse(requestId, Map.of("result", "success"));
            assertThat(sessionManager.handleResponse("client-1", response))
                    .isEqualTo(ResponseOutcome.COMPLETED);

            // Verify result
            String result = resultFuture.join();
            assertThat(result).contains("\"result\"");
        }

        @Test
        void shouldFailIfClientNotConnected() {
            Uni<String> result = sessionManager.sendRequest("unknown", "test", Map.of());

            assertThatThrownBy(() -> result.await().atMost(Duration.ofSeconds(1)))
                    .hasStackTraceContaining("Client not connected: unknown");
        }

        /// "Not connected" would send an operator looking for a relay that is in
        /// fact connected; when the session is on another node, the failure says so.
        @Test
        void shouldNameOwningNodeWhenSessionIsHeldElsewhere() {
            when(leaseManager.sessionElsewhere("tenant-1")).thenReturn(Optional.of("node-b"));

            Uni<String> result = sessionManager.sendRequest("tenant-1", "tools/call", Map.of());

            assertThatThrownBy(() -> result.await().atMost(Duration.ofSeconds(1)))
                    .isInstanceOf(McpException.class)
                    .hasMessage(
                            "Tenant 'tenant-1' has its MCP session on node node-b, not on this"
                                    + " node node-a");
        }

        @Test
        void shouldTimeoutIfNoResponse() {
            sessionManager
                    .createSession("client-1")
                    .subscribe()
                    .withSubscriber(AssertSubscriber.create(10));

            Uni<String> result =
                    sessionManager.sendRequest(
                            "client-1", "test", Map.of(), Duration.ofMillis(100));

            assertThatThrownBy(() -> result.await().atMost(Duration.ofSeconds(1)))
                    .hasStackTraceContaining("timed out");
        }
    }

    @Nested
    class SendNotification {

        @Test
        void shouldSendNotificationWithoutWaiting() {
            AssertSubscriber<String> subscriber =
                    sessionManager
                            .createSession("client-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            subscriber.awaitItems(1); // ping

            sessionManager.sendNotification("client-1", "progress", Map.of("percent", 50));

            subscriber.awaitItems(2);
            assertThat(subscriber.getItems().get(1)).contains("\"method\":\"progress\"");
        }
    }

    @Nested
    class HandleResponse {

        /// A client-initiated request carries an id too, but it answers nothing the
        /// server sent; it must not be reported as an unknown response (a 404).
        @Test
        void shouldReportClientRequestAsNotAResponse() {
            String ping = jsonRpc.createRequest("client-ping-1", "ping", Map.of());

            assertThat(sessionManager.handleResponse("client-1", ping))
                    .isEqualTo(ResponseOutcome.NOT_A_RESPONSE);
        }

        @Test
        void shouldReportUnknownId() {
            String response = jsonRpc.createResponse("unknown-id", Map.of());
            assertThat(sessionManager.handleResponse("client-1", response))
                    .isEqualTo(ResponseOutcome.UNKNOWN);
        }

        /// A tenant that learns another tenant's request id must not be able to
        /// answer it: the forged answer would be fed into the owner's agent loop.
        @Test
        void shouldLeaveForeignTenantsRequestPendingForItsOwner() {
            AssertSubscriber<String> owner = connect("tenant-a");
            connect("tenant-b");
            CompletableFuture<String> call =
                    callAndAwaitPush("tenant-a", owner, Duration.ofSeconds(5));
            String requestId = jsonRpc.extractId(owner.getItems().getLast());

            String forged = jsonRpc.createResponse(requestId, Map.of("forged", true));
            assertThat(sessionManager.handleResponse("tenant-b", forged))
                    .isEqualTo(ResponseOutcome.UNKNOWN);
            assertThat(call).isNotDone();
            assertThat(sessionManager.pendingRequestCount()).isEqualTo(1);

            String genuine = jsonRpc.createResponse(requestId, Map.of("genuine", true));
            assertThat(sessionManager.handleResponse("tenant-a", genuine))
                    .isEqualTo(ResponseOutcome.COMPLETED);
            assertThat(call.join()).contains("genuine").doesNotContain("forged");
        }
    }

    @Nested
    class Supersede {

        /// The regression the fencing exists for: a displaced stream's termination
        /// must not evict the session that replaced it.
        @Test
        void shouldKeepSuccessorWhenDisplacedStreamTerminates() {
            AssertSubscriber<String> first = connect("client-1");
            AssertSubscriber<String> second = connect("client-1");

            first.awaitCompletion(Duration.ofSeconds(1));
            first.cancel();

            assertThat(sessionManager.isConnected("client-1")).isTrue();
            assertThat(sessionManager.connectedClientCount()).isEqualTo(1);

            CompletableFuture<String> call =
                    callAndAwaitPush("client-1", second, Duration.ofSeconds(5));
            String request = second.getItems().getLast();
            assertThat(request).contains("\"method\":\"tools/call\"");
            assertThat(first.getItems()).hasSize(1);

            sessionManager.handleResponse(
                    "client-1", jsonRpc.createResponse(jsonRpc.extractId(request), Map.of()));
            assertThat(call.join()).contains("\"result\"");
        }

        /// A request pushed down the displaced stream reached a relay that is gone;
        /// the successor never saw it, so nobody will answer it.
        @Test
        void shouldFailRequestsPendingOnDisplacedStream() {
            AssertSubscriber<String> first = connect("client-1");
            CompletableFuture<String> call =
                    callAndAwaitPush("client-1", first, Duration.ofSeconds(30));

            connect("client-1");

            assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS))
                    .hasStackTraceContaining("disconnected before answering tools/call");
            assertThat(sessionManager.pendingRequestCount()).isZero();
        }
    }

    @Nested
    class Disconnect {

        @Test
        void shouldRemoveClientOnDisconnect() {
            connect("client-1");

            assertThat(sessionManager.isConnected("client-1")).isTrue();

            sessionManager.disconnect("client-1");

            assertThat(sessionManager.isConnected("client-1")).isFalse();
        }

        @Test
        void shouldCompleteStreamOnDisconnect() {
            AssertSubscriber<String> subscriber = connect("client-1");

            sessionManager.disconnect("client-1");

            assertDoesNotThrow(() -> subscriber.awaitCompletion(Duration.ofSeconds(1)));
        }

        /// A client that goes away fails its in-flight calls at once, naming the
        /// disconnect, rather than leaving each to wait out its timeout.
        @Test
        void shouldFailInFlightCallsImmediatelyWhenClientDisconnects() {
            AssertSubscriber<String> stream = connect("client-1");
            CompletableFuture<String> call =
                    callAndAwaitPush("client-1", stream, Duration.ofSeconds(30));
            assertThat(sessionManager.pendingRequestCount()).isEqualTo(1);

            stream.cancel();

            assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS))
                    .hasStackTraceContaining(
                            "MCP client 'client-1' disconnected before answering tools/call");
            assertThat(sessionManager.pendingRequestCount()).isZero();
            assertThat(sessionManager.isConnected("client-1")).isFalse();
        }

        @Test
        void shouldFailInFlightCallsOnForcedDisconnect() {
            AssertSubscriber<String> stream = connect("client-1");
            CompletableFuture<String> call =
                    callAndAwaitPush("client-1", stream, Duration.ofSeconds(30));

            sessionManager.disconnect("client-1");

            assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS))
                    .hasStackTraceContaining("disconnected before answering");
            assertThat(sessionManager.pendingRequestCount()).isZero();
        }
    }

    @Nested
    class SessionRows {

        /// Registration and termination run on the event loop and enqueue their
        /// writes in causal order. Disconnect-then-reconnect must reach the database
        /// as clear-then-publish; the reverse would erase the live session's row.
        @Test
        void shouldApplyClearBeforePublishWhenClientReconnects() {
            AssertSubscriber<String> first = connect("client-1");
            first.cancel();
            connect("client-1");

            drainWrites();

            InOrder order = inOrder(leaseManager);
            order.verify(leaseManager).publishSession("client-1");
            order.verify(leaseManager).clearSession("client-1");
            order.verify(leaseManager).publishSession("client-1");
        }

        /// The displaced stream is not current when it terminates, so it must not
        /// clear the row the successor just published.
        @Test
        void shouldPublishOnceForSupersedeAndNeverClear() {
            connect("client-1");
            drainWrites();

            connect("client-1");
            drainWrites();

            verify(leaseManager, times(2)).publishSession("client-1");
            verify(leaseManager, never()).clearSession(anyString());
        }

        @Test
        void shouldQueueWritesInsteadOfRunningThemOnTheCallingThread() {
            connect("client-1");

            verify(leaseManager, never()).publishSession(anyString());
            assertThat(queuedWrites).hasSize(1);
        }

        /// The heartbeat used to read the connected clients on its own thread and
        /// write later, so a clear landing in between was undone by a re-insert. The
        /// refresh now reads the registry when it runs on the writer, after the clear.
        @Test
        void shouldNotRefreshSessionThatEndedBeforeRefreshRan() {
            AssertSubscriber<String> stream = connect("client-1");
            drainWrites();

            sessionManager.refreshSessions();
            stream.cancel();
            drainWrites();

            verify(leaseManager, never()).refreshSessions(anyCollection());
            verify(leaseManager).clearSession("client-1");
        }

        /// The relay reconnected to another node, leaving this stream half-open; it
        /// must stop counting as connected here, or its refresh would win the row
        /// back the moment the other node clears it.
        @Test
        void shouldRetireSessionWhoseRowAnotherNodeHolds() {
            AssertSubscriber<String> stream = connect("client-1");
            drainWrites();
            when(leaseManager.refreshSessions(List.of("client-1"))).thenReturn(Set.of("client-1"));

            sessionManager.refreshSessions();
            drainWrites();

            stream.awaitCompletion();
            assertThat(sessionManager.isConnected("client-1")).isFalse();
        }

        /// A session whose publish has not run yet would find the previous holder's
        /// row and be retired for it, killing the newest connection.
        @Test
        void shouldNotJudgeSessionBeforeItsPublishRan() {
            when(leaseManager.refreshSessions(anyCollection())).thenReturn(Set.of("client-1"));
            sessionManager.refreshSessions();
            connect("client-1");

            drainWrites();

            verify(leaseManager, never()).refreshSessions(anyCollection());
            assertThat(sessionManager.isConnected("client-1")).isTrue();
        }

        /// After a failed publish, another node's row may predate this session; it
        /// proves nothing and must not retire the live stream.
        @Test
        void shouldNotRetireSessionWhosePublishFailed() {
            doThrow(new RuntimeException("db down")).when(leaseManager).publishSession("client-1");
            connect("client-1");
            drainWrites();
            when(leaseManager.refreshSessions(List.of("client-1"))).thenReturn(Set.of("client-1"));

            sessionManager.refreshSessions();
            drainWrites();

            assertThat(sessionManager.isConnected("client-1")).isTrue();
        }
    }

    @Nested
    class SessionChangedEvents {

        @Test
        void shouldAnnounceRegistrationAndTermination() {
            AssertSubscriber<String> stream = connect("client-1");
            assertThat(sessionEvents).containsExactly(new McpSessionChanged("client-1"));

            stream.cancel();

            assertThat(sessionEvents)
                    .containsExactly(
                            new McpSessionChanged("client-1"), new McpSessionChanged("client-1"));
        }

        /// A supersede is one registration; the displaced stream's termination is not
        /// a session change, because the client is still connected.
        @Test
        void shouldAnnounceSupersedeOnceAndIgnoreDisplacedTermination() {
            connect("client-1");
            connect("client-1");

            assertThat(sessionEvents).hasSize(2);
            assertThat(sessionManager.isConnected("client-1")).isTrue();
        }
    }
}
