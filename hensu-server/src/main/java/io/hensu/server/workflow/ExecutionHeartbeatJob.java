package io.hensu.server.workflow;

import io.hensu.server.persistence.ExecutionLeaseManager;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/// Scheduled job that renews distributed recovery leases for executions
/// running on this node.
///
/// Periodically bumps `last_heartbeat_at` for all rows where
/// `server_node_id = thisNode`. A surviving node's recovery sweeper
/// ({@link WorkflowRecoveryJob}) uses the absence of a fresh heartbeat to
/// detect crashed nodes and reclaim their orphaned executions.
///
/// No-op when the datasource is inactive (e.g., the {@code inmem} profile).
///
/// ### Configuration
/// | Property                         | Default | Description                      |
/// |----------------------------------|---------|----------------------------------|
/// | `hensu.lease.heartbeat-interval` | `30s`   | How often to renew active leases |
///
/// @implNote Thread-safe. The underlying SQL is a single bulk UPDATE.
///
/// @see ExecutionLeaseManager#updateHeartbeats()
/// @see WorkflowRecoveryJob
@ApplicationScoped
public class ExecutionHeartbeatJob {

    private static final Logger LOG = Logger.getLogger(ExecutionHeartbeatJob.class);

    private final ExecutionLeaseManager leaseManager;

    @Inject
    public ExecutionHeartbeatJob(ExecutionLeaseManager leaseManager) {
        this.leaseManager = leaseManager;
    }

    /// Renews all active leases held by this node.
    ///
    /// Skips execution when the datasource is inactive.
    @Scheduled(every = "${hensu.lease.heartbeat-interval:30s}")
    void tick() {
        if (!leaseManager.isActive()) return;
        leaseManager.updateHeartbeats();
        LOG.debugv("Lease heartbeat renewed for node: {0}", leaseManager.getServerNodeId());
    }
}
