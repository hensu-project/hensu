package io.hensu.server.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.plan.PlanSnapshot;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.WorkflowStateRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.sql.DataSource;

/// PostgreSQL-backed workflow execution state repository.
///
/// Stores {@link HensuSnapshot} records with JSONB columns for `context`,
/// `history`, and `active_plan`. Each execution has at most one row —
/// checkpoints overwrite the previous state (UPSERT semantics).
///
/// ### Lease Management
/// `save()` automatically maintains the distributed recovery lease:
/// - `checkpoint_reason = "checkpoint"` — sets `server_node_id` and bumps `last_heartbeat_at`
/// - Any terminal reason (`"completed"`, `"paused"`, `"failed"`, `"rejected"`) — clears
/// both to NULL
///
/// This ensures the recovery sweeper only targets executions that are actively
/// running on a now-dead node, never safely-paused human-review executions.
///
/// ### Contracts
/// - **Precondition**: Flyway migration `V1__create_schema` has run
/// - **Postcondition**: `save` is idempotent per `(tenant_id, execution_id)`
///
/// @implNote Thread-safe. Each call acquires its own JDBC connection from the
/// Agroal pool via {@link JdbcSupport}. Blocking I/O is acceptable because the
/// server runs on virtual threads.
///
/// @see HensuSnapshot
/// @see ExecutionLeaseManager
/// @see JdbcWorkflowRepository
public class JdbcWorkflowStateRepository implements WorkflowStateRepository {

    // --- SQL constants ---

    private static final String SQL_SAVE =
            """
            INSERT INTO runtime.execution_states
                (tenant_id, execution_id, workflow_id, current_node_id,
                 context, history, active_plan, checkpoint_reason, created_at,
                 server_node_id, last_heartbeat_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, execution_id)
            DO UPDATE SET
                current_node_id   = EXCLUDED.current_node_id,
                context           = EXCLUDED.context,
                history           = EXCLUDED.history,
                active_plan       = EXCLUDED.active_plan,
                checkpoint_reason = EXCLUDED.checkpoint_reason,
                server_node_id    = EXCLUDED.server_node_id,
                last_heartbeat_at = EXCLUDED.last_heartbeat_at
            """;

    private static final String SQL_FIND_BY_EXECUTION_ID =
            """
            SELECT workflow_id, current_node_id, context, history,
                   active_plan, checkpoint_reason, created_at
            FROM runtime.execution_states
            WHERE tenant_id = ? AND execution_id = ?
            """;

    /// Only returns executions that are safely paused for human review
    /// (server_node_id IS NULL ensures active checkpoints are excluded).
    private static final String SQL_FIND_PAUSED =
            """
            SELECT execution_id, workflow_id, current_node_id, context, history,
                   active_plan, checkpoint_reason, created_at
            FROM runtime.execution_states
            WHERE tenant_id = ? AND current_node_id IS NOT NULL AND server_node_id IS NULL
            ORDER BY created_at
            """;

    private static final String SQL_FIND_BY_WORKFLOW_ID =
            """
            SELECT execution_id, workflow_id, current_node_id, context, history,
                   active_plan, checkpoint_reason, created_at
            FROM runtime.execution_states
            WHERE tenant_id = ? AND workflow_id = ?
            ORDER BY created_at
            """;

    private static final String SQL_DELETE =
            "DELETE FROM runtime.execution_states WHERE tenant_id = ? AND execution_id = ?";

    private static final String SQL_DELETE_ALL =
            "DELETE FROM runtime.execution_states WHERE tenant_id = ?";

    // --- Fields ---

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcSupport jdbc;
    private final ObjectMapper objectMapper;
    private final String serverNodeId;

    /// Creates a repository backed by the given data source.
    ///
    /// @param dataSource the JDBC connection pool, not null
    /// @param objectMapper Jackson mapper for JSONB serialization, not null
    /// @param serverNodeId unique identifier of this server instance, not null
    public JdbcWorkflowStateRepository(
            DataSource dataSource, ObjectMapper objectMapper, String serverNodeId) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcSupport(dataSource);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
    }

    @Override
    public void save(String tenantId, HensuSnapshot snapshot) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        // Active checkpoints hold the lease; terminal states release it.
        boolean active = "checkpoint".equals(snapshot.checkpointReason());
        String leaseNodeId = active ? serverNodeId : null;
        OffsetDateTime heartbeatAt = active ? OffsetDateTime.now(ZoneOffset.UTC) : null;

        jdbc.update(
                SQL_SAVE,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, snapshot.executionId());
                    ps.setString(3, snapshot.workflowId());
                    ps.setString(4, snapshot.currentNodeId());
                    ps.setString(5, writeJson(snapshot.context()));
                    ps.setString(6, writeJson(snapshot.history()));
                    ps.setString(
                            7,
                            snapshot.activePlan() != null
                                    ? writeJson(snapshot.activePlan())
                                    : null);
                    ps.setString(8, snapshot.checkpointReason());
                    ps.setObject(9, OffsetDateTime.ofInstant(snapshot.createdAt(), ZoneOffset.UTC));
                    ps.setString(10, leaseNodeId);
                    ps.setObject(11, heartbeatAt);
                },
                "Failed to save execution state: " + snapshot.executionId());
    }

    @Override
    public Optional<HensuSnapshot> findByExecutionId(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");

        return jdbc.queryOne(
                SQL_FIND_BY_EXECUTION_ID,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, executionId);
                },
                rs -> mapSnapshot(executionId, rs),
                "Failed to find execution state: " + executionId);
    }

    @Override
    public List<HensuSnapshot> findPaused(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        return jdbc.queryList(
                SQL_FIND_PAUSED,
                ps -> ps.setString(1, tenantId),
                rs -> mapSnapshot(rs.getString("execution_id"), rs),
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
                rs -> mapSnapshot(rs.getString("execution_id"), rs),
                "Failed to find executions for workflow: " + workflowId);
    }

    @Override
    public boolean delete(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");

        return jdbc.update(
                        SQL_DELETE,
                        ps -> {
                            ps.setString(1, tenantId);
                            ps.setString(2, executionId);
                        },
                        "Failed to delete execution state: " + executionId)
                > 0;
    }

    @Override
    public int deleteAllForTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        return jdbc.update(
                SQL_DELETE_ALL,
                ps -> ps.setString(1, tenantId),
                "Failed to delete execution states for tenant: " + tenantId);
    }

    // --- Internal helpers ---

    private HensuSnapshot mapSnapshot(String executionId, ResultSet rs) throws SQLException {
        Map<String, Object> context = readJson(rs.getString("context"), MAP_TYPE);
        ExecutionHistory history = readJson(rs.getString("history"), ExecutionHistory.class);
        String activePlanJson = rs.getString("active_plan");
        PlanSnapshot activePlan =
                activePlanJson != null ? readJson(activePlanJson, PlanSnapshot.class) : null;
        OffsetDateTime createdOdt = rs.getObject("created_at", OffsetDateTime.class);
        Instant createdAt = createdOdt != null ? createdOdt.toInstant() : Instant.now();

        // Use HashMap for context — HensuSnapshot compact constructor calls Map.copyOf(),
        // but HensuSnapshot.toState() needs the Builder to produce a mutable copy for
        // resumed execution. Passing a mutable map here is safe because copyOf() in the
        // compact constructor makes it immutable inside the snapshot.
        return new HensuSnapshot(
                rs.getString("workflow_id"),
                executionId,
                rs.getString("current_node_id"),
                new HashMap<>(context),
                history,
                activePlan,
                createdAt,
                rs.getString("checkpoint_reason"));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Failed to serialize to JSON", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Failed to deserialize from JSON", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Failed to deserialize from JSON", e);
        }
    }
}
