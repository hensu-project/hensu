package io.hensu.server.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// {@link ToolAuditRepository} for deployments that have no database.
///
/// The `inmem` profile and the behaviour tests run without a data source, while the
/// listener that writes the trail is composed unconditionally — which is the whole point
/// of it. So the trail needs somewhere to go that is not a silent discard: a test
/// asserting "the denied call left a row" should be able to assert it wherever the run
/// stored one.
///
/// Entries live as long as the process. This is not durability and does not claim to be;
/// it is the same record, in the only place a database-less deployment can keep it.
///
/// @implNote Thread-safe. Entries for one execution are appended under that list's own
///     monitor, because parallel fan-out branches settle tool calls concurrently.
/// @see JdbcToolAuditRepository for the durable implementation
public class InMemoryToolAuditRepository implements ToolAuditRepository {

    private final Map<String, List<ToolAuditEntry>> entries = new ConcurrentHashMap<>();

    @Override
    public void record(String tenantId, ToolAuditEntry entry) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        List<ToolAuditEntry> forExecution =
                entries.computeIfAbsent(key(tenantId, entry.executionId()), _ -> new ArrayList<>());
        synchronized (forExecution) {
            forExecution.add(entry);
        }
    }

    @Override
    public List<ToolAuditEntry> findByExecution(String tenantId, String executionId) {
        List<ToolAuditEntry> forExecution = entries.get(key(tenantId, executionId));
        if (forExecution == null) {
            return List.of();
        }
        synchronized (forExecution) {
            return List.copyOf(forExecution);
        }
    }

    /// Drops every stored entry.
    ///
    /// @apiNote Test hook for suites that reset state between cases.
    public void clear() {
        entries.clear();
    }

    private static String key(String tenantId, String executionId) {
        return tenantId + ' ' + executionId;
    }
}
