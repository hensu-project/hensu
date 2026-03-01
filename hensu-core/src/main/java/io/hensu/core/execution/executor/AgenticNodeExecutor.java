package io.hensu.core.execution.executor;

import io.hensu.core.plan.PlanContext;
import io.hensu.core.plan.PlanExecutor;
import io.hensu.core.plan.PlanObserver;
import io.hensu.core.plan.PlanPipeline;
import io.hensu.core.plan.Planner;
import io.hensu.core.tool.ToolRegistry;
import io.hensu.core.workflow.node.StandardNode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/// Executor for {@link StandardNode} with full planning support.
///
/// Delegates simple (non-planning) nodes to {@link StandardNodeExecutor}.
/// For planning-enabled nodes, runs two sequential {@link PlanPipeline}s:
///
/// ### Pre-execution pipeline
///
/// ```
/// +————————————————————————+
/// │  PlanCreationProcessor │  STATIC or DYNAMIC plan
/// +————————————————————————+
///            │
///            V
/// +——————————————————————+
/// │  ReviewGateProcessor │  Gate 1 — review plan structure (optional PENDING)
/// +——————————————————————+
/// ```
///
/// ### Execution pipeline
///
/// ```
/// +————————————————————————————————+
/// │ SynthesizeEnrichmentProcessor  │  fills agentId on synthesize steps
/// +————————————————————————————————+
///            │
///            V
/// +—————————————————————————+
/// │  PlanExecutionProcessor │  step loop + revision
/// +—————————————————————————+
///            │
///            V
/// +——————————————————————————————————+
/// │ PostExecutionReviewGateProcessor │  Gate 2 — review results (optional PENDING)
/// +——————————————————————————————————+
/// ```
///
/// Both review gates are controlled by the single
/// {@link io.hensu.core.plan.PlanningConfig#review()} flag.
/// If Gate 1 returns PENDING, the execution pipeline never runs.
///
/// ### Thread Safety
/// @implNote Thread-safe. All state flows through {@link PlanContext};
/// the executor holds no mutable state.
///
/// @see PlanPipeline for pipeline composition
/// @see PlanExecutor for step execution
/// @see io.hensu.core.plan.LlmPlanner for dynamic plan generation
public class AgenticNodeExecutor implements NodeExecutor<StandardNode> {

    private static final Logger LOG = Logger.getLogger(AgenticNodeExecutor.class.getName());

    private final StandardNodeExecutor simpleExecutor;
    private final PlanPipeline prePipeline;
    private final PlanPipeline executionPipeline;

    /// Creates the executor with required dependencies.
    ///
    /// Wires the pre-execution pipeline ({@link PlanCreationProcessor} →
    /// {@link ReviewGateProcessor}) and the execution pipeline
    /// ({@link SynthesizeEnrichmentProcessor} → {@link PlanExecutionProcessor} →
    /// {@link PostExecutionReviewGateProcessor}).
    ///
    /// @param planner        planner for dynamic plan generation, not null
    /// @param planExecutor  executor for plan steps, not null
    /// @param toolRegistry  registry of available tools for the planner, not null
    /// @param observers     plan event observers, may be empty but not null
    public AgenticNodeExecutor(
            Planner planner,
            PlanExecutor planExecutor,
            ToolRegistry toolRegistry,
            List<PlanObserver> observers) {
        Objects.requireNonNull(planner, "planner must not be null");
        Objects.requireNonNull(planExecutor, "planExecutor must not be null");
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        List<PlanObserver> safeObservers = observers != null ? List.copyOf(observers) : List.of();

        this.simpleExecutor = new StandardNodeExecutor();
        this.prePipeline =
                new PlanPipeline(
                        List.of(
                                new PlanCreationProcessor(planner, toolRegistry),
                                new ReviewGateProcessor()));
        this.executionPipeline =
                new PlanPipeline(
                        List.of(
                                new SynthesizeEnrichmentProcessor(),
                                new PlanExecutionProcessor(
                                        planner, planExecutor, toolRegistry, safeObservers),
                                new PostExecutionReviewGateProcessor()));
    }

    @Override
    public Class<StandardNode> getNodeType() {
        return StandardNode.class;
    }

    @Override
    public NodeResult execute(StandardNode node, ExecutionContext context) {
        if (!node.hasPlanningEnabled()) {
            LOG.fine(
                    "Planning disabled for node " + node.getId() + ", executing simple agent call");
            return simpleExecutor.execute(node, context);
        }

        PlanContext ctx = new PlanContext(node, context);

        Optional<NodeResult> preResult = prePipeline.execute(ctx);
        return preResult.orElseGet(
                () ->
                        executionPipeline
                                .execute(ctx)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Execution pipeline did not produce a result for node "
                                                                + node.getId())));
    }
}
