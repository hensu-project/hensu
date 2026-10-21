package io.hensu.server.workflow;

import io.hensu.server.mcp.McpSessionManager;
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
/// The same tick refreshes this node's rows in `runtime.mcp_sessions` for the
/// tenants whose relay is connected here, so a session and the leases it serves
/// go stale on one schedule: a node that dies leaves session rows that stop
/// blocking claims at the same moment its leases become claimable. That
/// refresh is queued on the session manager's serial writer rather than run
/// here, so it is ordered against the publish and clear writes of sessions
/// that begin and end meanwhile.
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
/// @see McpSessionManager#refreshSessions()
/// @see WorkflowRecoveryJob
@ApplicationScoped
public class ExecutionHeartbeatJob {

    private static final Logger LOG = Logger.getLogger(ExecutionHeartbeatJob.class);

    private final ExecutionLeaseManager leaseManager;
    private final McpSessionManager sessionManager;

    @Inject
    public ExecutionHeartbeatJob(
            ExecutionLeaseManager leaseManager, McpSessionManager sessionManager) {
        this.leaseManager = leaseManager;
        this.sessionManager = sessionManager;
    }

    /// Renews all active leases held by this node.
    ///
    /// Skips execution when the datasource is inactive.
    @Scheduled(every = "${hensu.lease.heartbeat-interval:30s}")
    void tick() {
        if (!leaseManager.isActive()) return;
        leaseManager.updateHeartbeats();
        sessionManager.refreshSessions();
        LOG.debugv("Lease heartbeat renewed for node: {0}", leaseManager.getServerNodeId());
    }
}
