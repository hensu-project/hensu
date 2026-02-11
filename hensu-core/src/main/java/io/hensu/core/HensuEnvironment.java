package io.hensu.core;

import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.rubric.RubricRepository;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.WorkflowRepository;
import java.util.concurrent.ExecutorService;

/// Container holding all core Hensu components required for workflow execution.
///
/// This class serves as the central access point for all Hensu services after
/// environment initialization. It implements {@link AutoCloseable} to ensure
/// proper cleanup of resources, particularly the underlying thread pool.
///
/// ### Contracts
/// - **Precondition**: All constructor parameters must be non-null
/// - **Postcondition**: All getters return the same instances passed to constructor
/// - **Invariant**: Component references are immutable after construction
///
/// @implNote **Not thread-safe** for mutation, but safe for concurrent reads.
/// All fields are final and set at construction time. The contained components
/// may have their own thread-safety guarantees.
///
/// @apiNote Create instances via {@link HensuFactory#createEnvironment()} or
/// {@link HensuFactory.Builder} rather than direct construction.
///
/// @see HensuFactory#createEnvironment()
/// @see HensuFactory.Builder
public final class HensuEnvironment implements AutoCloseable {

    private final WorkflowExecutor workflowExecutor;
    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final AgentRegistry agentRegistry;
    private final RubricRepository rubricRepository;
    private final ExecutorService executorService;
    private final ActionExecutor actionExecutor;
    private final WorkflowRepository workflowRepository;
    private final WorkflowStateRepository workflowStateRepository;

    /// Creates a new Hensu environment with the specified components.
    ///
    /// @param workflowExecutor the executor for running workflows, not null
    /// @param nodeExecutorRegistry registry of node type executors, not null
    /// @param agentRegistry registry for AI agent instances, not null
    /// @param rubricRepository storage for rubric definitions, not null
    /// @param executorService thread pool for parallel execution, not null
    /// @param actionExecutor executor for executable actions, may be null for logging-only mode
    /// @param workflowRepository tenant-scoped storage for workflow definitions, not null
    /// @param workflowStateRepository tenant-scoped storage for execution state snapshots, not null
    public HensuEnvironment(
            WorkflowExecutor workflowExecutor,
            NodeExecutorRegistry nodeExecutorRegistry,
            AgentRegistry agentRegistry,
            RubricRepository rubricRepository,
            ExecutorService executorService,
            ActionExecutor actionExecutor,
            WorkflowRepository workflowRepository,
            WorkflowStateRepository workflowStateRepository) {
        this.workflowExecutor = workflowExecutor;
        this.nodeExecutorRegistry = nodeExecutorRegistry;
        this.agentRegistry = agentRegistry;
        this.rubricRepository = rubricRepository;
        this.executorService = executorService;
        this.actionExecutor = actionExecutor;
        this.workflowRepository = workflowRepository;
        this.workflowStateRepository = workflowStateRepository;
    }

    /// Returns the workflow executor for running workflow definitions.
    ///
    /// @return the workflow executor instance, never null
    public WorkflowExecutor getWorkflowExecutor() {
        return workflowExecutor;
    }

    /// Returns the registry containing executors for each node type.
    ///
    /// @return the node executor registry, never null
    public NodeExecutorRegistry getNodeExecutorRegistry() {
        return nodeExecutorRegistry;
    }

    /// Returns the registry for managing AI agent instances.
    ///
    /// @return the agent registry, never null
    public AgentRegistry getAgentRegistry() {
        return agentRegistry;
    }

    /// Returns the repository for storing and retrieving rubric definitions.
    ///
    /// @return the rubric repository, never null
    public RubricRepository getRubricRepository() {
        return rubricRepository;
    }

    /// Returns the executor for executable actions.
    ///
    /// @return the action executor, may be null if running in logging-only mode
    public ActionExecutor getActionExecutor() {
        return actionExecutor;
    }

    /// Returns the repository for storing and retrieving workflow definitions.
    ///
    /// Defaults to {@link io.hensu.core.workflow.InMemoryWorkflowRepository} when
    /// no custom implementation is registered via {@link HensuFactory.Builder}.
    ///
    /// @return the workflow repository, never null
    public WorkflowRepository getWorkflowRepository() {
        return workflowRepository;
    }

    /// Returns the repository for persisting workflow execution state snapshots.
    ///
    /// Defaults to {@link io.hensu.core.state.InMemoryWorkflowStateRepository} when
    /// no custom implementation is registered via {@link HensuFactory.Builder}.
    ///
    /// @return the workflow state repository, never null
    public WorkflowStateRepository getWorkflowStateRepository() {
        return workflowStateRepository;
    }

    /// Shuts down the underlying executor service gracefully.
    ///
    /// @apiNote **Side effects**:
    /// - Initiates orderly shutdown of the thread pool
    /// - Previously submitted tasks continue to execute
    /// - No new tasks will be accepted after this call
    ///
    /// @implNote Calls `ExecutorService.shutdown()` which does not block.
    /// Use `ExecutorService.awaitTermination()` on the executor directly
    /// if blocking shutdown is required.
    @Override
    public void close() {
        executorService.shutdown();
    }
}
