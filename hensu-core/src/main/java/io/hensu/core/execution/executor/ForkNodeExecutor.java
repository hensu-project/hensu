package io.hensu.core.execution.executor;

import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.SynchronizedListenerDecorator;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.parallel.BranchResult;
import io.hensu.core.execution.parallel.FailureMarker;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.util.JsonUtil;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.ForkNode;
import io.hensu.core.workflow.node.JoinNode;
import io.hensu.core.workflow.node.MergeStrategy;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/// Executes fork nodes by spawning parallel sub-flows using {@link StructuredTaskScope}.
///
/// ### Lifecycle
///
/// The fork node owns the entire structured concurrency lifecycle:
/// open scope → fork sub-flows → join → merge → store results.
/// {@link StructuredTaskScope} requires lexical scoping (try-with-resources),
/// so fork and join MUST happen in the same method. The corresponding
/// {@link io.hensu.core.execution.executor.JoinNodeExecutor} is a passthrough –
/// results are already in state when it executes.
///
/// ### Sub-flow execution
///
/// Each fork target is the start node of a sub-flow that traverses multiple
/// nodes until reaching the join boundary. Sub-flows run via
/// {@link WorkflowExecutor#executeUntil}, which executes the full pipeline
/// (pre- / post-processors, output extraction via {@code writes()}) for each
/// sub-flow node. The fork executor does NOT extract outputs – the pipeline
/// already did that.
///
/// ### Branch isolation
///
/// Each sub-flow receives an isolated state copy via {@link HensuState#branch}.
/// Branch writes never leak to siblings or the parent. After join, the fork
/// executor reads branch state contexts and merges them according to
/// {@link MergeStrategy}.
///
/// ### Thread-safe listener
///
/// The parent {@link io.hensu.core.execution.ExecutionListener} is wrapped in a
/// {@link SynchronizedListenerDecorator} before sub-flow submission so that
/// multi-line output does not interleave across concurrent branches.
///
/// @implNote Uses Java 25 preview API ({@code StructuredTaskScope}).
/// Compile with {@code --enable-preview}.
///
/// @see JoinNodeExecutor for the passthrough boundary marker
/// @see WorkflowExecutor#executeUntil for sub-flow boundary execution
/// @see HensuState#branch for branch isolation mechanism
public class ForkNodeExecutor implements NodeExecutor<ForkNode> {

    private static final Logger logger = Logger.getLogger(ForkNodeExecutor.class.getName());

    // -- Metadata keys --------------------------------------------------------

    static final String FORK_NODE_ID = "fork_node_id";
    static final String JOIN_NODE_ID = "join_node_id";
    static final String MERGE_STRATEGY = "merge_strategy";
    static final String TARGET_COUNT = "target_count";
    static final String FAILED_BRANCHES = "failed_branches";
    static final String EXECUTION_TIME_MS = "execution_time_ms";

    @Override
    public Class<ForkNode> getNodeType() {
        return ForkNode.class;
    }

    @Override
    public NodeResult execute(ForkNode node, ExecutionContext context) throws Exception {
        HensuState state = context.getState();
        Workflow workflow = context.getWorkflow();
        WorkflowExecutor workflowExecutor = context.getWorkflowExecutor();

        // Resolve join node from fork's success transition
        JoinNode joinNode = resolveJoinNode(node, workflow);
        String joinNodeId = joinNode.getId();
        MergeStrategy mergeStrategy = joinNode.getMergeStrategy();
        List<String> exports = joinNode.getExports();
        long timeoutMs = joinNode.getTimeoutMs();

        logger.info(
                "Forking at '"
                        + node.getId()
                        + "' → "
                        + node.getTargets().size()
                        + " sub-flows → join at '"
                        + joinNodeId
                        + "' ("
                        + mergeStrategy
                        + ")");

        // Thread-safe listener for concurrent sub-flow output
        var safeListener = new SynchronizedListenerDecorator(context.getListener());

        // Fork sub-flows using StructuredTaskScope
        List<BranchResult> branchResults;
        var threadFactory = Thread.ofVirtual().name("fork-" + node.getId() + "-", 0).factory();
        try (var scope =
                StructuredTaskScope.open(
                        StructuredTaskScope.Joiner.awaitAll(),
                        cf -> {
                            cf =
                                    cf.withThreadFactory(threadFactory)
                                            .withName("fork-" + node.getId());
                            return timeoutMs > 0
                                    ? cf.withTimeout(Duration.ofMillis(timeoutMs))
                                    : cf;
                        })) {

            List<Subtask<BranchResult>> subtasks = new ArrayList<>();

            for (String targetId : node.getTargets()) {
                Node targetNode = workflow.getNodes().get(targetId);
                if (targetNode == null) {
                    logger.warning("Fork target node not found: " + targetId);
                    continue;
                }

                subtasks.add(
                        scope.fork(
                                () ->
                                        executeSubFlow(
                                                targetId,
                                                joinNodeId,
                                                exports,
                                                state,
                                                workflow,
                                                workflowExecutor,
                                                safeListener)));
            }

            scope.join();

            branchResults = subtasks.stream().map(Subtask::get).toList();
        }

        // Log failures but always proceed to merge — fork always transitions to
        // join. The join passthrough owns the success/failure routing decision.
        List<String> failed =
                branchResults.stream()
                        .filter(br -> br.result().getStatus() == ResultStatus.FAILURE)
                        .map(BranchResult::getBranchId)
                        .toList();
        if (!failed.isEmpty()) {
            logger.warning("Fork '" + node.getId() + "' sub-flow failures: " + failed);
            state.getContext().put(FAILED_BRANCHES, failed);
        }

        // Merge results according to strategy (includes partial results)
        Object mergedOutput = mergeResults(branchResults, mergeStrategy);

        // Store merged result in state under join node's writes() variables
        List<String> writes = joinNode.getWrites();
        if (mergeStrategy == MergeStrategy.MERGE_MAPS) {
            // MERGE_MAPS: spread – each writes() var gets its own state entry
            Map<String, Object> spreadMap = (Map<String, Object>) mergedOutput;
            for (String varName : writes) {
                if (spreadMap.containsKey(varName)) {
                    state.getContext().put(varName, spreadMap.get(varName));
                }
            }
        } else {
            // COLLECT_ALL, FIRST_SUCCESSFUL, CONCATENATE: single blob
            state.getContext().put(writes.getFirst(), mergedOutput);
        }

        logger.info(
                "Fork '"
                        + node.getId()
                        + "' completed. Merged "
                        + branchResults.size()
                        + " sub-flows → "
                        + writes);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(FORK_NODE_ID, node.getId());
        metadata.put(JOIN_NODE_ID, joinNodeId);
        metadata.put(TARGET_COUNT, node.getTargets().size());
        metadata.put(MERGE_STRATEGY, mergeStrategy.name());

        return NodeResult.builder()
                .status(ResultStatus.SUCCESS)
                .output(mergedOutput)
                .metadata(metadata)
                .build();
    }

    // -- Sub-flow execution ---------------------------------------------------

    /// Executes a single sub-flow from fork target to join boundary.
    ///
    /// The sub-flow traverses the full pipeline for each node via
    /// {@link WorkflowExecutor#executeUntil}. Variables written by sub-flow
    /// nodes via {@code writes()} are already extracted into branch state
    /// by the pipeline – no additional extraction needed here.
    ///
    /// ### Yields isolation
    ///
    /// Fork/join sub-flows have no explicit {@code yields()} declarations
    /// (unlike parallel branches). Instead, yields are derived by diffing
    /// the branch state against the parent snapshot: only keys that were
    /// **added or mutated** by the sub-flow pipeline are collected. Engine
    /// internals ({@code _}-prefixed keys, {@code current_node}) are always
    /// stripped – they must never reach merge strategies or the LLM.
    private BranchResult executeSubFlow(
            String targetId,
            String joinNodeId,
            List<String> exports,
            HensuState parentState,
            Workflow workflow,
            WorkflowExecutor workflowExecutor,
            ExecutionListener listener) {

        long startTime = System.currentTimeMillis();

        // Snapshot parent context before branching for post-execution diff.
        // Collections.unmodifiableMap (not Map.copyOf) – context may contain null values.
        @SuppressWarnings("Java9CollectionFactory")
        Map<String, Object> parentSnapshot =
                Collections.unmodifiableMap(new HashMap<>(parentState.getContext()));

        try {
            logger.info("Sub-flow starting: " + targetId);

            HensuState branchState = parentState.branch(targetId);
            ExecutionResult result =
                    workflowExecutor.executeUntil(joinNodeId, branchState, workflow, listener);

            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("Sub-flow completed: " + targetId + " in " + elapsed + "ms");

            // Diff branch state against parent: collect only new or mutated keys.
            // Engine-internal keys are stripped – they must not leak to merge output.
            // If exports whitelist is non-empty, only whitelisted keys cross the boundary.
            Map<String, Object> yields = new HashMap<>();
            branchState
                    .getContext()
                    .forEach(
                            (key, value) -> {
                                if (key.startsWith("_") || key.equals("current_node")) return;
                                if (!exports.isEmpty() && !exports.contains(key)) return;
                                if (!parentSnapshot.containsKey(key)
                                        || !Objects.equals(parentSnapshot.get(key), value)) {
                                    yields.put(key, value);
                                }
                            });

            ResultStatus status =
                    result instanceof ExecutionResult.Completed
                            ? ResultStatus.SUCCESS
                            : ResultStatus.FAILURE;

            return new BranchResult(
                    targetId,
                    new NodeResult(status, yields, Map.of(EXECUTION_TIME_MS, elapsed)),
                    yields,
                    1.0);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.warning("Sub-flow failed: " + targetId + " – " + e.getMessage());

            return new BranchResult(
                    targetId,
                    new NodeResult(
                            ResultStatus.FAILURE,
                            "Sub-flow execution failed: " + e.getMessage(),
                            Map.of("error", e.getClass().getName(), EXECUTION_TIME_MS, elapsed)),
                    Map.of(),
                    1.0);
        }
    }

    // -- Join node resolution -------------------------------------------------

    /// Resolves the JoinNode from the fork node's success transition.
    ///
    /// The fork's {@code onComplete goto "join-id"} creates a {@link SuccessTransition}
    /// pointing to the join node. This method finds that transition and resolves
    /// the target node from the workflow.
    private JoinNode resolveJoinNode(ForkNode forkNode, Workflow workflow) {
        String joinNodeId =
                forkNode.getTransitionRules().stream()
                        .filter(SuccessTransition.class::isInstance)
                        .map(SuccessTransition.class::cast)
                        .map(SuccessTransition::getTargetNode)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "ForkNode '"
                                                        + forkNode.getId()
                                                        + "' has no success transition to a join node"));

        Node target = workflow.getNodes().get(joinNodeId);
        if (!(target instanceof JoinNode joinNode)) {
            throw new IllegalStateException(
                    "ForkNode '"
                            + forkNode.getId()
                            + "' success transition '"
                            + joinNodeId
                            + "' does not point to a JoinNode (got "
                            + (target != null ? target.getClass().getSimpleName() : "null")
                            + ")");
        }
        return joinNode;
    }

    // -- Merge strategies (moved from JoinNodeExecutor) -----------------------

    /// Merges sub-flow results according to the specified strategy.
    private Object mergeResults(List<BranchResult> results, MergeStrategy strategy) {
        return switch (strategy) {
            case COLLECT_ALL -> collectAll(results);
            case FIRST_SUCCESSFUL -> firstSuccessful(results);
            case CONCATENATE -> concatenate(results);
            case MERGE_MAPS -> mergeMaps(results);
        };
    }

    /// COLLECT_ALL: map of targetId → branch yields for all branches.
    private Map<String, Object> collectAll(List<BranchResult> results) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (BranchResult br : results) {
            if (br.result().getStatus() == ResultStatus.SUCCESS) {
                output.put(br.getBranchId(), br.yields());
            } else {
                String msg =
                        br.result().getOutput() != null
                                ? br.result().getOutput().toString()
                                : "Sub-flow returned null output";
                output.put(br.getBranchId(), new FailureMarker(msg));
            }
        }
        return output;
    }

    /// FIRST_SUCCESSFUL: yields from the first successful branch.
    private Map<String, Object> firstSuccessful(List<BranchResult> results) {
        for (BranchResult br : results) {
            if (br.result().getStatus() == ResultStatus.SUCCESS) {
                return br.yields();
            }
        }
        logger.warning("FIRST_SUCCESSFUL: no successful branch found");
        return null;
    }

    /// CONCATENATE: all successful branch yields joined as formatted text.
    private String concatenate(List<BranchResult> results) {
        return results.stream()
                .filter(br -> br.result().getStatus() == ResultStatus.SUCCESS)
                .map(br -> JsonUtil.toJson(br.yields()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /// MERGE_MAPS: all successful branch yields merged into one map.
    /// Values are stored as structured types –
    /// {@link io.hensu.core.template.SimpleTemplateResolver}
    /// serializes Maps/Collections to JSON on demand during template resolution.
    private Map<String, Object> mergeMaps(List<BranchResult> results) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (BranchResult br : results) {
            if (br.result().getStatus() == ResultStatus.SUCCESS) {
                for (Map.Entry<String, Object> entry : br.yields().entrySet()) {
                    if (merged.containsKey(entry.getKey())) {
                        logger.warning(
                                "MERGE_MAPS key collision on '"
                                        + entry.getKey()
                                        + "' from branch '"
                                        + br.getBranchId()
                                        + "'");
                    }
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return merged;
    }
}
