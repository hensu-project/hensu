-- The journaled relay-pull protocol (docs/multi-node-execution-protocol.md, section 3.1).
--
-- Replaces node-id leases judged by heartbeat age with four things every node can act on
-- alike:
--
--   * a claim per execution, carrying an epoch that every write compares, and an expiry
--     written and judged by the database clock alone;
--   * a journal of the steps a run cannot reproduce – model turns, nested-flow
--     checkpoints – so a recovered run replays its predecessor's decisions;
--   * tool calls as durable intents that the tenant's relay pulls from any node,
--     acknowledges before executing, and completes through any node;
--   * an optional idempotency key per start, so a client that retries a start whose
--     answer it lost gets the execution it already created instead of a second one.
--
-- No row here records which node a tenant's relay talks to: nothing depends on it.

-- ---------------------------------------------------------------- claims

ALTER TABLE runtime.execution_states
    ADD COLUMN run_state        TEXT,
    ADD COLUMN claim_epoch      BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN claim_owner      TEXT,
    ADD COLUMN claim_expires_at TIMESTAMPTZ,
    ADD COLUMN step             BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN start_key        TEXT,
    ADD COLUMN start_digest     TEXT;

-- A row a node still owned under the old leases was mid-run; it becomes RUNNABLE so the
-- next pickup resumes it from its checkpoint. An unowned row was paused or finished.
UPDATE runtime.execution_states
   SET run_state = CASE
           WHEN checkpoint_reason IN ('completed', 'failed', 'rejected') THEN 'DONE'
           WHEN server_node_id IS NOT NULL THEN 'RUNNABLE'
           ELSE 'PAUSED'
       END;

ALTER TABLE runtime.execution_states
    ALTER COLUMN run_state SET NOT NULL,
    ADD CONSTRAINT execution_states_run_state
        CHECK (run_state IN ('RUNNING', 'RUNNABLE', 'PAUSED', 'DONE')),
    -- Only a running execution has a claim holder and an expiry, and it always has both.
    ADD CONSTRAINT execution_states_claim_shape
        CHECK ((run_state = 'RUNNING') = (claim_owner IS NOT NULL AND claim_expires_at IS NOT NULL));

DROP INDEX runtime.idx_exec_states_active_leases;

ALTER TABLE runtime.execution_states
    DROP COLUMN server_node_id,
    DROP COLUMN last_heartbeat_at;

-- One execution per client idempotency key and tenant; start_digest fingerprints the
-- request the key was first sent with, so a different request reusing it is refused.
CREATE UNIQUE INDEX idx_exec_states_start_key
    ON runtime.execution_states (tenant_id, start_key) WHERE start_key IS NOT NULL;

-- Serves the pickup sweep: expired claims and released executions.
CREATE INDEX idx_exec_states_claimable
    ON runtime.execution_states (claim_expires_at NULLS FIRST)
    WHERE run_state IN ('RUNNING', 'RUNNABLE');

-- --------------------------------------------------------------- journal

-- Model turns of the node attempts a run has not yet checkpointed past ('turn', keyed by
-- the attempt's position and the turn index), and the latest checkpoint of each nested
-- flow ('flow', keyed by the flow's scope). main_step is the main-flow step a record
-- belongs to: a main-flow checkpoint at step s deletes everything below s.
CREATE TABLE runtime.execution_journal (
    tenant_id    TEXT    NOT NULL,
    execution_id TEXT    NOT NULL,
    main_step    BIGINT  NOT NULL,
    position     TEXT    NOT NULL,
    kind         TEXT    NOT NULL CHECK (kind IN ('turn', 'flow')),
    idx          INTEGER NOT NULL,
    payload      TEXT    NOT NULL,
    PRIMARY KEY (tenant_id, execution_id, kind, position, idx),
    FOREIGN KEY (tenant_id, execution_id)
        REFERENCES runtime.execution_states (tenant_id, execution_id) ON DELETE CASCADE
);

-- ---------------------------------------------------------- tool intents

-- One row per tool call. Inserted under the caller's claim epoch before any relay can see
-- it; offered to a polling relay under a single-use token; started when the relay
-- acknowledges that token; settled once. The first outcome wins.
--
--   PENDING   – waiting for a relay to poll; UNAVAILABLE at dispatch_deadline_at
--   OFFERED   – handed to a poll; back to PENDING if not acknowledged by offer_expires_at
--   STARTED   – acknowledged, the relay may be executing it; TIMEOUT at deadline_at
--   DONE      – settled with outcome_status and outcome
--   CANCELLED – the execution finished before a relay took it
CREATE TABLE runtime.tool_intents (
    id                   BIGSERIAL   PRIMARY KEY,
    tenant_id            TEXT        NOT NULL,
    execution_id         TEXT        NOT NULL,
    call_key             TEXT        NOT NULL,
    tool_name            TEXT        NOT NULL,
    -- Cleared once settled: the redacted copy in tool_audit is the record.
    arguments            JSONB,
    -- SHA-256 of the canonical arguments, kept after they are cleared: a replay that
    -- reaches this key with different arguments has diverged.
    arguments_digest     TEXT        NOT NULL,
    state                TEXT        NOT NULL
        CHECK (state IN ('PENDING', 'OFFERED', 'STARTED', 'DONE', 'CANCELLED')),
    created_epoch        BIGINT      NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatch_deadline_at TIMESTAMPTZ NOT NULL,
    offer_token          TEXT,
    offer_expires_at     TIMESTAMPTZ,
    deadline_at          TIMESTAMPTZ,
    outcome_status       TEXT,
    -- Cleared when the execution pauses or finishes: only a replay of the call's own
    -- attempt reads it, and tool_audit keeps the result.
    outcome              JSONB,
    settled_at           TIMESTAMPTZ,
    UNIQUE (tenant_id, execution_id, call_key),
    FOREIGN KEY (tenant_id, execution_id)
        REFERENCES runtime.execution_states (tenant_id, execution_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_tool_intents_token
    ON runtime.tool_intents (offer_token) WHERE offer_token IS NOT NULL;
CREATE INDEX idx_tool_intents_pending
    ON runtime.tool_intents (tenant_id, id) WHERE state = 'PENDING';
CREATE INDEX idx_tool_intents_open
    ON runtime.tool_intents (state) WHERE state IN ('PENDING', 'OFFERED', 'STARTED');

-- -------------------------------------------------------------- catalogs

-- The tool catalog each tenant's relay published, with the version every poll carries.
CREATE TABLE runtime.mcp_catalogs (
    tenant_id  TEXT        PRIMARY KEY,
    version    TEXT        NOT NULL,
    tools      JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------ tool audit

-- A recovered run replays the calls its predecessor made and reports them again; the
-- call id is stable across replays, so each call is recorded once.
ALTER TABLE runtime.tool_audit ADD COLUMN call_id TEXT;
CREATE UNIQUE INDEX idx_tool_audit_call
    ON runtime.tool_audit (tenant_id, call_id) WHERE call_id IS NOT NULL;
