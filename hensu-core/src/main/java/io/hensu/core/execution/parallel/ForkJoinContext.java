package io.hensu.core.execution.parallel;

import io.hensu.core.execution.executor.NodeResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/// Context for tracking forked execution paths in fork-join workflows.
///
/// Stores the state of forked executions, including futures for pending work
/// and completed results. Used by {@link io.hensu.core.execution.executor.ForkNodeExecutor}
/// to spawn parallel paths and {@link io.hensu.core.execution.executor.JoinNodeExecutor}
/// to await and merge results.
///
/// @implNote **Thread-safe**. Uses ConcurrentHashMap for storing futures and results,
/// allowing safe access from multiple forked execution threads.
///
/// @see io.hensu.core.execution.executor.ForkNodeExecutor for spawning forks
/// @see io.hensu.core.execution.executor.JoinNodeExecutor for joining results
public class ForkJoinContext {

    private final String forkNodeId;
    private final List<String> targetNodeIds;
    private final Map<String, Future<ForkResult>> futures;
    private final Map<String, ForkResult> completedResults;
    private final long startTimeMs;

    /// Creates a new fork-join context for tracking parallel executions.
    ///
    /// @param forkNodeId identifier of the fork node that created this context, not null
    /// @param targetNodeIds list of target node IDs to execute in parallel, not null
    public ForkJoinContext(String forkNodeId, List<String> targetNodeIds) {
        this.forkNodeId = forkNodeId;
        this.targetNodeIds = List.copyOf(targetNodeIds);
        this.futures = new ConcurrentHashMap<>();
        this.completedResults = new ConcurrentHashMap<>();
        this.startTimeMs = System.currentTimeMillis();
    }

    /// Returns the identifier of the fork node that created this context.
    ///
    /// @return the fork node ID, not null
    public String getForkNodeId() {
        return forkNodeId;
    }

    /// Returns the list of target node IDs being executed.
    ///
    /// @return immutable list of target node IDs, not null
    public List<String> getTargetNodeIds() {
        return targetNodeIds;
    }

    /// Registers a future for a target node's execution.
    ///
    /// @param targetNodeId identifier of the target node, not null
    /// @param future the future representing the pending execution, not null
    public void addFuture(String targetNodeId, Future<ForkResult> future) {
        futures.put(targetNodeId, future);
    }

    /// Retrieves the future for a specific target node.
    ///
    /// @param targetNodeId identifier of the target node, not null
    /// @return the future, or null if not registered
    public Future<ForkResult> getFuture(String targetNodeId) {
        return futures.get(targetNodeId);
    }

    /// Returns all registered futures.
    ///
    /// @return immutable copy of the futures map, never null
    public Map<String, Future<ForkResult>> getAllFutures() {
        return Map.copyOf(futures);
    }

    /// Records a completed result for a target node.
    ///
    /// @param targetNodeId identifier of the target node, not null
    /// @param result the completed result, not null
    public void addCompletedResult(String targetNodeId, ForkResult result) {
        completedResults.put(targetNodeId, result);
    }

    /// Retrieves the completed result for a specific target node.
    ///
    /// @param targetNodeId identifier of the target node, not null
    /// @return the completed result, or null if not yet completed
    public ForkResult getCompletedResult(String targetNodeId) {
        return completedResults.get(targetNodeId);
    }

    /// Returns all completed results.
    ///
    /// @return immutable copy of the completed results map, never null
    public Map<String, ForkResult> getAllCompletedResults() {
        return Map.copyOf(completedResults);
    }

    /// Checks if all forked executions have completed.
    ///
    /// @return true if all target nodes have completed results
    public boolean isAllCompleted() {
        return completedResults.size() == targetNodeIds.size();
    }

    /// Returns the elapsed time since this context was created.
    ///
    /// @return elapsed time in milliseconds
    public long getElapsedTimeMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    /// Result of a single forked execution path.
    ///
    /// @param targetNodeId identifier of the executed target node, not null
    /// @param nodeResult the execution result, may be null on failure
    /// @param executionTimeMs time taken for execution in milliseconds
    /// @param error the exception if execution failed, null on success
    public record ForkResult(
            String targetNodeId, NodeResult nodeResult, long executionTimeMs, Throwable error) {

        /// Checks if this fork execution was successful.
        ///
        /// @return true if no error occurred and the node result indicates success
        public boolean isSuccess() {
            return error == null && nodeResult != null && nodeResult.isSuccess();
        }

        /// Returns the output from the node execution.
        ///
        /// @return the output object, or null if execution failed
        public Object getOutput() {
            return nodeResult != null ? nodeResult.getOutput() : null;
        }

        /// Creates a successful fork result.
        ///
        /// @param targetNodeId the target node ID, not null
        /// @param result the successful node result, not null
        /// @param timeMs execution time in milliseconds
        /// @return a new success result, never null
        public static ForkResult success(String targetNodeId, NodeResult result, long timeMs) {
            return new ForkResult(targetNodeId, result, timeMs, null);
        }

        /// Creates a failed fork result.
        ///
        /// @param targetNodeId the target node ID, not null
        /// @param error the exception that caused failure, not null
        /// @param timeMs execution time in milliseconds
        /// @return a new failure result, never null
        public static ForkResult failure(String targetNodeId, Throwable error, long timeMs) {
            return new ForkResult(targetNodeId, null, timeMs, error);
        }
    }
}
