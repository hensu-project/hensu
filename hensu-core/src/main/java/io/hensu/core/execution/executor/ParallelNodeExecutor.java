package io.hensu.core.execution.executor;

import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.SynchronizedListenerDecorator;
import io.hensu.core.execution.parallel.*;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.util.AgentOutputValidator;
import io.hensu.core.util.JsonUtil;
import io.hensu.core.workflow.node.ParallelNode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/// Executes parallel nodes by running multiple branches concurrently.
///
/// ### Branch isolation
/// Each branch runs on its own virtual thread with an isolated context snapshot
/// bound via {@link ScopedValue}. Branch mutations never leak into sibling
/// branches or the parent state.
///
/// ### Agent lifecycle delegation
/// Branch agent execution is delegated to {@link AgentLifecycleRunner}, sharing
/// the same template resolution, prompt enrichment, listener notification, and
/// response conversion path as {@link StandardNodeExecutor}. Branch-specific
/// concerns (output validation, yield extraction) remain here.
///
/// ### Thread-safe listener
/// The parent {@link io.hensu.core.execution.ExecutionListener} is wrapped in a
/// {@link SynchronizedListenerDecorator} before branch submission so that
/// multi-line output (e.g. box-drawing in verbose CLI) does not interleave
/// across concurrent branches.
///
/// ### Yields merge
/// After consensus evaluation, only winning branches' yields are promoted to
/// {@code state.getContext()}. Loser yields are preserved in consensus metadata
/// for observability but never touch the live context.
///
/// ### Branch execution metadata
/// Branch-specific config (consensus flag, yield declarations) is carried on
/// {@link ExecutionContext#getBranchConfig()} – NOT in the state context map.
/// This keeps engine-internal flags out of the user data bus and invisible to
/// the LLM agent.
///
/// @implNote Uses Java 25 preview API ({@code StructuredTaskScope}).
/// Compile with {@code --enable-preview}.
///
/// @see AgentLifecycleRunner for the shared agent call lifecycle
/// @see ConsensusEvaluator for vote extraction and strategy evaluation
/// @see BranchExecutionConfig for branch execution metadata
public class ParallelNodeExecutor implements NodeExecutor<ParallelNode> {

    private static final Logger logger = Logger.getLogger(ParallelNodeExecutor.class.getName());

    /// Default branch execution timeout in seconds.
    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;

    /// Branch-scoped context bound per virtual thread. Enrichers and extractors
    /// can read {@code BRANCH_CONTEXT.get()} without explicit parameter drilling.
    public static final ScopedValue<Map<String, Object>> BRANCH_CONTEXT = ScopedValue.newInstance();

    private final ConsensusEvaluator consensusEvaluator = new ConsensusEvaluator();

    @Override
    public Class<ParallelNode> getNodeType() {
        return ParallelNode.class;
    }

    @Override
    public NodeResult execute(ParallelNode node, ExecutionContext context) throws Exception {
        HensuState state = context.getState();

        logger.info(
                "Executing parallel node: "
                        + node.getId()
                        + " with "
                        + node.getBranches().length
                        + " branches");

        // Wrap listener for thread-safe output during parallel branch execution
        ExecutionContext safeContext =
                context.withListener(new SynchronizedListenerDecorator(context.getListener()));

        // Fork branches using StructuredTaskScope
        List<BranchResult> branchResults;
        var threadFactory = Thread.ofVirtual().name("parallel-" + node.getId() + "-", 0).factory();
        try (var scope =
                StructuredTaskScope.open(
                        StructuredTaskScope.Joiner.awaitAll(),
                        cf ->
                                cf.withThreadFactory(threadFactory)
                                        .withTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                                        .withName("parallel-" + node.getId()))) {

            List<Subtask<BranchResult>> subtasks =
                    Arrays.stream(node.getBranches())
                            .map(
                                    branch ->
                                            scope.fork(
                                                    () -> executeBranch(branch, node, safeContext)))
                            .toList();

            scope.join();

            branchResults = subtasks.stream().map(Subtask::get).toList();
        }

        if (branchResults.stream()
                .anyMatch(br -> br.getResult().getStatus() == ResultStatus.FAILURE)) {
            List<String> failed =
                    branchResults.stream()
                            .filter(br -> br.getResult().getStatus() == ResultStatus.FAILURE)
                            .map(BranchResult::getBranchId)
                            .toList();
            logger.warning("Partial failures in parallel execution: " + failed);
            state.getContext().put("failed_branches", failed);
        }

        NodeResult finalResult;
        if (node.getConsensusConfig() != null) {
            finalResult =
                    evaluateConsensus(
                            node.getConsensusConfig(),
                            branchResults,
                            state,
                            context.getAgentRegistry(),
                            safeContext.getListener());
        } else {
            finalResult = aggregateResults(branchResults);
        }

        logger.info("Parallel execution completed: " + node.getId());
        return finalResult;
    }

    // -- Branch execution --------------------------------------------------------

    /// Executes a single branch with isolated context and shared agent lifecycle.
    ///
    /// The branch context is isolated via {@link ScopedValue} binding. Agent
    /// execution is delegated to {@link AgentLifecycleRunner} (template resolution,
    /// prompt enrichment, listener events, response conversion). Branch-specific
    /// concerns (output validation, yield extraction) remain here.
    private BranchResult executeBranch(Branch branch, ParallelNode node, ExecutionContext context) {

        HensuState parentState = context.getState();
        HashMap<String, Object> branchSnapshot = new HashMap<>(parentState.getContext());

        // Branch execution config on ExecutionContext – NOT in the context map.
        // This keeps engine flags invisible to the LLM agent.
        ConsensusConfig cc = node.getConsensusConfig();
        BranchExecutionConfig branchConfig =
                new BranchExecutionConfig(
                        cc != null, cc != null ? cc.getStrategy() : null, branch.getYields());

        try {
            return ScopedValue.where(BRANCH_CONTEXT, branchSnapshot)
                    .call(
                            () -> {
                                HensuState branchState =
                                        new HensuState(
                                                branchSnapshot,
                                                parentState.getWorkflowId(),
                                                parentState.getCurrentNode(),
                                                new ExecutionHistory());
                                ExecutionContext branchCtx =
                                        context.withState(branchState)
                                                .withBranchConfig(branchConfig);

                                String branchNodeId = node.getId() + "/" + branch.getId();

                                // Resolve template against branch-isolated snapshot
                                TemplateResolver resolver = branchCtx.getTemplateResolver();
                                String resolvedPrompt =
                                        branch.getPrompt() != null
                                                ? resolver.resolve(
                                                        branch.getPrompt(), branchSnapshot)
                                                : "";

                                // Delegate to shared agent execution lifecycle
                                NodeResult nodeResult =
                                        AgentLifecycleRunner.execute(
                                                branchNodeId,
                                                branch.getAgentId(),
                                                resolvedPrompt,
                                                node,
                                                branchCtx);

                                // Validate output before extraction (same safety contract as
                                // OutputExtractionPostProcessor for sequential nodes)
                                if (nodeResult.getOutput() != null) {
                                    String output = nodeResult.getOutput().toString();
                                    Optional<String> violation =
                                            AgentOutputValidator.validate(output);
                                    if (violation.isPresent()) {
                                        logger.warning(
                                                "Branch ["
                                                        + branch.getId()
                                                        + "] output rejected: "
                                                        + violation.get());
                                        return new BranchResult(
                                                branch.getId(),
                                                new NodeResult(
                                                        ResultStatus.FAILURE,
                                                        "Branch output " + violation.get(),
                                                        Map.of(
                                                                "error",
                                                                "output_validation_failed")),
                                                Map.of(),
                                                branch.getWeight());
                                    }
                                }

                                // Extract structured yields from agent output
                                Map<String, Object> yields =
                                        extractBranchYields(
                                                branch, node, nodeResult, branchSnapshot);

                                return new BranchResult(
                                        branch.getId(), nodeResult, yields, branch.getWeight());
                            });
        } catch (Exception e) {
            logger.warning("Branch [" + branch.getId() + "] execution failed: " + e.getMessage());
            return new BranchResult(
                    branch.getId(),
                    new NodeResult(
                            ResultStatus.FAILURE,
                            "Branch execution failed: " + e.getMessage(),
                            Map.of("error", e.getClass().getName())),
                    Map.of(),
                    branch.getWeight());
        }
    }

    /// Extracts structured output from branch agent response into a yields map.
    ///
    /// Uses the same {@link JsonUtil#extractOutputParams} path as
    /// {@link io.hensu.core.execution.pipeline.OutputExtractionPostProcessor}.
    /// Extracts both domain yields declared via {@code yields()} and engine
    /// variables (via {@link EngineVariables#CONSENSUS_KEYS}) for consensus
    /// evaluation.
    private Map<String, Object> extractBranchYields(
            Branch branch,
            ParallelNode node,
            NodeResult result,
            Map<String, Object> branchContext) {

        if (result.getOutput() == null) return Map.of();

        String output = result.getOutput().toString();
        List<String> allKeys = new ArrayList<>(branch.getYields());

        ConsensusConfig branchCC = node.getConsensusConfig();
        if (branchCC != null && branchCC.getStrategy() != ConsensusStrategy.JUDGE_DECIDES) {
            allKeys.addAll(EngineVariables.CONSENSUS_KEYS);
        }

        if (allKeys.isEmpty()) return Map.of();

        // Extract into branch context (same as OutputExtractionPostProcessor)
        JsonUtil.extractOutputParams(allKeys, output, branchContext, logger);

        // Package extracted values into yields map
        Map<String, Object> yields = new HashMap<>();
        for (String key : allKeys) {
            if (branchContext.containsKey(key)) {
                yields.put(key, branchContext.get(key));
            }
        }
        return yields;
    }

    // -- Result aggregation (no consensus) ---------------------------------------

    private NodeResult aggregateResults(List<BranchResult> branchResults) {
        boolean allSuccess = true;
        Map<String, Object> outputs = new LinkedHashMap<>();

        for (BranchResult br : branchResults) {
            NodeResult result = br.getResult();
            if (result.getStatus() == ResultStatus.SUCCESS && result.getOutput() != null) {
                outputs.put(br.getBranchId(), result.getOutput());
            } else {
                allSuccess = false;
                String msg =
                        result.getOutput() != null
                                ? result.getOutput().toString()
                                : "Branch returned null output";
                outputs.put(br.getBranchId(), new FailureMarker(msg));
            }
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("branch_count", branchResults.size());

        return new NodeResult(
                allSuccess ? ResultStatus.SUCCESS : ResultStatus.FAILURE, outputs, metadata);
    }

    // -- Consensus evaluation + yields merge ------------------------------------

    private NodeResult evaluateConsensus(
            ConsensusConfig config,
            List<BranchResult> branchResults,
            HensuState state,
            AgentRegistry agentRegistry,
            ExecutionListener listener)
            throws Exception {

        ConsensusResult consensusResult =
                consensusEvaluator.evaluate(config, branchResults, state, agentRegistry, listener);

        // Merge branch yields into parent context.
        // Vote-based strategies: merge ALL yields – the vote gates the transition,
        // not the data. Every branch's domain output flows to downstream nodes.
        // JUDGE_DECIDES: merge only winner yields – it's a pick-the-best selection.
        boolean judgeStrategy = config.getStrategy() == ConsensusStrategy.JUDGE_DECIDES;
        Set<String> winnerIds =
                judgeStrategy ? new HashSet<>(consensusResult.winningBranchIds()) : Set.of();

        for (BranchResult br : branchResults) {
            if (!judgeStrategy || winnerIds.contains(br.getBranchId())) {
                state.getContext().putAll(br.yields());
            }
        }

        // Store consensus metadata in context for downstream processing
        state.getContext().put("consensus_reached", consensusResult.consensusReached());
        state.getContext().put("consensus_result", consensusResult);
        state.getContext().put("consensus_votes", consensusResult.votes());
        if (consensusResult.winningBranchId() != null) {
            state.getContext().put("consensus_winning_branch", consensusResult.winningBranchId());
        }

        // Build result metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("consensus_reached", consensusResult.consensusReached());
        metadata.put("strategy", config.getStrategy().name());
        metadata.put("approve_count", consensusResult.approveCount());
        metadata.put("reject_count", consensusResult.rejectCount());
        metadata.put("average_score", consensusResult.averageScore());
        metadata.put("reasoning", consensusResult.reasoning());
        if (!consensusResult.winningBranchIds().isEmpty()) {
            metadata.put("winning_branches", consensusResult.winningBranchIds());
        }
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

        Object output =
                consensusResult.finalOutput() != null
                        ? consensusResult.finalOutput()
                        : consensusResult.reasoning();
        return new NodeResult(
                consensusResult.consensusReached() ? ResultStatus.SUCCESS : ResultStatus.FAILURE,
                output,
                metadata);
    }
}
