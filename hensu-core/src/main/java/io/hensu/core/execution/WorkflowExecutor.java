package io.hensu.core.execution;

import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.pipeline.ProcessorContext;
import io.hensu.core.execution.pipeline.ProcessorPipeline;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/// Main execution engine for Hensu workflows.
///
/// Orchestrates workflow graph traversal from start node to end node,
/// delegating pre- and post-execution logic to {@link ProcessorPipeline}s.
/// The node lifecycle follows: PRE-PIPELINE -> EXECUTE -> POST-PIPELINE.
///
/// ### Contracts
/// - **Precondition**: `workflow.getStartNode()` must exist in the workflow's node map
/// - **Postcondition**: Returns `Completed`, `Paused`, `Rejected`, or `Failure`, never null
/// - **Invariant**: State history is append-only during execution
///
/// @implNote **Not thread-safe**. Each workflow execution should use a dedicated
/// instance. The executor maintains no mutable state between executions, but
/// concurrent executions of the same instance are not supported.
/// @see ProcessorPipeline for post-execution processing chain
/// @see NodeExecutorRegistry for node type dispatch
public class WorkflowExecutor {

    private static final Logger logger = Logger.getLogger(WorkflowExecutor.class.getName());

    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final AgentRegistry agentRegistry;
    private final ExecutorService executorService;
    private final RubricEngine rubricEngine;
    private final TemplateResolver templateResolver;
    private final ActionExecutor actionExecutor;
    private final WorkflowRepository workflowRepository;
    private final ProcessorPipeline prePipeline;
    private final ProcessorPipeline postPipeline;

    /// Creates a workflow executor with core dependencies.
    ///
    /// @param nodeExecutorRegistry registry for node-type-specific executors, not null
    /// @param agentRegistry        registry of available AI agents, not null
    /// @param executorService      thread pool for parallel execution, not null
    /// @param rubricEngine         engine for rubric-based quality evaluation, not null
    /// @param reviewHandler        handler for human review checkpoints, may be null (defaults to
    ///                                                         auto-approve)
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
                new SimpleTemplateResolver(),
                null);
    }

    /// Creates a workflow executor with action execution support.
    ///
    /// @param nodeExecutorRegistry registry for node-type-specific executors, not null
    /// @param agentRegistry        registry of available AI agents, not null
    /// @param executorService      thread pool for parallel execution, not null
    /// @param rubricEngine         engine for rubric-based quality evaluation, not null
    /// @param reviewHandler        handler for human review checkpoints, may be null (defaults to
    ///                                                         auto-approve)
    /// @param actionExecutor       executor for executable actions, may be null (actions logged
    ///                                                         only)
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
                new SimpleTemplateResolver(),
                null);
    }

    /// Creates a workflow executor with all dependencies.
    ///
    /// @param nodeExecutorRegistry registry for node-type-specific executors, not null
    /// @param agentRegistry        registry of available AI agents, not null
    /// @param executorService      thread pool for parallel execution, not null
    /// @param rubricEngine         engine for rubric-based quality evaluation, not null
    /// @param reviewHandler        handler for human review checkpoints, may be null (defaults to
    ///                                                         auto-approve)
    /// @param actionExecutor       executor for executable actions, may be null
    ///                                                         (actions logged only)
    /// @param templateResolver     resolver for `{variable}` syntax in prompts, not null
    /// @param workflowRepository   repository for loading sub-workflow definitions, not null
    public WorkflowExecutor(
            NodeExecutorRegistry nodeExecutorRegistry,
            AgentRegistry agentRegistry,
            ExecutorService executorService,
            RubricEngine rubricEngine,
            ReviewHandler reviewHandler,
            ActionExecutor actionExecutor,
            TemplateResolver templateResolver,
            WorkflowRepository workflowRepository) {
        this.nodeExecutorRegistry = nodeExecutorRegistry;
        this.agentRegistry = agentRegistry;
        this.executorService = executorService;
        this.rubricEngine = rubricEngine;
        reviewHandler = reviewHandler != null ? reviewHandler : ReviewHandler.AUTO_APPROVE;
        this.actionExecutor = actionExecutor;
        this.templateResolver = templateResolver;
        this.workflowRepository = workflowRepository;
        this.prePipeline = ProcessorPipeline.preExecution();
        this.postPipeline = ProcessorPipeline.postExecution(reviewHandler, rubricEngine);
    }

    /// Executes a workflow without observability listener.
    ///
    /// Convenience method that uses a no-op listener.
    ///
    /// @param workflow       the workflow definition to execute, not null
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
    /// each node via type-specific executors. Post-execution processing
    /// (output extraction, history, review, rubric, transitions) is delegated
    /// to the {@link ProcessorPipeline}.
    ///
    /// @param workflow       the workflow definition to execute, not null
    /// @param initialContext initial context variables for template resolution, not null
    /// @param listener       listener for execution events (node start/complete, agent calls),
    /// not null
    /// @return execution result indicating completion or rejection, never null
    /// @throws Exception if execution fails due to missing nodes, agents, or execution errors
    /// @throws IllegalStateException if start node not found or no valid transitions exist
    public ExecutionResult execute(
            Workflow workflow, Map<String, Object> initialContext, ExecutionListener listener)
            throws Exception {

        registerWorkflowAgents(workflow);

        String currentNodeId = workflow.getStartNode();
        String executionId =
                initialContext.containsKey("_execution_id")
                        ? (String) initialContext.get("_execution_id")
                        : UUID.randomUUID().toString();
        HensuState state =
                new HensuState.Builder()
                        .executionId(executionId)
                        .workflowId(workflow.getId())
                        .currentNode(currentNodeId)
                        .context(initialContext)
                        .history(new ExecutionHistory())
                        .build();

        return executeLoop(state, workflow, listener);
    }

    /// Resumes execution of a workflow from a previously saved state.
    ///
    /// Used after a workflow was paused (returned {@link ExecutionResult.Paused})
    /// and needs to continue from the saved checkpoint.
    ///
    /// @param workflow   the workflow definition, not null
    /// @param savedState the state to resume from, not null
    /// @return execution result indicating completion, pause, or rejection, never null
    /// @throws Exception if execution fails
    public ExecutionResult executeFrom(Workflow workflow, HensuState savedState) throws Exception {
        return executeFrom(workflow, savedState, ExecutionListener.NOOP);
    }

    /// Resumes execution of a workflow from a previously saved state with a listener.
    ///
    /// @param workflow   the workflow definition, not null
    /// @param savedState the state to resume from, not null
    /// @param listener   listener for execution events, not null
    /// @return execution result indicating completion, pause, or rejection, never null
    /// @throws Exception if execution fails
    public ExecutionResult executeFrom(
            Workflow workflow, HensuState savedState, ExecutionListener listener) throws Exception {
        registerWorkflowAgents(workflow);
        return executeLoop(savedState, workflow, listener);
    }

    /// Core execution loop shared by {@link #execute} and {@link #executeFrom}.
    ///
    /// Traverses the workflow graph with a symmetric pipeline model:
    /// PRE-PIPELINE -> node execution -> POST-PIPELINE. The loop handles:
    /// node lookup, end-node detection, pre-pipeline directives,
    /// node execution, PENDING detection, and post-pipeline directive dispatch.
    ///
    /// @param state    current workflow state with position, not null
    /// @param workflow workflow definition, not null
    /// @param listener execution event listener, not null
    /// @return execution result, never null
    /// @throws Exception if execution fails
    private ExecutionResult executeLoop(
            HensuState state, Workflow workflow, ExecutionListener listener) throws Exception {

        ExecutionContext context = createExecutionContext(state, workflow, listener);

        while (true) {
            String currentNodeId = state.getCurrentNode();
            Node node = workflow.getNodes().get(currentNodeId);
            if (node == null) {
                throw new IllegalStateException("Node not found: " + currentNodeId);
            }

            // Clear rubric evaluation from previous node to prevent stale data leaking
            state.setRubricEvaluation(null);

            if (node instanceof EndNode endNode) {
                NodeExecutor<EndNode> executor =
                        nodeExecutorRegistry.getExecutorOrThrow(EndNode.class);
                executor.execute(endNode, context);
                return new ExecutionResult.Completed(state, endNode.getExitStatus());
            }

            var preResult = prePipeline.execute(new ProcessorContext(context, node, null));
            if (preResult.isPresent()) return preResult.get();

            NodeResult result = executeNode(node, context);

            if (result.getStatus() == ResultStatus.PENDING) {
                state.setCurrentNode(currentNodeId);
                return new ExecutionResult.Paused(state);
            }

            var postResult = postPipeline.execute(new ProcessorContext(context, node, result));
            if (postResult.isPresent()) return postResult.get();
        }
    }

    /// Creates an execution context with all required services.
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
                .workflowRepository(workflowRepository)
                .rubricEngine(rubricEngine)
                .build();
    }

    /// Executes a node using the type-safe executor registry.
    private NodeResult executeNode(Node node, ExecutionContext context) throws Exception {
        NodeExecutor<Node> executor = nodeExecutorRegistry.getExecutorFor(node);
        return executor.execute(node, context);
    }

    /// Registers all agents defined in the workflow.
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
