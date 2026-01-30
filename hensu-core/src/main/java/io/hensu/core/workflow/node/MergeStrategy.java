package io.hensu.core.workflow.node;

/// Strategy for merging outputs from forked execution paths in {@link JoinNode}.
public enum MergeStrategy {
    /// Collect all outputs into a map keyed by target node ID. Output: Map&lt;String, Object&gt;
    /// where key is target ID, value is output.
    COLLECT_ALL,

    /// Return only the first completed output (race pattern). Useful when any result is acceptable.
    FIRST_COMPLETED,

    /// Concatenate all string outputs with newlines. Non-string outputs are converted to string.
    CONCATENATE,

    /// Merge all outputs assuming they are maps. Later outputs override earlier ones for duplicate
    /// keys.
    MERGE_MAPS,

    /// Use a custom merge function provided in the join configuration. The function receives
    /// `List<ForkResult>` and returns merged output.
    CUSTOM
}
