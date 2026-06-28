-- Per-node retry budgets for bounded transitions (ticket #73).
--
-- Stores the namespaced retry counter map (keyed by "namespace:nodeId") so that
-- bounded revise/retry budgets survive checkpoint, resume, and recovery. The
-- '{}' default backfills pre-migration rows with an empty counter map, so no
-- data migration is required.
ALTER TABLE runtime.execution_states
    ADD COLUMN retry_counters JSONB NOT NULL DEFAULT '{}';
