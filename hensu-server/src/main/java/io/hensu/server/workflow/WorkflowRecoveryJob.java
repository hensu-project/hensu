package io.hensu.server.workflow;

import io.hensu.server.persistence.ExecutionLeaseManager;
import io.hensu.server.persistence.ExecutionLeaseManager.ExecutionRef;
import io.hensu.server.validation.LogSanitizer;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/// Scheduled job that detects and resumes orphaned workflow executions.
///
/// On each tick, claims all rows whose `last_heartbeat_at` is older than the
/// stale threshold — indicating the owning node has crashed — then delegates
/// each claimed execution to {@link WorkflowService#resumeExecution} to
/// continue from the last checkpoint.
///
/// Safely-paused executions (awaiting human review) always have
/// `server_node_id = NULL` and are never visible to this sweeper.
///
/// No-op when the datasource is inactive (e.g., the {@code inmem} profile).
///
/// ### Configuration
/// | Property                        | Default | Description                                      |
/// |---------------------------------|---------|--------------------------------------------------|
/// | `hensu.lease.recovery-interval` | `60s`   | How often the sweeper polls for orphaned rows    |
/// | `hensu.lease.stale-threshold`   | `90s`   | Age of `last_heartbeat_at` to declare a row dead |
///
/// @implNote Thread-safe. `claimStaleExecutions` is atomic under PostgreSQL
/// `READ COMMITTED` — two concurrent sweepers cannot claim the same row.
/// Each `resumeExecution` call runs synchronously on a virtual thread.
///
/// @see ExecutionLeaseManager#claimStaleExecutions(Instant)
/// @see ExecutionHeartbeatJob
@ApplicationScoped
public class WorkflowRecoveryJob {

    private static final Logger LOG = Logger.getLogger(WorkflowRecoveryJob.class);

    private final ExecutionLeaseManager leaseManager;
    private final WorkflowService workflowService;

    @ConfigProperty(name = "hensu.lease.stale-threshold", defaultValue = "90s")
    Duration staleThreshold;

    @Inject
    public WorkflowRecoveryJob(
            ExecutionLeaseManager leaseManager, WorkflowService workflowService) {
        this.leaseManager = leaseManager;
        this.workflowService = workflowService;
    }

    /// Claims and resumes all orphaned executions whose heartbeat is stale.
    ///
    /// Skips execution when the datasource is inactive.
    @Scheduled(every = "${hensu.lease.recovery-interval:60s}")
    void tick() {
        if (!leaseManager.isActive()) return;

        Instant threshold = Instant.now().minus(staleThreshold);
        List<ExecutionRef> stale = leaseManager.claimStaleExecutions(threshold);

        if (stale.isEmpty()) return;

        LOG.infov("Recovering {0} orphaned execution(s)", stale.size());

        for (ExecutionRef ref : stale) {
            try {
                workflowService.resumeExecution(ref.tenantId(), ref.executionId(), null);
                LOG.infov("Recovered execution: {0}", LogSanitizer.sanitize(ref.executionId()));
            } catch (Exception e) {
                LOG.errorv(
                        e,
                        "Failed to recover execution: {0}",
                        LogSanitizer.sanitize(ref.executionId()));
            }
        }
    }
}
