package io.hensu.server.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.execution.ReplayDivergenceException;
import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.tool.ToolCall;
import io.hensu.server.execution.CanonicalDigest;
import io.hensu.server.execution.Claim;
import io.hensu.server.mcp.ToolIntents;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;

/// PostgreSQL implementation of {@link ToolIntents}.
///
/// Every transition is one conditional statement, compared against `now()`, and every
/// statement that makes an intent offerable or settles one notifies in the same commit
/// (`hensu_intent` with the tenant, `hensu_outcome` with the intent id), so waiters on any
/// node wake when it lands. The intent is inserted under the claim's epoch with the
/// execution row taken `FOR SHARE`, so a claim taken concurrently either waits for the
/// insert or makes it find the epoch changed – a superseded run can never add a call.
///
/// @implNote Thread-safe. Each call takes its own pooled connection.
public final class JdbcToolIntents implements ToolIntents {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String SQL_SUBMIT =
            """
            WITH claim AS (
                SELECT 1 FROM runtime.execution_states
                 WHERE tenant_id = ? AND execution_id = ?
                   AND claim_epoch = ? AND run_state = 'RUNNING'
                   FOR SHARE),
            inserted AS (
                INSERT INTO runtime.tool_intents
                    (tenant_id, execution_id, call_key, tool_name, arguments, arguments_digest,
                     state, created_epoch, dispatch_deadline_at)
                SELECT ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', ?, now() + make_interval(secs => ?)
                  FROM claim
                ON CONFLICT (tenant_id, execution_id, call_key) DO NOTHING
                RETURNING id, tenant_id)
            SELECT inserted.id FROM inserted, pg_notify('hensu_intent', inserted.tenant_id) AS n
            """;

    private static final String SQL_FIND =
            """
            SELECT id, tool_name, arguments_digest FROM runtime.tool_intents
             WHERE tenant_id = ? AND execution_id = ? AND call_key = ?
            """;

    private static final String SQL_SETTLEMENT =
            """
            SELECT state, outcome_status, outcome::text AS outcome FROM runtime.tool_intents
             WHERE id = ?
            """;

    private static final String SQL_OFFER =
            """
            WITH picked AS (
                SELECT id FROM runtime.tool_intents
                 WHERE tenant_id = ? AND state = 'PENDING'
                 ORDER BY id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED)
            UPDATE runtime.tool_intents i
               SET state            = 'OFFERED',
                   offer_token      = gen_random_uuid()::text,
                   offer_expires_at = now() + make_interval(secs => ?)
              FROM picked
             WHERE i.id = picked.id
            RETURNING i.offer_token, i.execution_id, i.call_key, i.tool_name,
                      i.arguments::text AS arguments
            """;

    /// Idempotent for the same token: a relay that lost the reply retries and is told the
    /// call is started, without the deadline moving.
    private static final String SQL_ACK =
            """
            UPDATE runtime.tool_intents
               SET deadline_at = CASE WHEN state = 'OFFERED'
                                      THEN now() + make_interval(secs => ?)
                                      ELSE deadline_at END,
                   state       = 'STARTED'
             WHERE offer_token = ? AND tenant_id = ?
               AND ((state = 'OFFERED' AND offer_expires_at > now()) OR state = 'STARTED')
            RETURNING id
            """;

    private static final String SQL_COMPLETE =
            """
            WITH settled AS (
                UPDATE runtime.tool_intents
                   SET state          = 'DONE',
                       outcome_status = ?,
                       outcome        = ?::jsonb,
                       arguments      = NULL,
                       settled_at     = now()
                 WHERE offer_token = ? AND tenant_id = ? AND state = 'STARTED'
                RETURNING id)
            SELECT settled.id FROM settled, pg_notify('hensu_outcome', settled.id::text) AS n
            """;

    private static final String SQL_TOKEN_STATE =
            "SELECT state FROM runtime.tool_intents WHERE offer_token = ? AND tenant_id = ?";

    private static final String SQL_EXPIRE =
            """
            WITH withdrawn AS (
                UPDATE runtime.tool_intents
                   SET state = 'PENDING', offer_token = NULL, offer_expires_at = NULL
                 WHERE state = 'OFFERED' AND offer_expires_at <= now()
                RETURNING tenant_id),
            timed_out AS (
                UPDATE runtime.tool_intents
                   SET state = 'DONE', outcome_status = 'TIMEOUT', arguments = NULL,
                       settled_at = now()
                 WHERE state = 'STARTED' AND deadline_at <= now()
                RETURNING id),
            unavailable AS (
                UPDATE runtime.tool_intents
                   SET state = 'DONE', outcome_status = 'UNAVAILABLE', arguments = NULL,
                       offer_token = NULL, settled_at = now()
                 WHERE state = 'PENDING' AND dispatch_deadline_at <= now()
                RETURNING id),
            signals AS (
                SELECT 'hensu_intent' AS channel, tenant_id AS payload
                  FROM (SELECT DISTINCT tenant_id FROM withdrawn) w
                UNION ALL
                SELECT 'hensu_outcome', id::text FROM timed_out
                UNION ALL
                SELECT 'hensu_outcome', id::text FROM unavailable)
            SELECT count(*) AS signalled FROM signals, pg_notify(signals.channel, signals.payload) AS n
            """;

    private final JdbcSupport jdbc;
    private final ObjectMapper mapper;
    private final double ackSeconds;
    private final double callSeconds;
    private final double dispatchSeconds;
    private final long callMillis;

    /// @param dataSource the connection pool, not null
    /// @param mapper the server's configured mapper, not null
    /// @param ackDeadline how long an offer waits for its acknowledgement, not null
    /// @param callDeadline how long a started call may take, not null
    /// @param dispatchDeadline how long an intent waits for any relay, not null
    public JdbcToolIntents(
            DataSource dataSource,
            ObjectMapper mapper,
            Duration ackDeadline,
            Duration callDeadline,
            Duration dispatchDeadline) {
        this.jdbc =
                new JdbcSupport(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.ackSeconds = ackDeadline.toMillis() / 1000.0;
        this.callSeconds = callDeadline.toMillis() / 1000.0;
        this.dispatchSeconds = dispatchDeadline.toMillis() / 1000.0;
        this.callMillis = callDeadline.toMillis();
    }

    @Override
    public Intent submit(Claim claim, ToolCall call) {
        String digest = CanonicalDigest.of(call.arguments());
        String arguments = json(call.arguments());
        Optional<Long> inserted =
                preempting(
                        claim,
                        () ->
                                jdbc.queryOne(
                                        SQL_SUBMIT,
                                        ps -> {
                                            ps.setString(1, claim.tenantId());
                                            ps.setString(2, claim.executionId());
                                            ps.setLong(3, claim.epoch());
                                            ps.setString(4, claim.tenantId());
                                            ps.setString(5, claim.executionId());
                                            ps.setString(6, call.key());
                                            ps.setString(7, call.toolName());
                                            ps.setString(8, arguments);
                                            ps.setString(9, digest);
                                            ps.setLong(10, claim.epoch());
                                            ps.setDouble(11, dispatchSeconds);
                                        },
                                        rs -> rs.getLong("id"),
                                        "Failed to submit " + call.key()));
        if (inserted.isPresent()) {
            return new Intent(inserted.get(), call.key());
        }
        // Not inserted: a replay reached a key its predecessor submitted, or the claim is gone.
        record Existing(long id, String toolName, String digest) {}
        Existing existing =
                preempting(
                                claim,
                                () ->
                                        jdbc.queryOne(
                                                SQL_FIND,
                                                ps -> {
                                                    ps.setString(1, claim.tenantId());
                                                    ps.setString(2, claim.executionId());
                                                    ps.setString(3, call.key());
                                                },
                                                rs ->
                                                        new Existing(
                                                                rs.getLong("id"),
                                                                rs.getString("tool_name"),
                                                                rs.getString("arguments_digest")),
                                                "Failed to find " + call.key()))
                        .orElseThrow(
                                () ->
                                        new RunPreemptedException(
                                                "Claim " + claim + " no longer stands"));
        if (!existing.toolName().equals(call.toolName()) || !existing.digest().equals(digest)) {
            throw new ReplayDivergenceException(
                    "Call "
                            + call.key()
                            + " was '"
                            + existing.toolName()
                            + "' with other arguments when first made; the replay asked for '"
                            + call.toolName()
                            + "'");
        }
        return new Intent(existing.id(), call.key());
    }

    @Override
    public Optional<Settlement> settlement(Intent intent) {
        try {
            return jdbc.queryOne(
                            SQL_SETTLEMENT,
                            ps -> ps.setLong(1, intent.id()),
                            rs ->
                                    switch (rs.getString("state")) {
                                        case "DONE" ->
                                                Optional.of(
                                                        new Settlement(
                                                                rs.getString("outcome_status"),
                                                                rs.getString("outcome")));
                                        case "CANCELLED" ->
                                                Optional.of(new Settlement("CANCELLED", null));
                                        default -> Optional.<Settlement>empty();
                                    },
                            "Failed to read intent " + intent.key())
                    .flatMap(s -> s);
        } catch (PersistenceException e) {
            throw new RunPreemptedException("Store unreachable: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Offer> offer(String tenantId, int max) {
        return jdbc.queryList(
                SQL_OFFER,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setInt(2, max);
                    ps.setDouble(3, ackSeconds);
                },
                rs ->
                        new Offer(
                                rs.getString("offer_token"),
                                ToolCall.id(rs.getString("execution_id"), rs.getString("call_key")),
                                rs.getString("tool_name"),
                                arguments(rs.getString("arguments")),
                                callMillis),
                "Failed to offer intents of " + tenantId);
    }

    @Override
    public Ack ack(String tenantId, String token) {
        return jdbc.queryOne(
                                SQL_ACK,
                                ps -> {
                                    ps.setDouble(1, callSeconds);
                                    ps.setString(2, token);
                                    ps.setString(3, tenantId);
                                },
                                rs -> rs.getLong("id"),
                                "Failed to acknowledge an offer")
                        .isPresent()
                ? Ack.STARTED
                : Ack.NOT_OFFERED;
    }

    @Override
    public Completion complete(String tenantId, String token, Settlement outcome) {
        boolean settled =
                jdbc.queryOne(
                                SQL_COMPLETE,
                                ps -> {
                                    ps.setString(1, outcome.status());
                                    ps.setString(2, outcome.outcome());
                                    ps.setString(3, token);
                                    ps.setString(4, tenantId);
                                },
                                rs -> rs.getLong("id"),
                                "Failed to complete an intent")
                        .isPresent();
        if (settled) {
            return Completion.SETTLED;
        }
        return jdbc.queryOne(
                        SQL_TOKEN_STATE,
                        ps -> {
                            ps.setString(1, token);
                            ps.setString(2, tenantId);
                        },
                        rs -> rs.getString("state"),
                        "Failed to read an intent")
                .map(_ -> Completion.ALREADY_SETTLED)
                .orElse(Completion.UNKNOWN);
    }

    @Override
    public void expireDeadlines() {
        jdbc.queryOne(
                SQL_EXPIRE, _ -> {}, rs -> rs.getLong("signalled"), "Failed to apply deadlines");
    }

    private Map<String, Object> arguments(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Unreadable intent arguments", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Failed to serialize tool arguments", e);
        }
    }

    private static <T> T preempting(Claim claim, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (PersistenceException e) {
            throw new RunPreemptedException(
                    "Store unreachable for " + claim + ": " + e.getMessage(), e);
        }
    }
}
