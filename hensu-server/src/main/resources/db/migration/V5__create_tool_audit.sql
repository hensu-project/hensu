-- Durable record of every tool invocation an execution made.
--
-- The listener hooks give the events a status, an exit code and a timestamp, but they
-- live only as long as the process. What the security model calls a complete record has
-- to outlive it: an operator asking "what did this run actually do to my machine" after
-- the fact has no other source, and a log line that a configuration flag can switch off
-- is a debugging aid rather than a record.
--
-- One row per settled call, including the refused ones. A denial that left no row would
-- make "the run did nothing" and "the run was stopped from doing something" look the
-- same.
CREATE TABLE runtime.tool_audit (
    id            BIGSERIAL   PRIMARY KEY,
    tenant_id     TEXT        NOT NULL,
    execution_id  TEXT        NOT NULL,
    node_id       TEXT        NOT NULL,
    agent_id      TEXT        NOT NULL,
    tool_name     TEXT        NOT NULL,
    status        TEXT        NOT NULL,
    exit_code     INTEGER,
    duration_ms   BIGINT      NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    -- The invocation as it ran, for process-backed calls. NULL for tools that run no
    -- process of their own, which is every tool the server itself can reach today.
    resolved_argv JSONB,
    -- Argument keys and values, with every parameter declared `secret:` already replaced
    -- by a redaction marker at the source.
    arguments     JSONB       NOT NULL,
    output        TEXT
);

-- Every question asked of this table starts from one execution.
CREATE INDEX idx_tool_audit_execution ON runtime.tool_audit (tenant_id, execution_id, occurred_at);
