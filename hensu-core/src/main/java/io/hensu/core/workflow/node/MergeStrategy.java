package io.hensu.core.workflow.node;

/// Strategy for merging outputs from forked sub-flow execution paths.
///
/// Applied by {@link io.hensu.core.execution.executor.ForkNodeExecutor} after
/// all sub-flows complete. The merged result is stored in state under the
/// {@link JoinNode}'s output field.
public enum MergeStrategy {
    /// Collect all outputs into a map keyed by target node ID. Output: Map&lt;String, Object&gt;
    /// where key is target ID, value is output.
    COLLECT_ALL,

    /// Return the output of the first successful branch in definition order.
    ///
    /// @implNote All futures are awaited before merging — this is **not** a race pattern.
    /// "First" means first in fork definition order, not chronologically first to finish.
    /// Rename from {@code FIRST_COMPLETED} which implied racing semantics it does not have.
    FIRST_SUCCESSFUL,

    /// Concatenate all string outputs with newlines. Non-string outputs are converted to string.
    CONCATENATE,

    /// Merge all outputs assuming they are maps. Later outputs override earlier ones for duplicate
    /// keys.
    MERGE_MAPS
}
