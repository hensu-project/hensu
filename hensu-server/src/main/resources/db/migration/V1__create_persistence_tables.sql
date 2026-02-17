-- Hensu persistence schema

CREATE SCHEMA IF NOT EXISTS hensu;

-- Workflow definitions pushed from CLI
CREATE TABLE hensu.workflows (
    tenant_id   TEXT        NOT NULL,
    workflow_id TEXT        NOT NULL,
    definition  JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, workflow_id)
);

-- Execution state snapshots (one per execution, overwritten on each checkpoint)
CREATE TABLE hensu.execution_states (
    tenant_id         TEXT        NOT NULL,
    execution_id      TEXT        NOT NULL,
    workflow_id       TEXT        NOT NULL,
    current_node_id   TEXT,
    context           JSONB       NOT NULL DEFAULT '{}',
    history           JSONB       NOT NULL DEFAULT '{}',
    active_plan       JSONB,
    checkpoint_reason TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, execution_id),
    FOREIGN KEY (tenant_id, workflow_id)
        REFERENCES hensu.workflows (tenant_id, workflow_id)
);

CREATE INDEX idx_exec_states_workflow
    ON hensu.execution_states (tenant_id, workflow_id);

-- Partial index: only rows where execution is resumable (not completed)
CREATE INDEX idx_exec_states_paused
    ON hensu.execution_states (tenant_id)
    WHERE current_node_id IS NOT NULL;
