ALTER TABLE runtime.execution_states ADD COLUMN phase JSONB NOT NULL DEFAULT '{"type":"initial"}';

-- Soft-delete support for workflows.
--
-- Instead of hard-deleting rows (which violates FK constraints from
-- execution_states), we mark workflows as deleted with a timestamp.
-- All queries filter on `deleted_at IS NULL` so soft-deleted workflows
-- are invisible to the application.
ALTER TABLE runtime.workflows ADD COLUMN deleted_at TIMESTAMPTZ;
