package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.tool.ToolCall;
import io.hensu.server.execution.Claim;
import io.hensu.server.execution.ExecutionStore.StartKey;
import io.hensu.server.execution.ExecutionStore.StartOutcome;
import io.hensu.server.mcp.ToolIntents.Settlement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// The claim protocol at the level where it is decided: single SQL statements against
/// PostgreSQL. Each test names the invariant of docs/multi-node-execution-protocol.md it
/// pins.
class JdbcExecutionStoreTest extends JdbcRepositoryTestBase {

    private JdbcExecutionStore nodeA;
    private JdbcExecutionStore nodeB;

    @BeforeEach
    void setUp() {
        deleteExecutions(TENANT);
        JdbcWorkflowRepository workflows = new JdbcWorkflowRepository(dataSource);
        workflows.deleteAllForTenant(TENANT);
        workflows.save(TENANT, buildWorkflow("wf"));
        nodeA = new JdbcExecutionStore(dataSource, objectMapper, "node-a", Duration.ofSeconds(30));
        nodeB = new JdbcExecutionStore(dataSource, objectMapper, "node-b", Duration.ofSeconds(30));
    }

    @Test
    void s2_aSupersededClaimCanWriteNothing() {
        Claim first = nodeA.start(TENANT, snapshot("x", 0));
        expire("x");
        Claim second = nodeB.pickUp(10).getFirst();
        assertThat(second.epoch()).isEqualTo(2);

        assertThatThrownBy(() -> nodeA.checkpoint(first, snapshot("x", 1)))
                .isInstanceOf(RunPreemptedException.class);
        assertThatThrownBy(() -> nodeA.recordTurn(first, "0", 1, "turn"))
                .isInstanceOf(RunPreemptedException.class);
        assertThatThrownBy(() -> nodeA.recordFlowCheckpoint(first, "0/f:a/", snapshot("x", 0)))
                .isInstanceOf(RunPreemptedException.class);
        assertThatThrownBy(() -> nodeA.finish(first, snapshot("x", 0, "completed")))
                .isInstanceOf(RunPreemptedException.class);

        nodeB.checkpoint(second, snapshot("x", 1));
        assertThat(nodeB.findByExecutionId(TENANT, "x").orElseThrow().step()).isEqualTo(1);
    }

    /// A claim about to lapse is renewed a full lease past the database's clock, so no
    /// pickup can take it from its live holder.
    @Test
    void s5_renewalPushesAClaimAboutToLapseAFullLeaseAhead() {
        Claim claim = nodeA.start(TENANT, snapshot("x", 0));
        update(
                "UPDATE runtime.execution_states SET claim_expires_at = now() + interval '1 second'"
                        + " WHERE tenant_id = '"
                        + TENANT
                        + "' AND execution_id = 'x'");

        assertThat(nodeA.renew(List.of(claim))).containsExactly(claim);

        assertThat(secondsLeft()).isGreaterThan(20);
        assertThat(nodeB.pickUp(10)).isEmpty();
    }

    @Test
    void s1_concurrentPickupsNeverTakeTheSameExecution() throws Exception {
        for (int i = 0; i < 20; i++) {
            nodeA.start(TENANT, snapshot("x" + i, 0));
            expire("x" + i);
        }

        CompletableFuture<List<Claim>> a = CompletableFuture.supplyAsync(() -> nodeA.pickUp(20));
        CompletableFuture<List<Claim>> b = CompletableFuture.supplyAsync(() -> nodeB.pickUp(20));
        List<Claim> taken = new ArrayList<>(a.get(10, TimeUnit.SECONDS));
        taken.addAll(b.get(10, TimeUnit.SECONDS));

        assertThat(taken).hasSize(20);
        assertThat(taken.stream().map(Claim::executionId).distinct()).hasSize(20);
        assertThat(taken).allSatisfy(c -> assertThat(c.epoch()).isEqualTo(2));
    }

    @Test
    void l1_renewalReturnsOnlyClaimsThatStillStand() {
        Claim kept = nodeA.start(TENANT, snapshot("kept", 0));
        Claim lost = nodeA.start(TENANT, snapshot("lost", 0));
        expire("lost");
        nodeB.pickUp(10);

        Set<Claim> renewed = nodeA.renew(List.of(kept, lost));

        assertThat(renewed).containsExactly(kept);
    }

    @Test
    void l5_aReleasedExecutionIsPickedUpAtOnceWithoutWaitingOutItsClaim() {
        Claim claim = nodeA.start(TENANT, snapshot("x", 0));

        nodeA.release(claim);

        assertThat(nodeB.pickUp(10)).extracting(Claim::epoch).containsExactly(2L);
        assertThatThrownBy(() -> nodeA.checkpoint(claim, snapshot("x", 1)))
                .isInstanceOf(RunPreemptedException.class);
    }

    @Test
    void s8_concurrentRetriesOfOneKeyedStartCreateOneExecution() throws Exception {
        StartKey key = new StartKey("start-1", "digest-a");
        CountDownLatch go = new CountDownLatch(1);
        List<Future<StartOutcome>> attempts = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                JdbcExecutionStore node = i % 2 == 0 ? nodeA : nodeB;
                String executionId = "x" + i;
                attempts.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    return node.start(TENANT, snapshot(executionId, 0), key);
                                }));
            }
            go.countDown();
        }
        List<StartOutcome> outcomes = new ArrayList<>();
        for (Future<StartOutcome> attempt : attempts) {
            outcomes.add(attempt.get());
        }

        List<String> started =
                outcomes.stream()
                        .filter(StartOutcome.Started.class::isInstance)
                        .map(o -> ((StartOutcome.Started) o).claim().executionId())
                        .toList();
        assertThat(started).hasSize(1);
        assertThat(outcomes)
                .filteredOn(StartOutcome.Existing.class::isInstance)
                .hasSize(7)
                .allSatisfy(
                        o ->
                                assertThat((StartOutcome.Existing) o)
                                        .isEqualTo(
                                                new StartOutcome.Existing(
                                                        started.getFirst(), true)));
        assertThat(nodeB.start(TENANT, snapshot("y", 0), new StartKey("start-1", "digest-b")))
                .isEqualTo(new StartOutcome.Existing(started.getFirst(), false));
    }

    @Test
    void onlyAPausedExecutionCanBeResumedAndOnlyOnce() {
        Claim claim = nodeA.start(TENANT, snapshot("x", 0));
        assertThat(nodeB.resume(TENANT, "x")).isEmpty();

        nodeA.pause(claim, snapshot("x", 0, "paused"));
        assertThat(nodeB.resume(TENANT, "x")).get().extracting(Claim::epoch).isEqualTo(2L);
        assertThat(nodeA.resume(TENANT, "x")).isEmpty();
    }

    @Test
    void s7_aJournaledTurnIsReplayedNotRecordedAgain() {
        Claim first = nodeA.start(TENANT, snapshot("x", 0));
        assertThat(nodeA.recordTurn(first, "0", 1, "original")).isEqualTo("original");

        expire("x");
        Claim second = nodeB.pickUp(10).getFirst();

        assertThat(nodeB.turn(second, "0", 1)).contains("original");
        // A recovery that somehow produced a turn again keeps the recorded one.
        assertThat(nodeB.recordTurn(second, "0", 1, "different")).isEqualTo("original");
    }

    @Test
    void aCheckpointDropsTheJournalOfEveryEarlierAttempt() {
        Claim claim = nodeA.start(TENANT, snapshot("x", 0));
        nodeA.recordTurn(claim, "0", 1, "step zero");
        nodeA.recordFlowCheckpoint(claim, "0/f:a/", snapshot("x", 0));
        nodeA.recordTurn(claim, "1", 1, "step one");

        nodeA.checkpoint(claim, snapshot("x", 1));

        assertThat(nodeA.turn(claim, "0", 1)).isEmpty();
        assertThat(nodeA.flowCheckpoint(claim, "0/f:a/")).isEmpty();
        assertThat(nodeA.turn(claim, "1", 1)).contains("step one");
    }

    @Test
    void aNestedFlowsLatestCheckpointReplacesItsPrevious() {
        Claim claim = nodeA.start(TENANT, snapshot("x", 0));
        nodeA.recordFlowCheckpoint(claim, "0/f:a/", snapshot("x", 0));
        nodeA.recordFlowCheckpoint(claim, "0/f:a/", snapshot("x", 2));

        assertThat(nodeA.flowCheckpoint(claim, "0/f:a/"))
                .get()
                .extracting(HensuSnapshot::step)
                .isEqualTo(2L);
    }

    /// The fenced journal insert takes the execution row FOR SHARE, so a write that races a
    /// claim waits for it and then finds the epoch changed. Without the lock the insert would
    /// read the old epoch from its snapshot and commit under a superseded claim.
    @Test
    void s3_aFencedWriteWaitsOutAnOpenClaimAndThenWritesNothing() throws Exception {
        Claim first = nodeA.start(TENANT, snapshot("x", 0));
        // The pool closes after the connection, so a failed assertion rolls the claim back
        // and frees the waiting write instead of deadlocking the close.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
                Connection claiming = dataSource.getConnection()) {
            claiming.setAutoCommit(false);
            // A pickup's claim, held open: the same row update the sweep makes.
            try (PreparedStatement claim =
                    claiming.prepareStatement(
                            "UPDATE runtime.execution_states SET claim_epoch = claim_epoch + 1,"
                                    + " claim_owner = 'node-b'"
                                    + " WHERE tenant_id = ? AND execution_id = ?")) {
                claim.setString(1, TENANT);
                claim.setString(2, "x");
                assertThat(claim.executeUpdate()).isOne();
            }

            Future<String> late = pool.submit(() -> nodeA.recordTurn(first, "0", 1, "late"));
            Thread.sleep(300);
            assertThat(late).as("the fenced write waits for the open claim").isNotDone();

            claiming.commit();
            assertThatThrownBy(() -> late.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RunPreemptedException.class);
        }
        assertThat(nodeA.turn(first, "0", 1)).isEmpty();
    }

    @Test
    void finishingCancelsTheIntentsNoRelayHasTaken() throws Exception {
        Claim claim = nodeA.start(TENANT, snapshot("x", 0));
        JdbcToolIntents intents = intents();
        intents.submit(claim, new ToolCall("0#1", "t", Map.of()));

        nodeA.finish(claim, snapshot("x", 0, "completed"));

        assertThat(intentState()).isEqualTo("CANCELLED");
        assertThat(intents.offer(TENANT, 10)).isEmpty();
    }

    @Test
    void settlingDropsTheResultsOfSettledCallsAndKeepsTheirState() throws Exception {
        Claim claim = nodeA.start(TENANT, snapshot("x", 0));
        JdbcToolIntents intents = intents();
        intents.submit(claim, new ToolCall("0#1", "t", Map.of()));
        String token = intents.offer(TENANT, 10).getFirst().token();
        intents.ack(TENANT, token);
        intents.complete(TENANT, token, new Settlement("SUCCESS", "{\"result\":42}"));
        assertThat(intentOutcome()).isNotNull();

        nodeA.pause(claim, snapshot("x", 1, "paused"));

        assertThat(intentOutcome()).isNull();
        assertThat(intentState()).isEqualTo("DONE");
    }

    private static JdbcToolIntents intents() {
        return new JdbcToolIntents(
                dataSource,
                objectMapper,
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                Duration.ofMinutes(5));
    }

    private void expire(String executionId) {
        update(
                "UPDATE runtime.execution_states SET claim_expires_at = now() - interval '1 second'"
                        + " WHERE tenant_id = '"
                        + TENANT
                        + "' AND execution_id = '"
                        + executionId
                        + "'");
    }

    /// Seconds until the execution's claim expires, by the database's clock.
    private static double secondsLeft() {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT EXTRACT(EPOCH FROM claim_expires_at - now())"
                                        + " FROM runtime.execution_states"
                                        + " WHERE tenant_id = ? AND execution_id = ?")) {
            ps.setString(1, TENANT);
            ps.setString(2, "x");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getDouble(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String intentState() throws Exception {
        return intentColumn("state");
    }

    private String intentOutcome() throws Exception {
        return intentColumn("outcome::text");
    }

    private static String intentColumn(String column) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT "
                                        + column
                                        + " FROM runtime.tool_intents WHERE call_key = ?")) {
            ps.setString(1, "0#1");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void update(String sql) {
        try (Connection c = dataSource.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate(sql);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HensuSnapshot snapshot(String executionId, long step) {
        return snapshot(executionId, step, "checkpoint");
    }

    private static HensuSnapshot snapshot(String executionId, long step, String reason) {
        return new HensuSnapshot(
                "wf",
                executionId,
                "process",
                new HashMap<>(Map.of("k", "v")),
                Map.of(),
                new ExecutionHistory(),
                null,
                Instant.now(),
                reason,
                step);
    }
}
