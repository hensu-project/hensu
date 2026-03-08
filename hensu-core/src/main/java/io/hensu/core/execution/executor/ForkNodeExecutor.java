package io.hensu.core.execution.executor;

import io.hensu.core.execution.parallel.ForkJoinContext;
import io.hensu.core.execution.parallel.ForkJoinContext.ForkResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.ForkNode;
import io.hensu.core.workflow.node.Node;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/// Executes fork nodes by spawning parallel execution paths using virtual threads.
///
/// ### This executor
///
/// - Creates a `ForkJoinContext` to track forked executions
/// - Spawns a virtual thread for each target node
/// - Each branch receives an **isolated copy** of the parent context map — branch writes
///   do not leak back to the parent or to sibling branches
/// - Branch results are stored in `ForkJoinContext` only; the parent context is not mutated
/// - Stores `ForkJoinContext` in parent state for `JoinNode` to await and merge
///
/// The fork node does NOT wait for targets to complete — it transitions immediately after
/// spawning. Use `JoinNode` to wait for completion and merge results.
///
/// @implNote Each branch gets an isolated `ExecutionContext` via {@link HensuState#branch}.
/// All services (`agentRegistry`, `rubricEngine`, etc.) are shared by reference — they
/// must be thread-safe.
///
/// @see JoinNodeExecutor for result merging and merge strategy semantics
/// @see HensuState#branch for branch isolation mechanism
public class ForkNodeExecutor implements NodeExecutor<ForkNode> {

    private static final Logger logger = Logger.getLogger(ForkNodeExecutor.class.getName());
    private static final String FORK_CONTEXT_KEY = "_fork_context_";

    @Override
    public Class<ForkNode> getNodeType() {
        return ForkNode.class;
    }

    @Override
    public NodeResult execute(ForkNode node, ExecutionContext context) throws Exception {
        HensuState state = context.getState();
        Workflow workflow = context.getWorkflow();
        ExecutorService executorService = context.getExecutorService();
        NodeExecutorRegistry registry = context.getNodeExecutorRegistry();

        logger.info(
                "Forking execution at node: "
                        + node.getId()
                        + " with "
                        + node.getTargets().size()
                        + " targets: "
                        + node.getTargets());

        // Create fork context to track this fork's executions
        ForkJoinContext forkContext = new ForkJoinContext(node.getId(), node.getTargets());

        // Spawn virtual thread for each target
        for (String targetNodeId : node.getTargets()) {
            Node targetNode = workflow.getNodes().get(targetNodeId);
            if (targetNode == null) {
                logger.warning("Fork target node not found: " + targetNodeId);
                continue;
            }

            // Isolated branch context — branch writes stay in the branch, not the parent
            ExecutionContext branchContext = context.withState(state.branch(targetNodeId));

            Future<ForkResult> future =
                    executorService.submit(
                            () -> {
                                long startTime = System.currentTimeMillis();
                                try {
                                    logger.info("Fork executing target: " + targetNodeId);

                                    NodeExecutor<Node> executor =
                                            registry.getExecutorFor(targetNode);

                                    NodeResult result = executor.execute(targetNode, branchContext);

                                    long elapsed = System.currentTimeMillis() - startTime;
                                    logger.info(
                                            "Fork target completed: "
                                                    + targetNodeId
                                                    + " in "
                                                    + elapsed
                                                    + "ms");

                                    ForkResult forkResult =
                                            ForkResult.success(targetNodeId, result, elapsed);
                                    forkContext.addCompletedResult(targetNodeId, forkResult);
                                    return forkResult;

                                } catch (Exception e) {
                                    long elapsed = System.currentTimeMillis() - startTime;
                                    logger.warning(
                                            "Fork target failed: "
                                                    + targetNodeId
                                                    + " - "
                                                    + e.getMessage());

                                    ForkResult forkResult =
                                            ForkResult.failure(targetNodeId, e, elapsed);
                                    forkContext.addCompletedResult(targetNodeId, forkResult);
                                    return forkResult;
                                }
                            });

            forkContext.addFuture(targetNodeId, future);
        }

        // Store fork context in state for JoinNode to access
        String forkContextKey = FORK_CONTEXT_KEY + node.getId();
        state.getContext().put(forkContextKey, forkContext);

        // If waitForAll is true, block until all complete
        if (node.isWaitForAll()) {
            logger.info("Fork node " + node.getId() + " waiting for all targets to complete...");
            for (Future<ForkResult> future : forkContext.getAllFutures().values()) {
                future.get(); // Block until complete
            }
            logger.info("Fork node " + node.getId() + " all targets completed");
        }

        // Build result metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fork_node_id", node.getId());
        metadata.put("target_count", node.getTargets().size());
        metadata.put("targets", node.getTargets());
        metadata.put("wait_for_all", node.isWaitForAll());

        return NodeResult.builder()
                .status(ResultStatus.SUCCESS)
                .output("Forked " + node.getTargets().size() + " execution paths")
                .metadata(metadata)
                .build();
    }

    /// Get the context key for a fork node's ForkJoinContext.
    public static String getForkContextKey(String forkNodeId) {
        return FORK_CONTEXT_KEY + forkNodeId;
    }
}
