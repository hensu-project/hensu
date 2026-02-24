-- Hensu persistence schema
--
-- Single consolidated migration. The `runtime` schema namespaces all tables
-- inside the `hensu` database, keeping them distinct from any future schemas
-- (e.g., auth, metrics) without name collision.

CREATE SCHEMA IF NOT EXISTS runtime;

-- Workflow definitions pushed from CLI.
CREATE TABLE runtime.workflows (
    tenant_id   TEXT        NOT NULL,
    workflow_id TEXT        NOT NULL,
    definition  JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, workflow_id)
);

-- Execution state snapshots (one per execution, overwritten on each checkpoint).
--
-- Lease columns track which server node currently owns an active execution
-- for distributed failover recovery:
--   Actively running : server_node_id = '<uuid>', last_heartbeat_at = recent
--   Safely paused    : server_node_id = NULL,     last_heartbeat_at = NULL
--   Orphaned (crash) : server_node_id = '<uuid>', last_heartbeat_at = stale
CREATE TABLE runtime.execution_states (
    tenant_id         TEXT        NOT NULL,
    execution_id      TEXT        NOT NULL,
    workflow_id       TEXT        NOT NULL,
    current_node_id   TEXT,
    context           JSONB       NOT NULL DEFAULT '{}',
    history           JSONB       NOT NULL DEFAULT '{}',
    active_plan       JSONB,
    checkpoint_reason TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    server_node_id    TEXT,
    last_heartbeat_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, execution_id),
    FOREIGN KEY (tenant_id, workflow_id)
        REFERENCES runtime.workflows (tenant_id, workflow_id)
);

-- Supports workflow-scoped execution queries.
CREATE INDEX idx_exec_states_workflow
    ON runtime.execution_states (tenant_id, workflow_id);

-- Partial index: only rows where execution is resumable (not completed).
CREATE INDEX idx_exec_states_paused
    ON runtime.execution_states (tenant_id)
    WHERE current_node_id IS NOT NULL;

-- Partial index: only rows with an active lease.
-- Supports heartbeat UPDATE (server_node_id = ?) and sweeper range query
-- (server_node_id IS NOT NULL AND last_heartbeat_at < threshold).
CREATE INDEX idx_exec_states_active_leases
    ON runtime.execution_states (server_node_id, last_heartbeat_at)
    WHERE server_node_id IS NOT NULL;
