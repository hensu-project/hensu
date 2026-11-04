package io.hensu.server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.server.execution.Claim;
import io.hensu.server.execution.ExecutionStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;

/// PostgreSQL implementation of {@link ExecutionStore}.
///
/// ### One statement per decision
/// Every decision that guards safety is a single statement whose `WHERE` clause states the
/// whole condition – the claim's epoch and the execution's state – so it is atomic with the
/// write it guards under `READ COMMITTED`. A write to a row other than the execution's own
/// (a journal record) takes the execution row `FOR SHARE` in the same statement: a
/// concurrent claim must wait for it, or it waits for the claim and then finds the epoch
/// changed. No safety decision reads one table and writes another without that lock.
///
/// ### One clock
/// Expiry is `now() + ttl` on write and `<= now()` on read: the database's clock on both
/// sides. No timestamp computed by a node reaches a comparison.
///
/// @implNote Thread-safe. Each call takes its own pooled connection; blocking is fine on
///     the Virtual Threads runs use.
public final class JdbcExecutionStore implements ExecutionStore {

    // --- claims -----------------------------------------------------------------------

    /// A start whose key another execution already carries inserts nothing; the conflict
    /// is decided by the unique index, so concurrent retries cannot both insert.
    private static final String SQL_START =
            """
            INSERT INTO runtime.execution_states
                (tenant_id, execution_id, workflow_id, current_node_id, context, history,
                 phase, retry_counters, checkpoint_reason, created_at, step,
                 run_state, claim_epoch, claim_owner, claim_expires_at,
                 start_key, start_digest)
            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, 'checkpoint', ?, ?,
                    'RUNNING', 1, ?, now() + make_interval(secs => ?), ?, ?)
            ON CONFLICT (tenant_id, start_key) WHERE start_key IS NOT NULL DO NOTHING
            RETURNING claim_epoch
            """;

    private static final String SQL_START_KEY =
            """
            SELECT execution_id, start_digest FROM runtime.execution_states
             WHERE tenant_id = ? AND start_key = ?
            """;

    private static final String SQL_RESUME =
            """
            UPDATE runtime.execution_states
               SET run_state        = 'RUNNING',
                   claim_epoch      = claim_epoch + 1,
                   claim_owner      = ?,
                   claim_expires_at = now() + make_interval(secs => ?)
             WHERE tenant_id = ? AND execution_id = ? AND run_state = 'PAUSED'
            RETURNING claim_epoch
            """;

    /// Released executions first, then the longest-expired. SKIP LOCKED spreads a
    /// simultaneous sweep across nodes instead of making them queue on the same rows.
    private static final String SQL_PICK_UP =
            """
            WITH due AS (
                SELECT tenant_id, execution_id
                  FROM runtime.execution_states
                 WHERE run_state = 'RUNNABLE'
                    OR (run_state = 'RUNNING' AND claim_expires_at <= now())
                 ORDER BY claim_expires_at NULLS FIRST
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED)
            UPDATE runtime.execution_states e
               SET run_state        = 'RUNNING',
                   claim_epoch      = e.claim_epoch + 1,
                   claim_owner      = ?,
                   claim_expires_at = now() + make_interval(secs => ?)
              FROM due
             WHERE e.tenant_id = due.tenant_id AND e.execution_id = due.execution_id
            RETURNING e.tenant_id, e.execution_id, e.claim_epoch
            """;

    private static final String SQL_RENEW =
            """
            UPDATE runtime.execution_states e
               SET claim_expires_at = now() + make_interval(secs => ?)
              FROM unnest(?::text[], ?::text[], ?::bigint[]) AS c(tenant_id, execution_id, epoch)
             WHERE e.tenant_id = c.tenant_id
               AND e.execution_id = c.execution_id
               AND e.claim_epoch = c.epoch
               AND e.run_state = 'RUNNING'
            RETURNING e.tenant_id, e.execution_id, e.claim_epoch
            """;

    /// The checkpoint also renews the claim – it is the best proof of life there is – and
    /// drops the journal of every attempt before this one.
    private static final String SQL_CHECKPOINT =
            """
            WITH written AS (
                UPDATE runtime.execution_states
                   SET current_node_id   = ?,
                       context           = ?::jsonb,
                       history           = ?::jsonb,
                       phase             = ?::jsonb,
                       retry_counters    = ?::jsonb,
                       step              = ?,
                       checkpoint_reason = 'checkpoint',
                       claim_expires_at  = now() + make_interval(secs => ?)
                 WHERE tenant_id = ? AND execution_id = ?
                   AND claim_epoch = ? AND run_state = 'RUNNING'
                RETURNING tenant_id, execution_id),
            pruned AS (
                DELETE FROM runtime.execution_journal j
                 USING written w
                 WHERE j.tenant_id = w.tenant_id AND j.execution_id = w.execution_id
                   AND j.main_step < ?)
            SELECT count(*) AS written FROM written
            """;

    /// Pause or finish: the claim ends with the snapshot, the journal goes with it, settled
    /// calls drop their results, and intents no relay has taken are cancelled so none is
    /// executed for a finished run. A result is read only by a replay of its own attempt,
    /// and no attempt is replayed past a settle; `tool_audit` keeps the record.
    private static final String SQL_SETTLE =
            """
            WITH written AS (
                UPDATE runtime.execution_states
                   SET current_node_id   = ?,
                       context           = ?::jsonb,
                       history           = ?::jsonb,
                       phase             = ?::jsonb,
                       retry_counters    = ?::jsonb,
                       step              = ?,
                       checkpoint_reason = ?,
                       run_state         = ?,
                       claim_owner       = NULL,
                       claim_expires_at  = NULL
                 WHERE tenant_id = ? AND execution_id = ?
                   AND claim_epoch = ? AND run_state = 'RUNNING'
                RETURNING tenant_id, execution_id),
            pruned AS (
                DELETE FROM runtime.execution_journal j
                 USING written w
                 WHERE j.tenant_id = w.tenant_id AND j.execution_id = w.execution_id),
            cleared AS (
                UPDATE runtime.tool_intents i
                   SET outcome = NULL
                  FROM written w
                 WHERE i.tenant_id = w.tenant_id AND i.execution_id = w.execution_id
                   AND i.state = 'DONE' AND i.outcome IS NOT NULL),
            cancelled AS (
                UPDATE runtime.tool_intents i
                   SET state = 'CANCELLED', arguments = NULL, offer_token = NULL,
                       settled_at = now()
                  FROM written w
                 WHERE i.tenant_id = w.tenant_id AND i.execution_id = w.execution_id
                   AND i.state IN ('PENDING', 'OFFERED')
                   AND ? = 'DONE')
            SELECT count(*) AS written FROM written
            """;

    private static final String SQL_RELEASE =
            """
            WITH released AS (
                UPDATE runtime.execution_states
                   SET run_state = 'RUNNABLE', claim_owner = NULL, claim_expires_at = NULL
                 WHERE tenant_id = ? AND execution_id = ?
                   AND claim_epoch = ? AND run_state = 'RUNNING'
                RETURNING 1)
            SELECT count(*) AS written FROM released, pg_notify('hensu_runnable', '') AS n
            """;

    // --- reads ------------------------------------------------------------------------

    private static final String SQL_SNAPSHOT =
            """
            SELECT workflow_id, current_node_id, context, retry_counters, history, phase,
                   checkpoint_reason, created_at, step
              FROM runtime.execution_states
             WHERE tenant_id = ? AND execution_id = ?
            """;

    /// Lists only executions that are safely paused for human review.
    ///
    /// The filter is the claim state the resume path claims from (`PAUSED`) together with
    /// the durable {@link io.hensu.core.state.ExecutionPhase}, not the `checkpoint_reason`
    /// label, so every execution a resume would accept is listed and none that it would
    /// refuse.
    private static final String SQL_FIND_PAUSED =
            """
            SELECT execution_id, workflow_id, current_node_id, context, retry_counters, history,
                   phase, checkpoint_reason, created_at, step
              FROM runtime.execution_states
             WHERE tenant_id = ? AND current_node_id IS NOT NULL
                   AND run_state = 'PAUSED'
                   AND phase ->> 'type' = 'awaiting_post_processor'
             ORDER BY created_at
            """;

    private static final String SQL_FIND_BY_WORKFLOW_ID =
            """
            SELECT execution_id, workflow_id, current_node_id, context, retry_counters, history,
                   phase, checkpoint_reason, created_at, step
              FROM runtime.execution_states
             WHERE tenant_id = ? AND workflow_id = ?
             ORDER BY created_at
            """;

    // --- journal ----------------------------------------------------------------------

    private static final String CLAIM_FENCE =
            """
            WITH claim AS (
                SELECT 1 FROM runtime.execution_states
                 WHERE tenant_id = ? AND execution_id = ?
                   AND claim_epoch = ? AND run_state = 'RUNNING'
                   FOR SHARE)
            """;

    private static final String SQL_TURN =
            """
            SELECT payload FROM runtime.execution_journal
             WHERE tenant_id = ? AND execution_id = ? AND kind = 'turn'
               AND position = ? AND idx = ?
            """;

    private static final String SQL_RECORD_TURN =
            CLAIM_FENCE
                    + """
                    INSERT INTO runtime.execution_journal
                        (tenant_id, execution_id, main_step, position, kind, idx, payload)
                    SELECT ?, ?, ?, ?, 'turn', ?, ? FROM claim
                    ON CONFLICT DO NOTHING
                    RETURNING payload
                    """;

    private static final String SQL_FLOW =
            """
            SELECT payload FROM runtime.execution_journal
             WHERE tenant_id = ? AND execution_id = ? AND kind = 'flow'
               AND position = ? AND idx = 0
            """;

    private static final String SQL_RECORD_FLOW =
            CLAIM_FENCE
                    + """
                    INSERT INTO runtime.execution_journal
                        (tenant_id, execution_id, main_step, position, kind, idx, payload)
                    SELECT ?, ?, ?, ?, 'flow', 0, ? FROM claim
                    ON CONFLICT (tenant_id, execution_id, kind, position, idx)
                    DO UPDATE SET payload = EXCLUDED.payload
                    RETURNING 1
                    """;

    private final JdbcSupport jdbc;
    private final SnapshotRows rows;
    private final String owner;
    private final double ttlSeconds;

    /// @param dataSource the connection pool, not null
    /// @param mapper the server's configured mapper, not null
    /// @param owner this node's label, recorded on the claims it takes, not null
    /// @param ttl how long a claim lasts without renewal, not null
    public JdbcExecutionStore(
            DataSource dataSource, ObjectMapper mapper, String owner, Duration ttl) {
        this.jdbc =
                new JdbcSupport(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.rows = new SnapshotRows(mapper);
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.ttlSeconds = Objects.requireNonNull(ttl, "ttl must not be null").toMillis() / 1000.0;
    }

    @Override
    public StartOutcome start(String tenantId, HensuSnapshot initial, StartKey key) {
        Optional<Long> inserted =
                jdbc.queryOne(
                        SQL_START,
                        ps -> {
                            ps.setString(1, tenantId);
                            ps.setString(2, initial.executionId());
                            ps.setString(3, initial.workflowId());
                            ps.setString(4, initial.currentNodeId());
                            ps.setString(5, rows.json(initial.context()));
                            ps.setString(6, rows.json(initial.history()));
                            ps.setString(7, rows.json(initial.phase()));
                            ps.setString(8, rows.json(initial.retryCounters()));
                            ps.setObject(
                                    9,
                                    OffsetDateTime.ofInstant(initial.createdAt(), ZoneOffset.UTC));
                            ps.setLong(10, initial.step());
                            ps.setString(11, owner);
                            ps.setDouble(12, ttlSeconds);
                            ps.setString(13, key != null ? key.key() : null);
                            ps.setString(14, key != null ? key.requestDigest() : null);
                        },
                        rs -> rs.getLong("claim_epoch"),
                        "Failed to start execution: " + initial.executionId());
        if (inserted.isPresent()) {
            return new StartOutcome.Started(new Claim(tenantId, initial.executionId(), 1));
        }
        // Only a keyed start can insert nothing: the key belongs to an execution that
        // committed before this statement looked.
        return jdbc.queryOne(
                        SQL_START_KEY,
                        ps -> {
                            ps.setString(1, tenantId);
                            ps.setString(2, key.key());
                        },
                        rs ->
                                new StartOutcome.Existing(
                                        rs.getString("execution_id"),
                                        key.requestDigest().equals(rs.getString("start_digest"))),
                        "Failed to look up start key")
                .orElseThrow(
                        () ->
                                new PersistenceException(
                                        "Start key conflicted but no execution carries it", null));
    }

    @Override
    public Optional<Claim> resume(String tenantId, String executionId) {
        return jdbc.queryOne(
                SQL_RESUME,
                ps -> {
                    ps.setString(1, owner);
                    ps.setDouble(2, ttlSeconds);
                    ps.setString(3, tenantId);
                    ps.setString(4, executionId);
                },
                rs -> new Claim(tenantId, executionId, rs.getLong("claim_epoch")),
                "Failed to claim paused execution: " + executionId);
    }

    @Override
    public List<Claim> pickUp(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.queryList(
                SQL_PICK_UP,
                ps -> {
                    ps.setInt(1, limit);
                    ps.setString(2, owner);
                    ps.setDouble(3, ttlSeconds);
                },
                rs ->
                        new Claim(
                                rs.getString("tenant_id"),
                                rs.getString("execution_id"),
                                rs.getLong("claim_epoch")),
                "Failed to pick up executions");
    }

    @Override
    public Set<Claim> renew(Collection<Claim> claims) {
        if (claims.isEmpty()) {
            return Set.of();
        }
        List<Claim> list = List.copyOf(claims);
        Object[] tenants = list.stream().map(Claim::tenantId).toArray();
        Object[] executions = list.stream().map(Claim::executionId).toArray();
        Object[] epochs = list.stream().map(Claim::epoch).toArray();
        return new HashSet<>(
                jdbc.queryList(
                        SQL_RENEW,
                        ps -> {
                            ps.setDouble(1, ttlSeconds);
                            ps.setArray(2, ps.getConnection().createArrayOf("text", tenants));
                            ps.setArray(3, ps.getConnection().createArrayOf("text", executions));
                            ps.setArray(4, ps.getConnection().createArrayOf("bigint", epochs));
                        },
                        rs ->
                                new Claim(
                                        rs.getString("tenant_id"),
                                        rs.getString("execution_id"),
                                        rs.getLong("claim_epoch")),
                        "Failed to renew claims"));
    }

    @Override
    public void checkpoint(Claim claim, HensuSnapshot snapshot) {
        long written =
                fenced(
                        claim,
                        () ->
                                jdbc.queryOne(
                                                SQL_CHECKPOINT,
                                                ps -> {
                                                    ps.setString(1, snapshot.currentNodeId());
                                                    ps.setString(2, rows.json(snapshot.context()));
                                                    ps.setString(3, rows.json(snapshot.history()));
                                                    ps.setString(4, rows.json(snapshot.phase()));
                                                    ps.setString(
                                                            5, rows.json(snapshot.retryCounters()));
                                                    ps.setLong(6, snapshot.step());
                                                    ps.setDouble(7, ttlSeconds);
                                                    ps.setString(8, claim.tenantId());
                                                    ps.setString(9, claim.executionId());
                                                    ps.setLong(10, claim.epoch());
                                                    ps.setLong(11, snapshot.step());
                                                },
                                                rs -> rs.getLong("written"),
                                                "Failed to checkpoint " + claim)
                                        .orElse(0L));
        requireWritten(claim, written);
    }

    @Override
    public void pause(Claim claim, HensuSnapshot snapshot) {
        settle(claim, snapshot, "PAUSED");
    }

    @Override
    public void finish(Claim claim, HensuSnapshot snapshot) {
        settle(claim, snapshot, "DONE");
    }

    private void settle(Claim claim, HensuSnapshot snapshot, String runState) {
        long written =
                fenced(
                        claim,
                        () ->
                                jdbc.queryOne(
                                                SQL_SETTLE,
                                                ps -> {
                                                    ps.setString(1, snapshot.currentNodeId());
                                                    ps.setString(2, rows.json(snapshot.context()));
                                                    ps.setString(3, rows.json(snapshot.history()));
                                                    ps.setString(4, rows.json(snapshot.phase()));
                                                    ps.setString(
                                                            5, rows.json(snapshot.retryCounters()));
                                                    ps.setLong(6, snapshot.step());
                                                    ps.setString(7, snapshot.checkpointReason());
                                                    ps.setString(8, runState);
                                                    ps.setString(9, claim.tenantId());
                                                    ps.setString(10, claim.executionId());
                                                    ps.setLong(11, claim.epoch());
                                                    ps.setString(12, runState);
                                                },
                                                rs -> rs.getLong("written"),
                                                "Failed to settle " + claim)
                                        .orElse(0L));
        requireWritten(claim, written);
    }

    @Override
    public void release(Claim claim) {
        long written =
                fenced(
                        claim,
                        () ->
                                jdbc.queryOne(
                                                SQL_RELEASE,
                                                ps -> {
                                                    ps.setString(1, claim.tenantId());
                                                    ps.setString(2, claim.executionId());
                                                    ps.setLong(3, claim.epoch());
                                                },
                                                rs -> rs.getLong("written"),
                                                "Failed to release " + claim)
                                        .orElse(0L));
        requireWritten(claim, written);
    }

    @Override
    public Optional<HensuSnapshot> findByExecutionId(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        return jdbc.queryOne(
                SQL_SNAPSHOT,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, executionId);
                },
                rs -> rows.read(executionId, rs),
                "Failed to read execution: " + executionId);
    }

    @Override
    public List<HensuSnapshot> findPaused(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return jdbc.queryList(
                SQL_FIND_PAUSED,
                ps -> ps.setString(1, tenantId),
                rs -> rows.read(rs.getString("execution_id"), rs),
                "Failed to query paused executions for tenant: " + tenantId);
    }

    @Override
    public List<HensuSnapshot> findByWorkflowId(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        return jdbc.queryList(
                SQL_FIND_BY_WORKFLOW_ID,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, workflowId);
                },
                rs -> rows.read(rs.getString("execution_id"), rs),
                "Failed to find executions for workflow: " + workflowId);
    }

    @Override
    public Optional<String> turn(Claim claim, String position, int index) {
        return fenced(
                claim,
                () ->
                        jdbc.queryOne(
                                SQL_TURN,
                                ps -> {
                                    ps.setString(1, claim.tenantId());
                                    ps.setString(2, claim.executionId());
                                    ps.setString(3, position);
                                    ps.setInt(4, index);
                                },
                                rs -> rs.getString("payload"),
                                "Failed to read turn " + position + "/" + index));
    }

    @Override
    public String recordTurn(Claim claim, String position, int index, String payload) {
        Optional<String> inserted =
                fenced(
                        claim,
                        () ->
                                jdbc.queryOne(
                                        SQL_RECORD_TURN,
                                        ps -> {
                                            fence(ps, claim);
                                            ps.setString(4, claim.tenantId());
                                            ps.setString(5, claim.executionId());
                                            ps.setLong(6, ExecutionStore.mainStep(position));
                                            ps.setString(7, position);
                                            ps.setInt(8, index);
                                            ps.setString(9, payload);
                                        },
                                        rs -> rs.getString("payload"),
                                        "Failed to journal turn " + position + "/" + index));
        if (inserted.isPresent()) {
            return inserted.get();
        }
        // Nothing inserted: either the turn is already there – keep what was recorded – or
        // the claim no longer stands.
        Optional<String> existing = turn(claim, position, index);
        if (existing.isPresent()) {
            return existing.get();
        }
        throw superseded(claim);
    }

    @Override
    public Optional<HensuSnapshot> flowCheckpoint(Claim claim, String scope) {
        return fenced(
                claim,
                () ->
                        jdbc.queryOne(
                                        SQL_FLOW,
                                        ps -> {
                                            ps.setString(1, claim.tenantId());
                                            ps.setString(2, claim.executionId());
                                            ps.setString(3, scope);
                                        },
                                        rs -> rs.getString("payload"),
                                        "Failed to read flow checkpoint " + scope)
                                .map(rows::fromDocument));
    }

    @Override
    public void recordFlowCheckpoint(Claim claim, String scope, HensuSnapshot snapshot) {
        String document = rows.document(snapshot);
        boolean written =
                fenced(
                        claim,
                        () ->
                                jdbc.queryOne(
                                                SQL_RECORD_FLOW,
                                                ps -> {
                                                    fence(ps, claim);
                                                    ps.setString(4, claim.tenantId());
                                                    ps.setString(5, claim.executionId());
                                                    ps.setLong(6, ExecutionStore.mainStep(scope));
                                                    ps.setString(7, scope);
                                                    ps.setString(8, document);
                                                },
                                                _ -> true,
                                                "Failed to journal flow checkpoint " + scope)
                                        .orElse(false));
        if (!written) {
            throw superseded(claim);
        }
    }

    // --- support ----------------------------------------------------------------------

    private static void fence(java.sql.PreparedStatement ps, Claim claim)
            throws java.sql.SQLException {
        ps.setString(1, claim.tenantId());
        ps.setString(2, claim.executionId());
        ps.setLong(3, claim.epoch());
    }

    /// Runs a write – or a read a run depends on – translating an unreachable store into
    /// preemption: the run cannot tell whether its claim still stands, so it must stop.
    private static <T> T fenced(Claim claim, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (PersistenceException e) {
            throw new RunPreemptedException(
                    "Store unreachable for " + claim + ": " + e.getMessage(), e);
        }
    }

    private static void requireWritten(Claim claim, long written) {
        if (written == 0) {
            throw superseded(claim);
        }
    }

    private static RunPreemptedException superseded(Claim claim) {
        return new RunPreemptedException("Claim " + claim + " no longer stands");
    }
}
