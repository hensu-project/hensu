-- Records every change to an execution row with the DB clock, so the checker can
-- order claims, renewals and terminal writes against what the relay saw. Installed
-- by the test after the first node has run the migrations; never part of the
-- product schema.
CREATE SCHEMA IF NOT EXISTS fault_test;

CREATE TABLE IF NOT EXISTS fault_test.lease_audit (
    id           BIGSERIAL   PRIMARY KEY,
    at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    op           TEXT        NOT NULL,
    tenant_id    TEXT        NOT NULL,
    execution_id TEXT        NOT NULL,
    old_row      JSONB,
    new_row      JSONB
);

CREATE OR REPLACE FUNCTION fault_test.audit_execution() RETURNS trigger AS $$
BEGIN
    INSERT INTO fault_test.lease_audit (op, tenant_id, execution_id, old_row, new_row)
    VALUES (TG_OP,
            COALESCE(NEW.tenant_id, OLD.tenant_id),
            COALESCE(NEW.execution_id, OLD.execution_id),
            CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) - 'context' - 'history' END,
            CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE to_jsonb(NEW) - 'context' - 'history' END);
    RETURN NULL;
END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS fault_test_audit ON runtime.execution_states;
CREATE TRIGGER fault_test_audit
    AFTER INSERT OR UPDATE OR DELETE ON runtime.execution_states
    FOR EACH ROW EXECUTE FUNCTION fault_test.audit_execution();
