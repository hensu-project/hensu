package io.hensu.core.execution.executor;

import io.hensu.core.execution.parallel.FailureMarker;
import io.hensu.core.execution.parallel.ForkJoinContext;
import io.hensu.core.execution.parallel.ForkJoinContext.ForkResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.node.JoinNode;
import io.hensu.core.workflow.node.MergeStrategy;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/// Executes join nodes by awaiting forked execution paths and merging results.
///
/// ### This executor
///
/// - Retrieves `ForkJoinContext` from parent state for each await target
/// - Awaits all forked futures to complete (with optional per-fork timeout)
/// - Merges results according to the configured `MergeStrategy`
/// - Writes the single merged output to `state.getContext()` under `JoinNode.outputField`
///
/// ### Merge Strategies
/// - `COLLECT_ALL` — `Map<targetId, output>` of all branches; failed forks appear
/// as {@link FailureMarker}
/// - `FIRST_SUCCESSFUL` — output of the first successful branch in definition order (not a race)
/// - `CONCATENATE` — all successful outputs joined as a single string separated by `---`
/// - `MERGE_MAPS` — all `Map` outputs merged into one `LinkedHashMap`; key collisions are logged
/// - `CUSTOM` — falls back to `COLLECT_ALL`; caller handles further transformation
///
/// ### Thread Safety
/// Runs entirely on the coordinator thread after fork branches have completed.
/// The single `state.getContext().put(outputField, merged)` write is safe.
///
/// @implNote Branch isolation is enforced by {@link ForkNodeExecutor} —
/// individual branch outputs are never written to the parent context.
/// Only the merged result under `outputField` enters parent state.
///
/// @see ForkNodeExecutor for branch isolation semantics
/// @see MergeStrategy for strategy definitions
public class JoinNodeExecutor implements NodeExecutor<JoinNode> {

    private static final Logger logger = Logger.getLogger(JoinNodeExecutor.class.getName());

    @Override
    public Class<JoinNode> getNodeType() {
        return JoinNode.class;
    }

    @Override
    public NodeResult execute(JoinNode node, ExecutionContext context) throws Exception {
        HensuState state = context.getState();

        logger.info("Join node " + node.getId() + " awaiting: " + node.getAwaitTargets());

        List<ForkResult> allResults = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Collect results from all await targets
        for (String forkNodeId : node.getAwaitTargets()) {
            String forkContextKey = ForkNodeExecutor.getForkContextKey(forkNodeId);
            Object forkContextObj = state.getContext().get(forkContextKey);

            if (forkContextObj == null) {
                errors.add("Fork context not found for: " + forkNodeId);
                continue;
            }

            if (!(forkContextObj instanceof ForkJoinContext forkContext)) {
                errors.add("Invalid fork context type for: " + forkNodeId);
                continue;
            }

            // Wait for all futures in this fork context
            List<ForkResult> forkResults = awaitForkResults(forkContext, node.getTimeoutMs());
            allResults.addAll(forkResults);

            // Check for failures
            if (node.isFailOnAnyError()) {
                for (ForkResult result : forkResults) {
                    if (!result.isSuccess()) {
                        String errorMsg =
                                result.error() != null
                                        ? result.error().getMessage()
                                        : "Execution failed";
                        errors.add(result.targetNodeId() + ": " + errorMsg);
                    }
                }
            }
        }

        // If errors and failOnAnyError, return failure
        if (!errors.isEmpty() && node.isFailOnAnyError()) {
            logger.warning("Join node " + node.getId() + " failed: " + errors);
            return NodeResult.builder()
                    .status(ResultStatus.FAILURE)
                    .output("Fork execution failed: " + String.join("; ", errors))
                    .metadata(
                            Map.of(
                                    "join_node_id", node.getId(),
                                    "errors", errors,
                                    "completed_count", allResults.size()))
                    .build();
        }

        // Merge results according to strategy
        Object mergedOutput = mergeResults(allResults, node.getMergeStrategy());

        // Store in context
        state.getContext().put(node.getOutputField(), mergedOutput);

        logger.info(
                "Join node "
                        + node.getId()
                        + " completed. Merged "
                        + allResults.size()
                        + " results into: "
                        + node.getOutputField());

        // Build success metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("join_node_id", node.getId());
        metadata.put("await_targets", node.getAwaitTargets());
        metadata.put("result_count", allResults.size());
        metadata.put("merge_strategy", node.getMergeStrategy().name());
        metadata.put("output_field", node.getOutputField());

        // Add individual results to metadata
        Map<String, Object> resultDetails =
                allResults.stream()
                        .collect(
                                Collectors.toMap(
                                        ForkResult::targetNodeId,
                                        r -> {
                                            Map<String, Object> detail = new LinkedHashMap<>();
                                            detail.put("success", r.isSuccess());
                                            detail.put("execution_time_ms", r.executionTimeMs());
                                            detail.put(
                                                    "output",
                                                    r.isSuccess() && r.getOutput() != null
                                                            ? r.getOutput()
                                                            : new FailureMarker(
                                                                    r.error() != null
                                                                            ? r.error().getMessage()
                                                                            : "null output"));
                                            return detail;
                                        }));
        metadata.put("fork_results", resultDetails);

        return NodeResult.builder()
                .status(ResultStatus.SUCCESS)
                .output(mergedOutput)
                .metadata(metadata)
                .build();
    }

    /// Await all futures in a fork context, with optional timeout.
    ///
    /// @implNote Iterates {@link ForkJoinContext#getTargetNodeIds()} — the authoritative
    /// definition-order list — rather than the futures map's entry set, which has no
    /// guaranteed iteration order. This ensures {@code FIRST_SUCCESSFUL} and other
    /// order-sensitive strategies always see results in fork-definition order.
    private List<ForkResult> awaitForkResults(ForkJoinContext forkContext, long timeoutMs) {
        List<ForkResult> results = new ArrayList<>();
        Map<String, Future<ForkResult>> futures = forkContext.getAllFutures();

        for (String targetId : forkContext.getTargetNodeIds()) {
            Future<ForkResult> future = futures.get(targetId);
            if (future == null) {
                logger.warning("No future registered for fork target: " + targetId);
                results.add(
                        ForkResult.failure(
                                targetId,
                                new IllegalStateException("No future registered for: " + targetId),
                                0));
                continue;
            }

            try {
                ForkResult result;
                if (timeoutMs > 0) {
                    result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                } else {
                    result = future.get();
                }
                results.add(result);

            } catch (TimeoutException e) {
                logger.warning("Fork target timed out: " + targetId);
                results.add(ForkResult.failure(targetId, e, timeoutMs));

            } catch (Exception e) {
                logger.warning("Fork target exception: " + targetId + " - " + e.getMessage());
                results.add(ForkResult.failure(targetId, e, forkContext.getElapsedTimeMs()));
            }
        }

        return results;
    }

    /// Merge fork results according to the specified strategy.
    private Object mergeResults(List<ForkResult> results, MergeStrategy strategy) {
        return switch (strategy) {
            case COLLECT_ALL -> collectAll(results);
            case FIRST_SUCCESSFUL -> firstSuccessful(results);
            case CONCATENATE -> concatenate(results);
            case MERGE_MAPS -> mergeMaps(results);
            case CUSTOM -> collectAll(results); // Custom requires external handler
        };
    }

    /// COLLECT_ALL: Return map of targetId → output for all branches.
    ///
    /// Failed forks and forks with null output are represented as {@link FailureMarker}
    /// entries rather than being silently dropped. Downstream nodes can pattern-match
    /// on {@code FailureMarker} to distinguish real outputs from failures.
    private Map<String, Object> collectAll(List<ForkResult> results) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (ForkResult r : results) {
            if (r.isSuccess() && r.getOutput() != null) {
                output.put(r.targetNodeId(), r.getOutput());
            } else {
                String msg =
                        r.error() != null ? r.error().getMessage() : "Fork returned null output";
                output.put(r.targetNodeId(), new FailureMarker(msg));
            }
        }
        return output;
    }

    /// FIRST_SUCCESSFUL: Return the output of the first successful branch in definition order.
    ///
    /// @implNote All futures are awaited before this method runs — iteration order is fork
    /// definition order, not chronological completion order.
    private Object firstSuccessful(List<ForkResult> results) {
        for (ForkResult r : results) {
            if (r.isSuccess() && r.getOutput() != null) {
                return r.getOutput();
            }
        }
        logger.warning("FIRST_SUCCESSFUL: no successful branch found — returning null");
        return null;
    }

    /// CONCATENATE: Join all successful outputs as a single string separated by `---`.
    ///
    /// Failed forks and null-output forks are skipped — this strategy produces LLM-facing
    /// text where partial results are still useful.
    private String concatenate(List<ForkResult> results) {
        return results.stream()
                .filter(r -> r.isSuccess() && r.getOutput() != null)
                .map(r -> r.getOutput().toString())
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /// MERGE_MAPS: Merge all successful Map outputs into one `LinkedHashMap`.
    ///
    /// Last-write-wins on key collision. Collisions are logged as warnings so workflow
    /// authors can detect unintended key shadowing.
    private Map<String, Object> mergeMaps(List<ForkResult> results) {
        Map<String, Object> merged = new LinkedHashMap<>();

        for (ForkResult r : results) {
            if (r.isSuccess() && r.getOutput() instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if (merged.containsKey(key)) {
                        logger.warning(
                                "MERGE_MAPS key collision on '"
                                        + key
                                        + "' from fork '"
                                        + r.targetNodeId()
                                        + "' — overwriting with last-write-wins");
                    }
                    merged.put(key, entry.getValue());
                }
            }
        }

        return merged;
    }
}
