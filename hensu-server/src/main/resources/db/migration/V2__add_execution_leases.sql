-- Distributed recovery leasing
--
-- Tracks which server node currently owns an active execution and when it last
-- sent a heartbeat. The recovery sweeper uses last_heartbeat_at to detect
-- crashed nodes and reclaim their orphaned executions.
--
-- Lease state semantics:
--   Actively running : server_node_id = '<uuid>', last_heartbeat_at = recent
--   Safely paused    : server_node_id = NULL,     last_heartbeat_at = NULL
--   Orphaned (crash) : server_node_id = '<uuid>', last_heartbeat_at = stale

ALTER TABLE hensu.execution_states
    ADD COLUMN server_node_id    TEXT,
    ADD COLUMN last_heartbeat_at TIMESTAMPTZ;

-- Partial index covering only rows with an active lease.
-- Supports the heartbeat UPDATE (server_node_id = ?) and the
-- sweeper range query (server_node_id IS NOT NULL AND last_heartbeat_at < ?).
CREATE INDEX idx_exec_states_active_leases
    ON hensu.execution_states (server_node_id, last_heartbeat_at)
    WHERE server_node_id IS NOT NULL;
