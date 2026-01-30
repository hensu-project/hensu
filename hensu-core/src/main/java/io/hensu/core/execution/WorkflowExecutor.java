package io.hensu.core.execution;

import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.*;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.rubric.evaluator.DefaultRubricEvaluator;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.rubric.model.Rubric;
import io.hensu.core.rubric.repository.RubricParser;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.util.JsonUtil;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.*;
import io.hensu.core.workflow.transition.TransitionRule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/// Main execution engine for Hensu workflows.
///
/// Executes workflow graphs from start node to end node, handling:
/// - Node execution via type-specific executors
/// - State management and context variable resolution
/// - Human review checkpoints
/// - Rubric-based quality evaluation and auto-backtracking
/// - Transition rule evaluation
///
/// ### Contracts
/// - **Precondition**: `workflow.getStartNode()` must exist in the workflow's node map
/// - **Postcondition**: Returns `Completed` or `Rejected`, never null
/// - **Invariant**: State history is append-only during execution
///
/// ### Backtracking Thresholds
/// The executor uses rubric scores to determine backtrack severity:
/// - Critical failure (score < 30): Return to the earliest logical step
/// - Moderate failure (score < 60): Return to previous phase
/// - Minor failure (score < 80): Retry current node with improvements
///
/// @implNote **Not thread-safe**. Each workflow execution should use a dedicated
/// instance. The executor maintains no mutable state between executions, but
/// concurrent executions of the same instance are not supported.
///
/// @see NodeExecutorRegistry for node type dispatch
/// @see RubricEngine for quality evaluation
/// @see ReviewHandler for human review integration
public class WorkflowExecutor {

    private static final Logger logger = Logger.getLogger(WorkflowExecutor.class.getName());

    private static final double CRITICAL_FAILURE_THRESHOLD = 30.0;
    private static final double MODERATE_FAILURE_THRESHOLD = 60.0;
    private static final double MINOR_FAILURE_THRESHOLD = 80.0;

    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final AgentRegistry agentRegistry;
    private final ExecutorService executorService;
    private final RubricEngine rubricEngine;
    private final ReviewHandler reviewHandler;
    private final TemplateResolver templateResolver;
    private final ActionExecutor actionExecutor;

    /// Creates a workflow executor with core dependencies.
    ///
    /// @param nodeExecutorRegistry registry for node-type-specific executors, not null
    /// @param agentRegistry registry of available AI agents, not null
    /// @param executorService thread pool for parallel execution, not null
    /// @param rubricEngine engine for rubric-based quality evaluation, not null
    /// @param reviewHandler handler for human review checkpoints, may be null (defaults to
    /// auto-approve)
    public WorkflowExecutor(
            NodeExecutorRegistry nodeExecutorRegistry,
            AgentRegistry agentRegistry,
            ExecutorService executorService,
            RubricEngine rubricEngine,
            ReviewHandler reviewHandler) {
        this(
                nodeExecutorRegistry,
                agentRegistry,
                executorService,
                rubricEngine,
                reviewHandler,
                null,
                new SimpleTemplateResolver());
    }

    /// Creates a workflow executor with action execution support.
    ///
    /// @param nodeExecutorRegistry registry for node-type-specific executors, not null
    /// @param agentRegistry registry of available AI agents, not null
    /// @param executorService thread pool for parallel execution, not null
    /// @param rubricEngine engine for rubric-based quality evaluation, not null
    /// @param reviewHandler handler for human review checkpoints, may be null (defaults to
    /// auto-approve)
    /// @param actionExecutor executor for executable actions, may be null (actions logged only)
    public WorkflowExecutor(
            NodeExecutorRegistry nodeExecutorRegistry,
            AgentRegistry agentRegistry,
            ExecutorService executorService,
            RubricEngine rubricEngine,
            ReviewHandler reviewHandler,
            ActionExecutor actionExecutor) {
        this(
                nodeExecutorRegistry,
                agentRegistry,
                executorService,
                rubricEngine,
                reviewHandler,
                actionExecutor,
                new SimpleTemplateResolver());
    }

    /// Creates a workflow executor with all dependencies.
    ///
    /// @param nodeExecutorRegistry registry for node-type-specific executors, not null
    /// @param agentRegistry registry of available AI agents, not null
    /// @param executorService thread pool for parallel execution, not null
    /// @param rubricEngine engine for rubric-based quality evaluation, not null
    /// @param reviewHandler handler for human review checkpoints, may be null (defaults to
    /// auto-approve)
    /// @param actionExecutor executor for executable actions, may be null (actions logged only)
    /// @param templateResolver resolver for `{variable}` syntax in prompts, not null
    public WorkflowExecutor(
            NodeExecutorRegistry nodeExecutorRegistry,
            AgentRegistry agentRegistry,
            ExecutorService executorService,
            RubricEngine rubricEngine,
            ReviewHandler reviewHandler,
            ActionExecutor actionExecutor,
            TemplateResolver templateResolver) {
        this.nodeExecutorRegistry = nodeExecutorRegistry;
        this.agentRegistry = agentRegistry;
        this.executorService = executorService;
        this.rubricEngine = rubricEngine;
        this.reviewHandler = reviewHandler != null ? reviewHandler : ReviewHandler.AUTO_APPROVE;
        this.actionExecutor = actionExecutor;
        this.templateResolver = templateResolver;
    }

    /// Executes a workflow without observability listener.
    ///
    /// Convenience method that uses a no-op listener.
    ///
    /// @param workflow the workflow definition to execute, not null
    /// @param initialContext initial context variables for template resolution, not null
    /// @return execution result indicating completion or rejection, never null
    /// @throws Exception if execution fails due to missing nodes, agents, or execution errors
    /// @throws IllegalStateException if start node not found or no valid transitions exist
    public ExecutionResult execute(Workflow workflow, Map<String, Object> initialContext)
            throws Exception {
        return execute(workflow, initialContext, ExecutionListener.NOOP);
    }

    /// Executes a workflow with an observability listener.
    ///
    /// Traverses the workflow graph from start node to end node, executing
    /// each node via type-specific executors. Supports human review checkpoints
    /// and rubric-based auto-backtracking.
    ///
    /// ### Performance
    /// - Time: O(n * m) where n = nodes visited, m = average node execution time
    /// - Space: O(h) where h = execution history size (all visited nodes)
    ///
    /// @apiNote **Side effects**:
    /// - Modifies workflow state throughout execution
    /// - May invoke external AI agents
    /// - May trigger human review callbacks
    /// - Logs execution progress at INFO level
    ///
    /// @param workflow the workflow definition to execute, not null
    /// @param initialContext initial context variables for template resolution, not null
    /// @param listener listener for execution events (node start/complete, agent calls), not null
    /// @return execution result indicating completion or rejection, never null
    /// @throws Exception if execution fails due to missing nodes, agents, or execution errors
    /// @throws IllegalStateException if start node not found or no valid transitions exist
    public ExecutionResult execute(
            Workflow workflow, Map<String, Object> initialContext, ExecutionListener listener)
            throws Exception {

        registerWorkflowAgents(workflow);

        String currentNodeId = workflow.getStartNode();
        HensuState state =
                new HensuState(
                        new HashMap<>(initialContext),
                        workflow.getId(),
                        currentNodeId,
                        new ExecutionHistory());

        ExecutionContext context = createExecutionContext(state, workflow, listener);

        while (true) {
            Node node = workflow.getNodes().get(currentNodeId);
            if (node == null) {
                throw new IllegalStateException("Node not found: " + currentNodeId);
            }

            if (node instanceof EndNode endNode) {
                NodeExecutor<EndNode> executor =
                        nodeExecutorRegistry.getExecutorOrThrow(EndNode.class);
                executor.execute(endNode, context);
                return new ExecutionResult.Completed(state, endNode.getExitStatus());
            }

            listener.onNodeStart(node);

            NodeResult result = executeNode(node, context);

            listener.onNodeComplete(node, result);

            if (result.getOutput() != null) {
                state.getContext().put(currentNodeId, result.getOutput().toString());

                if (node instanceof StandardNode standardNode
                        && !standardNode.getOutputParams().isEmpty()) {
                    JsonUtil.extractOutputParams(
                            standardNode.getOutputParams(),
                            result.getOutput().toString(),
                            state.getContext(),
                            logger);
                }
            }

            state.getHistory()
                    .addStep(
                            new ExecutionStep(
                                    currentNodeId, state.snapshot(), result, Instant.now()));

            if (node instanceof StandardNode standardNode) {
                if (standardNode.getReviewConfig() != null) {
                    ReviewDecision reviewDecision =
                            handleReview(standardNode, result, state, workflow);

                    if (reviewDecision instanceof ReviewDecision.Approve approve) {
                        if (approve.getEditedState() != null) {
                            state = approve.getEditedState();
                        }
                    } else if (reviewDecision instanceof ReviewDecision.Backtrack backtrack) {
                        currentNodeId = backtrack.getTargetStep();
                        state = backtrack.getEditedState();
                        state.setCurrentNode(currentNodeId);

                        if (backtrack.hasEditedPrompt()) {
                            String overrideKey = "_prompt_override_" + backtrack.getTargetStep();
                            state.getContext().put(overrideKey, backtrack.getEditedPrompt());
                            logger.info(
                                    "Stored edited prompt for node: " + backtrack.getTargetStep());
                        }

                        state.getHistory()
                                .addBacktrack(
                                        BacktrackEvent.builder()
                                                .from(node.getId())
                                                .to(backtrack.getTargetStep())
                                                .reason(backtrack.getReason())
                                                .build());

                        continue;
                    } else if (reviewDecision instanceof ReviewDecision.Reject reject) {
                        return new ExecutionResult.Rejected(reject.getReason(), state);
                    }
                }
            }

            if (node instanceof StandardNode standardNode) {
                if (standardNode.getRubricId() != null) {
                    Rubric rubric = loadRubric(standardNode.getRubricId(), workflow.getRubrics());
                    rubricEngine.registerRubric(rubric);
                    RubricEvaluation evaluation =
                            rubricEngine.evaluate(rubric.getId(), result, state.getContext());

                    state.setRubricEvaluation(evaluation);

                    if (!evaluation.isPassed()) {
                        AutoBacktrack autoBacktrack =
                                determineAutoBacktrack(evaluation, standardNode, workflow, state);

                        if (autoBacktrack != null) {
                            logger.info(
                                    "Auto-backtracking from "
                                            + node.getId()
                                            + " to "
                                            + autoBacktrack.getTargetNode()
                                            + " due to rubric failure");
                            currentNodeId = autoBacktrack.getTargetNode();
                            state.setCurrentNode(currentNodeId);
                            state.getContext().putAll(autoBacktrack.getContextUpdates());
                            state.getHistory()
                                    .addAutoBacktrack(
                                            node.getId(),
                                            autoBacktrack.getTargetNode(),
                                            "Rubric score: " + evaluation.getScore(),
                                            evaluation);
                            continue;
                        }
                    }
                }
            }

            String nextNodeId = evaluateTransitions(node, state, result);
            if (nextNodeId == null) {
                throw new IllegalStateException("No valid transition from " + node.getId());
            }

            currentNodeId = nextNodeId;
            state.setCurrentNode(currentNodeId);
        }
    }

    /// Creates an execution context with all required services.
    ///
    /// @param state current workflow state, not null
    /// @param workflow workflow definition, not null
    /// @param listener execution listener, not null
    /// @return configured execution context, never null
    private ExecutionContext createExecutionContext(
            HensuState state, Workflow workflow, ExecutionListener listener) {
        return ExecutionContext.builder()
                .state(state)
                .workflow(workflow)
                .listener(listener)
                .agentRegistry(agentRegistry)
                .templateResolver(templateResolver)
                .executorService(executorService)
                .nodeExecutorRegistry(nodeExecutorRegistry)
                .workflowExecutor(this)
                .actionExecutor(actionExecutor)
                .build();
    }

    /// Executes a node using the type-safe executor registry.
    ///
    /// @param node the node to execute, not null
    /// @param context execution context with state and services, not null
    /// @return node execution result, never null
    /// @throws Exception if execution fails
    private NodeResult executeNode(Node node, ExecutionContext context) throws Exception {
        NodeExecutor<Node> executor = nodeExecutorRegistry.getExecutorFor(node);
        return executor.execute(node, context);
    }

    /// Handles human review for a node if configured.
    ///
    /// @param node the node with review configuration, not null
    /// @param result node execution result, not null
    /// @param state current workflow state, not null
    /// @param workflow workflow definition, not null
    /// @return review decision (approve, backtrack, or reject), never null
    private ReviewDecision handleReview(
            StandardNode node, NodeResult result, HensuState state, Workflow workflow) {

        ReviewConfig config = node.getReviewConfig();
        if (config == null) {
            return new ReviewDecision.Approve(null);
        }

        if (config.getMode() == ReviewMode.DISABLED) {
            return new ReviewDecision.Approve(null);
        }

        if (config.getMode() == ReviewMode.OPTIONAL && result.getStatus() == ResultStatus.SUCCESS) {
            return new ReviewDecision.Approve(null);
        }

        logger.info("Requesting human review for node: " + node.getId());
        return reviewHandler.requestReview(
                node, result, state, state.getHistory(), config, workflow);
    }

    /// Determines auto-backtrack target based on rubric evaluation score.
    ///
    /// @param evaluation rubric evaluation result, not null
    /// @param currentNode the node that failed evaluation, not null
    /// @param workflow workflow definition, not null
    /// @param state current workflow state, not null
    /// @return auto-backtrack instruction, or null if no backtrack needed
    private AutoBacktrack determineAutoBacktrack(
            RubricEvaluation evaluation,
            StandardNode currentNode,
            Workflow workflow,
            HensuState state) {

        List<String> selfRecommendations =
                (List<String>) state.getContext().get(DefaultRubricEvaluator.RECOMMENDATIONS_KEY);

        if (evaluation.getScore() < CRITICAL_FAILURE_THRESHOLD) {
            String startNode = findEarliestLogicalStep(state, workflow);
            Map<String, Object> updates = new HashMap<>();
            updates.put("backtrack_reason", "Critical rubric failure: " + evaluation.getScore());
            updates.put("failed_criteria", evaluation.getFailedCriteria());
            addRecommendationsToContext(updates, selfRecommendations, evaluation);
            return new AutoBacktrack(startNode, updates);
        }

        if (evaluation.getScore() < MODERATE_FAILURE_THRESHOLD) {
            String previousPhase = findPreviousPhase(currentNode.getId(), state, workflow);
            if (previousPhase != null) {
                Map<String, Object> updates = new HashMap<>();
                updates.put(
                        "backtrack_reason", "Moderate rubric failure: " + evaluation.getScore());
                updates.put("improvement_suggestions", evaluation.getSuggestions());
                addRecommendationsToContext(updates, selfRecommendations, evaluation);
                return new AutoBacktrack(previousPhase, updates);
            }
        }

        if (evaluation.getScore() < MINOR_FAILURE_THRESHOLD) {
            Map<String, Object> updates = new HashMap<>();
            Integer retryAttempt = (Integer) state.getContext().get("retry_attempt");
            updates.put("retry_attempt", retryAttempt != null ? retryAttempt + 1 : 1);
            updates.put("improvement_hints", evaluation.getSuggestions());
            addRecommendationsToContext(updates, selfRecommendations, evaluation);
            return new AutoBacktrack(currentNode.getId(), updates);
        }

        return null;
    }

    /// Adds self-evaluation recommendations to context for prompt injection.
    ///
    /// @param updates context updates map to populate, not null
    /// @param selfRecommendations recommendations from self-evaluation, may be null
    /// @param evaluation rubric evaluation with suggestions, not null
    private void addRecommendationsToContext(
            Map<String, Object> updates,
            List<String> selfRecommendations,
            RubricEvaluation evaluation) {

        StringBuilder recommendations = new StringBuilder();

        if (selfRecommendations != null && !selfRecommendations.isEmpty()) {
            recommendations.append("Self-evaluation recommendations:\n");
            for (String rec : selfRecommendations) {
                recommendations.append("- ").append(rec).append("\n");
            }
        }

        List<String> rubricSuggestions = evaluation.getSuggestions();
        if (rubricSuggestions != null && !rubricSuggestions.isEmpty()) {
            if (!recommendations.isEmpty()) {
                recommendations.append("\n");
            }
            recommendations.append("Rubric improvement areas:\n");
            for (String suggestion : rubricSuggestions) {
                recommendations.append("- ").append(suggestion).append("\n");
            }
        }

        if (!recommendations.isEmpty()) {
            updates.put("recommendations", recommendations.toString().trim());
            logger.info("Injecting recommendations into backtrack context:\n" + recommendations);
        }
    }

    /// Evaluates transition rules to determine the next node.
    ///
    /// @param node current node, not null
    /// @param state current workflow state, not null
    /// @param result node execution result, not null
    /// @return next node ID, or null if no valid transition
    private String evaluateTransitions(Node node, HensuState state, NodeResult result) {
        String loopBreakTarget = state.getLoopBreakTarget();
        if (loopBreakTarget != null) {
            state.setLoopBreakTarget(null);
            return loopBreakTarget;
        }

        if (node instanceof LoopNode) {
            return (String) state.getContext().get("loop_exit_target");
        }

        for (TransitionRule rule : node.getTransitionRules()) {
            String target = rule.evaluate(state, result);
            if (target != null) {
                return target;
            }
        }

        return null;
    }

    /// Finds the earliest logical step for critical backtracking.
    ///
    /// @param state current workflow state, not null
    /// @param workflow workflow definition, not null
    /// @return node ID of earliest rubric-evaluated step, or start node
    private String findEarliestLogicalStep(HensuState state, Workflow workflow) {
        Optional<ExecutionStep> firstMajorStep =
                state.getHistory().getSteps().stream()
                        .filter(
                                step -> {
                                    Node node = workflow.getNodes().get(step.getNodeId());
                                    if (node instanceof StandardNode standardNode) {
                                        return standardNode.getRubricId() != null;
                                    }
                                    return false;
                                })
                        .findFirst();

        return firstMajorStep.map(ExecutionStep::getNodeId).orElse(workflow.getStartNode());
    }

    /// Finds the previous phase for moderate backtracking.
    ///
    /// @param currentNodeId current node ID, not null
    /// @param state current workflow state, not null
    /// @param workflow workflow definition, not null
    /// @return node ID of previous phase, or null if none found
    private String findPreviousPhase(String currentNodeId, HensuState state, Workflow workflow) {
        Node currentNode = workflow.getNodes().get(currentNodeId);
        String currentRubric = null;
        if (currentNode instanceof StandardNode) {
            currentRubric = currentNode.getRubricId();
        }

        List<ExecutionStep> steps = state.getHistory().getSteps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            ExecutionStep step = steps.get(i);
            Node stepNode = workflow.getNodes().get(step.getNodeId());
            if (stepNode instanceof StandardNode standardNode) {
                if (standardNode.getRubricId() != null
                        && !Objects.equals(standardNode.getRubricId(), currentRubric)) {
                    return step.getNodeId();
                }
            }
        }

        return null;
    }

    /// Loads a rubric by ID from the workflow's rubric paths.
    ///
    /// @param rubricId rubric identifier, not null
    /// @param rubrics map of rubric IDs to file paths, not null
    /// @return parsed rubric, never null
    /// @throws IllegalStateException if rubric ID not found in map
    private Rubric loadRubric(String rubricId, Map<String, String> rubrics) {
        String rubricPath = rubrics.get(rubricId);
        if (rubricPath == null) {
            throw new IllegalStateException("Rubric not found: " + rubricId);
        }

        return RubricParser.parse(Path.of(rubricPath));
    }

    /// Registers all agents defined in the workflow.
    ///
    /// @apiNote **Side effects**:
    /// - Modifies the agent registry by adding workflow-defined agents
    /// - Logs registration at INFO level
    ///
    /// @param workflow workflow containing agent definitions, not null
    private void registerWorkflowAgents(Workflow workflow) {
        workflow.getAgents()
                .forEach(
                        (agentId, config) -> {
                            if (!agentRegistry.hasAgent(agentId)) {
                                logger.info("Auto-registering agent from workflow: " + agentId);
                                agentRegistry.registerAgent(agentId, config);
                            }
                        });
    }
}
