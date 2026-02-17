package io.hensu.core.execution.executor;

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
/// - Retrieves ForkJoinContext from state for each await target
/// - Waits for all forked executions to complete (with optional timeout)
/// - Merges results according to configured MergeStrategy
/// - Stores merged output in context under specified outputField
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
                                        r ->
                                                Map.of(
                                                        "success", r.isSuccess(),
                                                        "execution_time_ms", r.executionTimeMs(),
                                                        "output",
                                                                r.getOutput() != null
                                                                        ? r.getOutput()
                                                                        : "null")));
        metadata.put("fork_results", resultDetails);

        return NodeResult.builder()
                .status(ResultStatus.SUCCESS)
                .output(mergedOutput)
                .metadata(metadata)
                .build();
    }

    /// Await all futures in a fork context, with optional timeout.
    private List<ForkResult> awaitForkResults(ForkJoinContext forkContext, long timeoutMs) {
        List<ForkResult> results = new ArrayList<>();

        for (Map.Entry<String, Future<ForkResult>> entry : forkContext.getAllFutures().entrySet()) {
            String targetId = entry.getKey();
            Future<ForkResult> future = entry.getValue();

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
            case FIRST_COMPLETED -> firstCompleted(results);
            case CONCATENATE -> concatenate(results);
            case MERGE_MAPS -> mergeMaps(results);
            case CUSTOM -> collectAll(results); // Custom requires external handler
        };
    }

    /// COLLECT_ALL: Return map of targetId -> output.
    private Map<String, Object> collectAll(List<ForkResult> results) {
        return results.stream()
                .filter(ForkResult::isSuccess)
                .collect(
                        Collectors.toMap(
                                ForkResult::targetNodeId,
                                r -> r.getOutput() != null ? r.getOutput() : "",
                                (_, b) -> b,
                                LinkedHashMap::new));
    }

    /// FIRST_COMPLETED: Return first successful result.
    private Object firstCompleted(List<ForkResult> results) {
        return results.stream()
                .filter(ForkResult::isSuccess)
                .findFirst()
                .map(ForkResult::getOutput)
                .orElse(null);
    }

    /// CONCATENATE: Join all outputs as strings.
    private String concatenate(List<ForkResult> results) {
        return results.stream()
                .filter(ForkResult::isSuccess)
                .map(r -> r.getOutput() != null ? r.getOutput().toString() : "")
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /// MERGE_MAPS: Merge all map outputs into one.
    private Map<String, Object> mergeMaps(List<ForkResult> results) {
        Map<String, Object> merged = new LinkedHashMap<>();

        for (ForkResult result : results) {
            if (result.isSuccess() && result.getOutput() instanceof Map) {
                merged.putAll((Map<String, Object>) result.getOutput());
            }
        }

        return merged;
    }
}
