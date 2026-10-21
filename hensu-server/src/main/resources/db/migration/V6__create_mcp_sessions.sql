-- Which node holds each tenant's MCP session.
--
-- A tenant's relay connects inbound to one node over the split pipe, and that stream lives
-- in that node's memory. Execution ownership, by contrast, is a row in execution_states, so
-- without this table a node could lease an execution whose tenant is connected elsewhere
-- and fail at its first tool call. Claiming consults this table: a node does not claim an
-- execution whose tenant's session is held by another node.
--
-- One row per tenant, because a tenant holds at most one session. A second connection
-- supersedes the first, on the same node or across nodes, and the row follows it.
--
-- A row outlives the node that wrote it, so it is authoritative only while fresh. The
-- owning node refreshes last_heartbeat_at on the same schedule as its execution leases,
-- and a row older than hensu.lease.stale-threshold counts as no session at all: it blocks
-- no claim, and the node that claims past it deletes it.
--
-- No foreign key to workflows: a tenant may connect its relay before it pushes anything.
CREATE TABLE runtime.mcp_sessions (
    tenant_id         TEXT        PRIMARY KEY,
    server_node_id    TEXT        NOT NULL,
    connected_at      TIMESTAMPTZ NOT NULL,
    last_heartbeat_at TIMESTAMPTZ NOT NULL
);
