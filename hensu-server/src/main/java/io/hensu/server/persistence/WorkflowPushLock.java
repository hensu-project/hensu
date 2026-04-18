package io.hensu.server.persistence;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/// Cluster-wide mutex for workflow definition push operations.
///
/// Backed by PostgreSQL advisory transaction locks (`pg_advisory_xact_lock`) when a
/// DataSource is active so pushes from multiple server nodes for the same
/// `(tenant, workflow)` pair serialize across the cluster. Falls back to a JVM-local
/// {@link ReentrantLock} map for the {@code inmem} profile.
///
/// ### Why advisory locks
/// Postgres advisory locks are independent of row locks and have zero effect on
/// regular queries — pure coordination primitives. Acquired inside an explicit
/// transaction, the lock auto-releases on commit/rollback, so a crashed claimant
/// never wedges a tenant.
///
/// @implNote Thread-safe. Active mode is decided once at {@link PostConstruct}; the
/// JVM-local map is only consulted when JDBC is unavailable.
@ApplicationScoped
public class WorkflowPushLock {

    private static final Logger LOG = Logger.getLogger(WorkflowPushLock.class);

    static final String SQL_ADVISORY_LOCK = "SELECT pg_advisory_xact_lock(?, ?)";

    @Inject Config config;
    @Inject Instance<DataSource> dataSourceInstance;

    private final ConcurrentMap<String, ReentrantLock> jvmLocks = new ConcurrentHashMap<>();
    private DataSource dataSource;
    private boolean distributed;

    @PostConstruct
    void init() {
        boolean dsActive =
                config.getOptionalValue("quarkus.datasource.active", Boolean.class).orElse(true);
        distributed = dsActive && dataSourceInstance.isResolvable();
        if (distributed) {
            dataSource = dataSourceInstance.get();
        }
        LOG.infov("Workflow push lock initialized: distributed={0}", distributed);
    }

    /// Runs `work` while holding an exclusive lock keyed on `(tenant, workflow)`.
    ///
    /// @param tenantId   tenant scope, not null
    /// @param workflowId workflow scope, not null
    /// @param work       critical section, not null
    /// @return whatever `work` returns
    public <T> T withLock(String tenantId, String workflowId, Supplier<T> work) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(work, "work must not be null");
        return distributed
                ? withAdvisoryLock(tenantId, workflowId, work)
                : withJvmLock(tenantId, workflowId, work);
    }

    private <T> T withJvmLock(String tenantId, String workflowId, Supplier<T> work) {
        ReentrantLock lock =
                jvmLocks.computeIfAbsent(tenantId + ":" + workflowId, _ -> new ReentrantLock());
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    private <T> T withAdvisoryLock(String tenantId, String workflowId, Supplier<T> work) {
        try (var conn = dataSource.getConnection()) {
            boolean priorAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (var ps = conn.prepareStatement(SQL_ADVISORY_LOCK)) {
                    ps.setInt(1, tenantId.hashCode());
                    ps.setInt(2, workflowId.hashCode());
                    ps.execute();
                }
                T result = work.get();
                conn.commit();
                return result;
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(priorAutoCommit);
            }
        } catch (SQLException e) {
            throw new PersistenceException(
                    "Failed to acquire advisory lock for " + tenantId + ":" + workflowId, e);
        }
    }
}
