package io.hensu.core.execution.executor;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.parallel.Branch;
import io.hensu.core.execution.parallel.BranchResult;
import io.hensu.core.execution.parallel.ConsensusConfig;
import io.hensu.core.execution.parallel.ConsensusEvaluator;
import io.hensu.core.execution.parallel.ConsensusResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.rubric.RubricParser;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.rubric.model.Rubric;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.ParallelNode;
import java.nio.file.Path;
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
/// - Evaluates branch outputs against rubrics when `Branch.rubricId` is set
/// - Aggregates results from all branches
/// - Evaluates consensus if configured (majority, unanimous, weighted, judge)
///
/// ### Rubric Integration
/// When a branch declares a `rubricId`, the executor evaluates the branch output
/// against the rubric after execution completes. The rubric score and pass/fail
/// status are stored in the branch result metadata (`rubric_score`, `rubric_passed`),
/// where they take priority over text-parsing heuristics during consensus voting.
///
/// @implNote The ExecutorService is obtained from ExecutionContext and is NOT shut down
/// by this executor — lifecycle is managed by the owner. Rubric evaluation runs
/// sequentially after all branch futures resolve.
///
/// @see ConsensusEvaluator for vote extraction and strategy evaluation
/// @see RubricEngine for rubric-based quality evaluation
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
                                                            branch.getId(), toNodeResult(response));
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

        // Inject branch-level weights into result metadata for ConsensusEvaluator
        branchResults = enrichWithBranchWeights(branchResults, node);

        // Evaluate branch rubrics before consensus (rubric scores override text heuristics)
        branchResults = enrichWithRubricScores(branchResults, node, context);

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

    /// Injects branch-level weights from the node definition into result metadata.
    ///
    /// The {@link ConsensusEvaluator} reads `"weight"` from result metadata during
    /// vote extraction. This method propagates the weight declared in each
    /// {@link Branch} definition so that DSL-configured weights reach the evaluator.
    ///
    /// @param branchResults collected branch execution results, not null
    /// @param node the parallel node containing branch definitions, not null
    /// @return branch results with weight metadata injected, never null
    private List<BranchResult> enrichWithBranchWeights(
            List<BranchResult> branchResults, ParallelNode node) {

        Map<String, Branch> branchMap = new HashMap<>();
        for (Branch branch : node.getBranches()) {
            branchMap.put(branch.getId(), branch);
        }

        List<BranchResult> enriched = new ArrayList<>(branchResults.size());
        for (BranchResult br : branchResults) {
            Branch branch = branchMap.get(br.getBranchId());
            if (branch != null && branch.getWeight() != 1.0) {
                Map<String, Object> metadata = new HashMap<>(br.getResult().getMetadata());
                metadata.put("weight", branch.getWeight());
                NodeResult enrichedResult =
                        new NodeResult(
                                br.getResult().getStatus(), br.getResult().getOutput(), metadata);
                enriched.add(new BranchResult(br.getBranchId(), enrichedResult));
            } else {
                enriched.add(br);
            }
        }
        return enriched;
    }

    /// Evaluates branch outputs against rubrics and enriches result metadata.
    ///
    /// For each branch that declares a `rubricId` and completed successfully,
    /// evaluates the output against the rubric and stores `rubric_score`,
    /// `rubric_passed`, and `rubric_id` in the result metadata. These metadata
    /// fields take priority over text-parsing heuristics in
    /// {@link ConsensusEvaluator#extractVotes}.
    ///
    /// Branches without a rubricId or with non-SUCCESS status are returned unchanged.
    /// Rubric evaluation failures are logged and the original result is preserved.
    ///
    /// @param branchResults collected branch execution results, not null
    /// @param node the parallel node containing branch definitions, not null
    /// @param context execution context with rubric engine and workflow, not null
    /// @return enriched branch results with rubric metadata where applicable, never null
    private List<BranchResult> enrichWithRubricScores(
            List<BranchResult> branchResults, ParallelNode node, ExecutionContext context) {

        RubricEngine rubricEngine = context.getRubricEngine();
        if (rubricEngine == null) {
            return branchResults;
        }

        Map<String, Branch> branchMap = new HashMap<>();
        for (Branch branch : node.getBranches()) {
            branchMap.put(branch.getId(), branch);
        }

        Workflow workflow = context.getWorkflow();
        List<BranchResult> enriched = new ArrayList<>(branchResults.size());

        for (BranchResult br : branchResults) {
            Branch branch = branchMap.get(br.getBranchId());

            if (branch != null
                    && branch.rubricId() != null
                    && br.getResult().getStatus() == ResultStatus.SUCCESS) {

                String rubricId = branch.rubricId();
                try {
                    registerRubricIfAbsent(rubricEngine, rubricId, workflow);

                    RubricEvaluation evaluation =
                            rubricEngine.evaluate(
                                    rubricId, br.getResult(), context.getState().getContext());

                    Map<String, Object> enrichedMetadata =
                            new HashMap<>(br.getResult().getMetadata());
                    enrichedMetadata.put("rubric_score", evaluation.getScore());
                    enrichedMetadata.put("rubric_passed", evaluation.isPassed());
                    enrichedMetadata.put("rubric_id", rubricId);

                    NodeResult enrichedResult =
                            new NodeResult(
                                    br.getResult().getStatus(),
                                    br.getResult().getOutput(),
                                    enrichedMetadata);

                    enriched.add(new BranchResult(br.getBranchId(), enrichedResult));

                    logger.info(
                            "Branch "
                                    + br.getBranchId()
                                    + " rubric '"
                                    + rubricId
                                    + "': score="
                                    + evaluation.getScore()
                                    + ", passed="
                                    + evaluation.isPassed());
                } catch (Exception e) {
                    logger.warning(
                            "Rubric evaluation failed for branch "
                                    + br.getBranchId()
                                    + ": "
                                    + e.getMessage());
                    enriched.add(br);
                }
            } else {
                enriched.add(br);
            }
        }

        return enriched;
    }

    /// Registers a rubric in the engine if not already present.
    ///
    /// Reuses the lazy-registration pattern from {@link io.hensu.core.execution.WorkflowExecutor}
    /// to avoid redundant file parsing on retries.
    ///
    /// @param rubricEngine rubric engine to register with, not null
    /// @param rubricId rubric identifier, not null
    /// @param workflow workflow containing rubric path mappings, not null
    private void registerRubricIfAbsent(
            RubricEngine rubricEngine, String rubricId, Workflow workflow) {
        if (!rubricEngine.exists(rubricId)) {
            String rubricPath = workflow.getRubrics().get(rubricId);
            if (rubricPath != null) {
                Rubric rubric = RubricParser.parse(Path.of(rubricPath));
                rubricEngine.registerRubric(rubric);
            }
        }
    }

    private NodeResult toNodeResult(AgentResponse response) {
        return switch (response) {
            case AgentResponse.TextResponse t ->
                    new NodeResult(ResultStatus.SUCCESS, t.content(), t.metadata());
            case AgentResponse.ToolRequest t ->
                    new NodeResult(
                            ResultStatus.SUCCESS,
                            "Tool: " + t.toolName(),
                            Map.of("toolName", t.toolName(), "arguments", t.arguments()));
            case AgentResponse.PlanProposal p ->
                    new NodeResult(
                            ResultStatus.SUCCESS,
                            "Plan with " + p.steps().size() + " steps",
                            Map.of("steps", p.steps()));
            case AgentResponse.Error e ->
                    new NodeResult(
                            ResultStatus.FAILURE,
                            e.message(),
                            Map.of("errorType", e.errorType().name()));
        };
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
