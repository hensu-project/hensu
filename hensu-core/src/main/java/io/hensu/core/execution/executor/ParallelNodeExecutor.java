package io.hensu.core.execution.executor;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.parallel.BranchResult;
import io.hensu.core.execution.parallel.ConsensusConfig;
import io.hensu.core.execution.parallel.ConsensusEvaluator;
import io.hensu.core.execution.parallel.ConsensusResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.workflow.node.ParallelNode;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/// Executes parallel nodes by running multiple branches concurrently.
///
/// ### This executor
///
/// - Executes branches in parallel using ExecutorService
/// - Aggregates results from all branches
/// - Evaluates consensus if configured (majority, unanimous, weighted, judge)
///
///
/// Note: The ExecutorService is obtained from ExecutionContext and is NOT shut down by this
/// executor - lifecycle is managed by the owner.
public class ParallelNodeExecutor implements NodeExecutor<ParallelNode> {

    private static final Logger logger = Logger.getLogger(ParallelNodeExecutor.class.getName());
    private final ConsensusEvaluator consensusEvaluator = new ConsensusEvaluator();

    @Override
    public Class<ParallelNode> getNodeType() {
        return ParallelNode.class;
    }

    @Override
    public NodeResult execute(ParallelNode node, ExecutionContext context) throws Exception {
        HensuState state = context.getState();
        AgentRegistry agentRegistry = context.getAgentRegistry();
        ExecutorService executorService = context.getExecutorService();
        TemplateResolver templateResolver = context.getTemplateResolver();

        logger.info(
                "Executing parallel node: "
                        + node.getId()
                        + " with "
                        + (node.getBranches().length - 1)
                        + " branches");

        // TODO: Make configurable via ParallelNode or ConsensusConfig
        // Default timeout: 5 minutes per branch
        long timeoutSeconds = 300L;

        List<Future<BranchResult>> futures =
                Arrays.stream(node.getBranches())
                        .map(
                                branch ->
                                        executorService.submit(
                                                () -> {
                                                    String agentId = branch.getAgentId();
                                                    Agent agent =
                                                            agentRegistry
                                                                    .getAgent(agentId)
                                                                    .orElseThrow(
                                                                            () ->
                                                                                    new IllegalStateException(
                                                                                            "Agent not found: "
                                                                                                    + agentId));
                                                    String resolvedPrompt =
                                                            branch.getPrompt() != null
                                                                    ? templateResolver.resolve(
                                                                            branch.getPrompt(),
                                                                            state.getContext())
                                                                    : "";

                                                    logger.info(
                                                            "Executing branch: " + branch.getId());

                                                    AgentResponse response =
                                                            agent.execute(
                                                                    resolvedPrompt,
                                                                    state.getContext());

                                                    return new BranchResult(
                                                            branch.getId(),
                                                            new NodeResult(
                                                                    response.isSuccess()
                                                                            ? ResultStatus.SUCCESS
                                                                            : ResultStatus.FAILURE,
                                                                    response.getOutput(),
                                                                    response.getMetadata()));
                                                }))
                        .toList();

        // Collect results with timeout and partial failure handling
        List<BranchResult> branchResults = new ArrayList<>();
        List<String> failedBranches = new ArrayList<>();

        for (int i = 0; i < futures.size(); i++) {
            Future<BranchResult> future = futures.get(i);
            String branchId = node.getBranches()[i].getId();

            try {
                BranchResult result =
                        future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
                branchResults.add(result);
            } catch (java.util.concurrent.TimeoutException e) {
                logger.warning("Branch timed out after " + timeoutSeconds + "s: " + branchId);
                failedBranches.add(branchId + " (timeout)");
                future.cancel(true);
                // Add failure result for timed-out branch
                branchResults.add(
                        new BranchResult(
                                branchId,
                                new NodeResult(
                                        ResultStatus.FAILURE,
                                        "Branch timed out",
                                        Map.of("error", "timeout"))));
            } catch (java.util.concurrent.ExecutionException e) {
                logger.warning(
                        "Branch failed with exception: "
                                + branchId
                                + " - "
                                + e.getCause().getMessage());
                failedBranches.add(branchId + " (" + e.getCause().getClass().getSimpleName() + ")");
                // Add failure result for failed branch
                branchResults.add(
                        new BranchResult(
                                branchId,
                                new NodeResult(
                                        ResultStatus.FAILURE,
                                        "Branch execution failed: " + e.getCause().getMessage(),
                                        Map.of(
                                                "error",
                                                e.getCause().getClass().getName(),
                                                "message",
                                                e.getCause().getMessage()))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Parallel execution interrupted", e);
            }
        }

        if (!failedBranches.isEmpty()) {
            logger.warning("Partial failures in parallel execution: " + failedBranches);
            state.getContext().put("failed_branches", failedBranches);
        }

        // Consensus evaluation if configured
        NodeResult finalResult;
        if (node.getConsensusConfig() != null) {
            finalResult =
                    evaluateConsensus(
                            node.getConsensusConfig(), branchResults, state, agentRegistry);
        } else {
            // No consensus, aggregate results
            boolean allSuccess =
                    branchResults.stream()
                            .allMatch(br -> br.getResult().getStatus() == ResultStatus.SUCCESS);

            Map<String, Object> outputs =
                    branchResults.stream()
                            .collect(
                                    Collectors.toMap(
                                            BranchResult::getBranchId,
                                            br -> br.getResult().getOutput()));

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("branch_count", branchResults.size());

            finalResult =
                    new NodeResult(
                            allSuccess ? ResultStatus.SUCCESS : ResultStatus.FAILURE,
                            outputs,
                            metadata);
        }

        logger.info("Parallel execution completed: " + node.getId());
        return finalResult;
    }

    private NodeResult evaluateConsensus(
            ConsensusConfig config,
            List<BranchResult> branchResults,
            HensuState state,
            AgentRegistry agentRegistry)
            throws Exception {

        // Delegate to ConsensusEvaluator for strategy-specific logic
        ConsensusResult consensusResult =
                consensusEvaluator.evaluate(config, branchResults, state, agentRegistry);

        // Store results in context for downstream processing
        state.getContext().put("consensus_reached", consensusResult.consensusReached());
        state.getContext().put("consensus_result", consensusResult);
        state.getContext().put("consensus_votes", consensusResult.votes());
        if (consensusResult.winningBranchId() != null) {
            state.getContext().put("consensus_winning_branch", consensusResult.winningBranchId());
        }

        // Build metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("consensus_reached", consensusResult.consensusReached());
        metadata.put("strategy", config.getStrategy().name());
        metadata.put("winning_branch", consensusResult.winningBranchId());
        metadata.put("approve_count", consensusResult.approveCount());
        metadata.put("reject_count", consensusResult.rejectCount());
        metadata.put("average_score", consensusResult.averageScore());
        metadata.put("reasoning", consensusResult.reasoning());
        metadata.put(
                "branch_results",
                branchResults.stream()
                        .collect(
                                Collectors.toMap(
                                        BranchResult::getBranchId,
                                        br -> br.getResult().getOutput())));

        logger.info(
                "Consensus result: "
                        + (consensusResult.consensusReached() ? "REACHED" : "NOT REACHED")
                        + " (approve: "
                        + consensusResult.approveCount()
                        + ", reject: "
                        + consensusResult.rejectCount()
                        + ")");

        return new NodeResult(
                consensusResult.consensusReached() ? ResultStatus.SUCCESS : ResultStatus.FAILURE,
                consensusResult.finalOutput(),
                metadata);
    }
}
