package io.hensu.server.persistence;

import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.serialization.WorkflowSerializer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/// PostgreSQL-backed workflow definition repository.
///
/// Stores compiled workflow JSON in a JSONB column, using
/// {@link WorkflowSerializer} for serialization. All operations are
/// tenant-scoped via composite primary key `(tenant_id, workflow_id)`.
///
/// ### Contracts
/// - **Precondition**: Flyway migration `V1__create_persistence_tables` has run
/// - **Postcondition**: `save` is idempotent (UPSERT semantics)
///
/// @implNote Thread-safe. Each call acquires its own JDBC connection from the
/// Agroal pool via {@link JdbcSupport}. Blocking I/O is acceptable because the
/// server runs on virtual threads.
///
/// @see WorkflowSerializer#toJson(Workflow)
/// @see WorkflowSerializer#fromJson(String)
public class JdbcWorkflowRepository implements WorkflowRepository {

    // --- SQL constants ---

    private static final String SQL_SAVE =
            """
            INSERT INTO hensu.workflows (tenant_id, workflow_id, definition, created_at, updated_at)
            VALUES (?, ?, ?::jsonb, now(), now())
            ON CONFLICT (tenant_id, workflow_id)
            DO UPDATE SET definition = EXCLUDED.definition, updated_at = now()
            """;

    private static final String SQL_FIND_BY_ID =
            "SELECT definition FROM hensu.workflows WHERE tenant_id = ? AND workflow_id = ?";

    private static final String SQL_FIND_ALL =
            "SELECT definition FROM hensu.workflows WHERE tenant_id = ? ORDER BY workflow_id";

    private static final String SQL_EXISTS =
            "SELECT 1 FROM hensu.workflows WHERE tenant_id = ? AND workflow_id = ?";

    private static final String SQL_DELETE =
            "DELETE FROM hensu.workflows WHERE tenant_id = ? AND workflow_id = ?";

    private static final String SQL_DELETE_ALL = "DELETE FROM hensu.workflows WHERE tenant_id = ?";

    private static final String SQL_COUNT =
            "SELECT count(*) FROM hensu.workflows WHERE tenant_id = ?";

    // --- Fields ---

    private final JdbcSupport jdbc;

    public JdbcWorkflowRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcSupport(dataSource);
    }

    @Override
    public void save(String tenantId, Workflow workflow) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflow, "workflow must not be null");

        String json = WorkflowSerializer.toJson(workflow);
        jdbc.update(
                SQL_SAVE,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, workflow.getId());
                    ps.setString(3, json);
                },
                "Failed to save workflow: " + workflow.getId());
    }

    @Override
    public Optional<Workflow> findById(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");

        return jdbc.queryOne(
                SQL_FIND_BY_ID,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, workflowId);
                },
                rs -> WorkflowSerializer.fromJson(rs.getString("definition")),
                "Failed to find workflow: " + workflowId);
    }

    @Override
    public List<Workflow> findAll(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        return jdbc.queryList(
                SQL_FIND_ALL,
                ps -> ps.setString(1, tenantId),
                rs -> WorkflowSerializer.fromJson(rs.getString("definition")),
                "Failed to list workflows for tenant: " + tenantId);
    }

    @Override
    public boolean exists(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");

        return jdbc.queryOne(
                        SQL_EXISTS,
                        ps -> {
                            ps.setString(1, tenantId);
                            ps.setString(2, workflowId);
                        },
                        _ -> true,
                        "Failed to check workflow existence: " + workflowId)
                .isPresent();
    }

    @Override
    public boolean delete(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");

        return jdbc.update(
                        SQL_DELETE,
                        ps -> {
                            ps.setString(1, tenantId);
                            ps.setString(2, workflowId);
                        },
                        "Failed to delete workflow: " + workflowId)
                > 0;
    }

    @Override
    public int deleteAllForTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        return jdbc.update(
                SQL_DELETE_ALL,
                ps -> ps.setString(1, tenantId),
                "Failed to delete workflows for tenant: " + tenantId);
    }

    @Override
    public int count(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        return jdbc.queryOne(
                        SQL_COUNT,
                        ps -> ps.setString(1, tenantId),
                        rs -> rs.getInt(1),
                        "Failed to count workflows for tenant: " + tenantId)
                .orElse(0);
    }
}
