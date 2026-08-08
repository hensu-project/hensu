-- Remove the active_plan column from execution_states.
-- The plan subsystem has been removed; plans are no longer stored in execution state.
ALTER TABLE runtime.execution_states DROP COLUMN IF EXISTS active_plan;
