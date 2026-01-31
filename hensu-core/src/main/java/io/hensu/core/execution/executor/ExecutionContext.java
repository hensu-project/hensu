package io.hensu.core.execution.executor;

import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.workflow.Workflow;
import java.util.concurrent.ExecutorService;

/// Encapsulates all dependencies and state needed for node execution.
///
/// This context object bundles execution-related services and state, allowing
/// each NodeExecutor to access only what it needs without requiring a bloated
/// method signature. Context is immutable but provides `with*` methods for
/// creating modified copies for sub-workflow execution.
///
/// ### Required Fields
/// - `state` - Current workflow execution state
/// - `workflow` - Workflow definition being executed
///
/// ### Optional Services
/// - `agentRegistry` - For resolving agent instances
/// - `templateResolver` - For prompt variable substitution
/// - `executorService` - For parallel node execution
/// - `nodeExecutorRegistry` - For node type dispatch
/// - `workflowExecutor` - For sub-workflow invocation
/// - `actionExecutor` - For command/action execution
///
/// @implNote Immutable after construction. Thread-safe for read access.
/// Modified copies can be created via {@link #withState} and {@link #withWorkflow}.
///
/// @see NodeExecutor for node execution logic
/// @see WorkflowExecutor for main execution loop
public final class ExecutionContext {

    // Required - always needed
    private final HensuState state;
    private final Workflow workflow;
    private final ExecutionListener listener;

    // Services - provided by registry, executors pull what they need
    private final AgentRegistry agentRegistry;
    private final TemplateResolver templateResolver;
    private final ExecutorService executorService;
    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final WorkflowExecutor workflowExecutor;
    private final ActionExecutor actionExecutor;

    private ExecutionContext(Builder builder) {
        this.state = builder.state;
        this.workflow = builder.workflow;
        this.listener = builder.listener;
        this.agentRegistry = builder.agentRegistry;
        this.templateResolver = builder.templateResolver;
        this.executorService = builder.executorService;
        this.nodeExecutorRegistry = builder.nodeExecutorRegistry;
        this.workflowExecutor = builder.workflowExecutor;
        this.actionExecutor = builder.actionExecutor;
    }

    /// Returns the current workflow execution state.
    ///
    /// @return current state, never null
    public HensuState getState() {
        return state;
    }

    /// Returns the workflow definition being executed.
    ///
    /// @return workflow definition, never null
    public Workflow getWorkflow() {
        return workflow;
    }

    /// Returns the execution event listener.
    ///
    /// @return listener (defaults to NOOP), never null
    public ExecutionListener getListener() {
        return listener;
    }

    /// Returns the agent registry for resolving agents.
    ///
    /// @return agent registry, or null if not configured
    public AgentRegistry getAgentRegistry() {
        return agentRegistry;
    }

    /// Returns the template resolver for prompt variables.
    ///
    /// @return template resolver, or null if not configured
    public TemplateResolver getTemplateResolver() {
        return templateResolver;
    }

    /// Returns the executor service for parallel execution.
    ///
    /// @return executor service, or null if not configured
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /// Returns the node executor registry for type dispatch.
    ///
    /// @return node executor registry, or null if not configured
    public NodeExecutorRegistry getNodeExecutorRegistry() {
        return nodeExecutorRegistry;
    }

    /// Returns the workflow executor for sub-workflow invocation.
    ///
    /// @return workflow executor, or null if not configured
    public WorkflowExecutor getWorkflowExecutor() {
        return workflowExecutor;
    }

    /// Returns the action executor for command execution.
    ///
    /// @return action executor, or null if not configured
    public ActionExecutor getActionExecutor() {
        return actionExecutor;
    }

    /// Creates a new context builder.
    ///
    /// @return new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Creates a child context with a different state.
    ///
    /// Used for sub-workflow execution or branch contexts where only
    /// the state differs from the parent context.
    ///
    /// @param newState the new execution state, not null
    /// @return new context with updated state, never null
    public ExecutionContext withState(HensuState newState) {
        return builder()
                .state(newState)
                .workflow(this.workflow)
                .listener(this.listener)
                .agentRegistry(this.agentRegistry)
                .templateResolver(this.templateResolver)
                .executorService(this.executorService)
                .nodeExecutorRegistry(this.nodeExecutorRegistry)
                .workflowExecutor(this.workflowExecutor)
                .actionExecutor(this.actionExecutor)
                .build();
    }

    /// Creates a child context with a different workflow.
    ///
    /// Used for sub-workflow invocation where the workflow definition
    /// changes but services remain the same.
    ///
    /// @param newWorkflow the new workflow definition, not null
    /// @return new context with updated workflow, never null
    public ExecutionContext withWorkflow(Workflow newWorkflow) {
        return builder()
                .state(this.state)
                .workflow(newWorkflow)
                .listener(this.listener)
                .agentRegistry(this.agentRegistry)
                .templateResolver(this.templateResolver)
                .executorService(this.executorService)
                .nodeExecutorRegistry(this.nodeExecutorRegistry)
                .workflowExecutor(this.workflowExecutor)
                .actionExecutor(this.actionExecutor)
                .build();
    }

    /// Builder for constructing ExecutionContext instances.
    ///
    /// Required fields: `state`, `workflow`
    ///
    /// @see #build() for validation rules
    public static final class Builder {
        private HensuState state;
        private Workflow workflow;
        private ExecutionListener listener = ExecutionListener.NOOP;
        private AgentRegistry agentRegistry;
        private TemplateResolver templateResolver;
        private ExecutorService executorService;
        private NodeExecutorRegistry nodeExecutorRegistry;
        private WorkflowExecutor workflowExecutor;
        private ActionExecutor actionExecutor;

        private Builder() {}

        public Builder state(HensuState state) {
            this.state = state;
            return this;
        }

        public Builder workflow(Workflow workflow) {
            this.workflow = workflow;
            return this;
        }

        public Builder listener(ExecutionListener listener) {
            this.listener = listener != null ? listener : ExecutionListener.NOOP;
            return this;
        }

        public Builder agentRegistry(AgentRegistry agentRegistry) {
            this.agentRegistry = agentRegistry;
            return this;
        }

        public Builder templateResolver(TemplateResolver templateResolver) {
            this.templateResolver = templateResolver;
            return this;
        }

        public Builder executorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        public Builder nodeExecutorRegistry(NodeExecutorRegistry nodeExecutorRegistry) {
            this.nodeExecutorRegistry = nodeExecutorRegistry;
            return this;
        }

        public Builder workflowExecutor(WorkflowExecutor workflowExecutor) {
            this.workflowExecutor = workflowExecutor;
            return this;
        }

        public Builder actionExecutor(ActionExecutor actionExecutor) {
            this.actionExecutor = actionExecutor;
            return this;
        }

        public ExecutionContext build() {
            if (state == null) {
                throw new IllegalStateException("state is required");
            }
            if (workflow == null) {
                throw new IllegalStateException("workflow is required");
            }
            return new ExecutionContext(this);
        }
    }
}
