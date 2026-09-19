package io.hensu.server.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

/// PostgreSQL-backed {@link ToolAuditRepository}.
///
/// A plain class rather than a CDI bean, like the other JDBC repositories: the producer
/// decides whether a data source exists and instantiates this or the no-op counterpart.
/// That keeps the decision in one place and keeps `inmem` profiles from needing a
/// database to start.
///
/// ### Contracts
/// - **Postcondition**: {@link #record} logs and returns on any failure, never throws
/// - **Invariant**: SQL is a `static final` constant; values bind through parameters only
///
/// @implNote Thread-safe and stateless beyond the pool. Each call takes and releases one
///     connection, which is acceptable because tool calls are already dominated by the
///     latency of whatever they invoke.
/// @see ToolAuditRepository for the contract
public class JdbcToolAuditRepository implements ToolAuditRepository {

    private static final Logger logger = Logger.getLogger(JdbcToolAuditRepository.class.getName());

    private static final String SQL_INSERT =
            """
            INSERT INTO runtime.tool_audit
                (tenant_id, execution_id, node_id, agent_id, tool_name, status, exit_code,
                 duration_ms, occurred_at, resolved_argv, arguments, output)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """;

    private static final String SQL_FIND_BY_EXECUTION =
            """
            SELECT execution_id, node_id, agent_id, tool_name, status, exit_code, duration_ms,
                   occurred_at, resolved_argv, arguments, output
            FROM runtime.tool_audit
            WHERE tenant_id = ? AND execution_id = ?
            ORDER BY occurred_at, id
            """;

    private static final TypeReference<List<String>> ARGV_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE =
            new TypeReference<>() {};

    private final JdbcSupport jdbc;
    private final ObjectMapper objectMapper;

    /// Creates a repository backed by the given data source.
    ///
    /// @param dataSource the JDBC connection pool, not null
    /// @param objectMapper Jackson mapper for JSONB serialization, not null
    public JdbcToolAuditRepository(DataSource dataSource, ObjectMapper objectMapper) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcSupport(dataSource);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void record(String tenantId, ToolAuditEntry entry) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        try {
            jdbc.update(
                    SQL_INSERT,
                    ps -> {
                        ps.setString(1, tenantId);
                        ps.setString(2, entry.executionId());
                        ps.setString(3, entry.nodeId());
                        ps.setString(4, entry.agentId());
                        ps.setString(5, entry.toolName());
                        ps.setString(6, entry.status());
                        if (entry.exitCode() == null) {
                            ps.setNull(7, Types.INTEGER);
                        } else {
                            ps.setInt(7, entry.exitCode());
                        }
                        ps.setLong(8, entry.durationMs());
                        ps.setObject(
                                9, OffsetDateTime.ofInstant(entry.occurredAt(), ZoneOffset.UTC));
                        ps.setString(10, entry.argv().isEmpty() ? null : writeJson(entry.argv()));
                        ps.setString(11, writeJson(entry.arguments()));
                        ps.setString(12, entry.output());
                    },
                    "Failed to record tool audit entry: " + entry.toolName());
        } catch (RuntimeException e) {
            // Deliberate: the run has already had its real effect, and losing the trail is
            // strictly better than failing the workflow that produced it.
            logger.log(
                    Level.WARNING,
                    "Tool audit entry for '"
                            + entry.toolName()
                            + "' in execution "
                            + entry.executionId()
                            + " was not stored: "
                            + e.getMessage(),
                    e);
        }
    }

    @Override
    public List<ToolAuditEntry> findByExecution(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        return jdbc.queryList(
                SQL_FIND_BY_EXECUTION,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, executionId);
                },
                this::mapEntry,
                "Failed to read tool audit for execution: " + executionId);
    }

    private ToolAuditEntry mapEntry(ResultSet rs) throws SQLException {
        return new ToolAuditEntry(
                rs.getString("execution_id"),
                rs.getString("node_id"),
                rs.getString("agent_id"),
                rs.getString("tool_name"),
                rs.getString("status"),
                rs.getObject("exit_code") == null ? null : rs.getInt("exit_code"),
                rs.getLong("duration_ms"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                readJson(rs.getString("resolved_argv"), ARGV_TYPE, List.of()),
                readJson(rs.getString("arguments"), ARGUMENTS_TYPE, Map.of()),
                rs.getString("output"));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new PersistenceException("Failed to serialize tool audit field", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            logger.warning("Unreadable tool audit field, treating it as absent: " + e.getMessage());
            return fallback;
        }
    }
}
