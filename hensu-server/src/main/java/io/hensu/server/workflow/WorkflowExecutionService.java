package io.hensu.server.workflow;

import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.execution.CompositeExecutionListener;
import io.hensu.server.execution.LoggingExecutionListener;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/// Service for dispatching new workflow executions.
///
/// Accepts an execution request, assigns an execution id, and launches the execution
/// on a dedicated virtual thread. Handles all four {@link ExecutionResult} variants,
/// persists the final snapshot, and publishes SSE events for completion, failure, and
/// pause.
///
/// This service is the **write path** for new executions. Resuming paused executions
/// lives in {@link ExecutionStateService}; read-model queries live in
/// {@link ExecutionQueryService}.
@ApplicationScoped
public class WorkflowExecutionService {

    private static final Logger LOG = Logger.getLogger(WorkflowExecutionService.class);

    @ConfigProperty(name = "hensu.verbose.enabled", defaultValue = "false")
    boolean verboseEnabled;

    private final WorkflowExecutor workflowExecutor;
    private final WorkflowStateRepository stateRepository;
    private final ExecutionEventBroadcaster eventBroadcaster;
    private final WorkflowRegistryService registryService;

    @Inject
    public WorkflowExecutionService(
            WorkflowExecutor workflowExecutor,
            WorkflowStateRepository stateRepository,
            ExecutionEventBroadcaster eventBroadcaster,
            WorkflowRegistryService registryService) {
        this.workflowExecutor =
                Objects.requireNonNull(workflowExecutor, "workflowExecutor must not be null");
        this.stateRepository =
                Objects.requireNonNull(stateRepository, "stateRepository must not be null");
        this.eventBroadcaster =
                Objects.requireNonNull(eventBroadcaster, "eventBroadcaster must not be null");
        this.registryService =
                Objects.requireNonNull(registryService, "registryService must not be null");
    }

    /// Accepts a new workflow execution and dispatches it asynchronously.
    ///
    /// Validates that the workflow exists, then immediately returns an execution ID while
    /// running the workflow on a background virtual thread. Progress and outcome are
    /// delivered via SSE events.
    ///
    /// @param tenantId the tenant requesting execution, not null
    /// @param workflowId the workflow to execute, not null
    /// @param context initial context variables, not null
    /// @return execution result containing the assigned execution ID, never null
    /// @throws WorkflowNotFoundException if the workflow does not exist
    public ExecutionStartResult startExecution(
            String tenantId, String workflowId, Map<String, Object> context) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(context, "context must not be null");

        LOG.infov("Accepting workflow execution: workflow={0}, tenant={1}", workflowId, tenantId);

        String executionId = UUID.randomUUID().toString();

        TenantInfo tenant;
        if (TenantContext.isBound() && TenantContext.current().tenantId().equals(tenantId)) {
            tenant = TenantContext.current();
        } else {
            tenant = TenantInfo.withMcp(tenantId, "sse://" + tenantId);
        }

        // Fail fast before accepting the request if the workflow is not registered.
        registryService.getWorkflow(tenantId, workflowId);

        Map<String, Object> executionContext = new HashMap<>(context);
        executionContext.put("_tenant_id", tenantId);
        executionContext.put("_execution_id", executionId);

        eventBroadcaster.publish(
                executionId,
                ExecutionEvent.ExecutionStarted.now(executionId, workflowId, tenantId));

        Thread.ofVirtual()
                .name("wf-exec-" + executionId)
                .start(
                        () ->
                                runExecutionAsync(
                                        executionId,
                                        workflowId,
                                        tenantId,
                                        tenant,
                                        executionContext));

        LOG.infov("Workflow execution accepted: executionId={0}", executionId);
        return new ExecutionStartResult(executionId, workflowId);
    }

    private void runExecutionAsync(
            String executionId,
            String workflowId,
            String tenantId,
            TenantInfo tenant,
            Map<String, Object> executionContext) {
        AtomicReference<HensuState> lastCheckpoint = new AtomicReference<>();
        try {
            eventBroadcaster.runAs(
                    executionId,
                    () -> {
                        TenantContext.runAs(
                                tenant,
                                () -> {
                                    Workflow workflow =
                                            registryService.getWorkflow(tenantId, workflowId);

                                    ExecutionListener checkpoint =
                                            trackingCheckpointListener(tenantId, lastCheckpoint);
                                    ExecutionListener listener =
                                            verboseEnabled
                                                    ? new CompositeExecutionListener(
                                                            checkpoint,
                                                            new LoggingExecutionListener())
                                                    : checkpoint;
                                    ExecutionResult result =
                                            workflowExecutor.execute(
                                                    workflow, executionContext, listener);

                                    if (result instanceof ExecutionResult.Completed completed) {
                                        HensuSnapshot snapshot =
                                                HensuSnapshot.from(
                                                        completed.finalState(), "completed");
                                        stateRepository.save(tenantId, snapshot);
                                        eventBroadcaster.publish(
                                                executionId,
                                                ExecutionEvent.ExecutionCompleted.success(
                                                        executionId,
                                                        workflowId,
                                                        completed.finalState().getCurrentNode(),
                                                        publicContext(
                                                                completed
                                                                        .finalState()
                                                                        .getContext())));
                                    } else if (result
                                            instanceof ExecutionResult.Rejected rejected) {
                                        HensuSnapshot snapshot =
                                                HensuSnapshot.from(rejected.state(), "rejected");
                                        stateRepository.save(tenantId, snapshot);
                                        eventBroadcaster.publish(
                                                executionId,
                                                ExecutionEvent.ExecutionCompleted.failure(
                                                        executionId,
                                                        workflowId,
                                                        rejected.state().getCurrentNode(),
                                                        publicContext(
                                                                rejected.state().getContext())));
                                    } else if (result
                                            instanceof ExecutionResult.Paused(HensuState state)) {
                                        HensuSnapshot snapshot =
                                                HensuSnapshot.from(state, "paused");
                                        stateRepository.save(tenantId, snapshot);
                                        eventBroadcaster.publish(
                                                executionId,
                                                ExecutionEvent.ExecutionCompleted.failure(
                                                        executionId,
                                                        workflowId,
                                                        state.getCurrentNode(),
                                                        publicContext(state.getContext())));
                                    } else if (result
                                            instanceof
                                            ExecutionResult.Failure(
                                                    HensuState currentState,
                                                    IllegalStateException e)) {
                                        HensuSnapshot snapshot =
                                                HensuSnapshot.from(currentState, "failed");
                                        stateRepository.save(tenantId, snapshot);
                                        eventBroadcaster.publish(
                                                executionId,
                                                ExecutionEvent.ExecutionError.now(
                                                        executionId,
                                                        "ExecutionFailure",
                                                        e.getMessage(),
                                                        currentState.getCurrentNode()));
                                    }
                                    return null;
                                });
                        return null;
                    });
            LOG.infov("Workflow execution completed: executionId={0}", executionId);
        } catch (Exception e) {
            LOG.errorv(
                    e, "Workflow execution failed: workflow={0}, tenant={1}", workflowId, tenantId);
            eventBroadcaster.publish(
                    executionId,
                    ExecutionEvent.ExecutionError.now(
                            executionId, e.getClass().getSimpleName(), e.getMessage(), null));
            HensuState ls = lastCheckpoint.get();
            if (ls != null) {
                stateRepository.save(tenantId, HensuSnapshot.from(ls, "failed"));
            }
        } finally {
            eventBroadcaster.complete(executionId);
        }
    }

    private ExecutionListener trackingCheckpointListener(
            String tenantId, AtomicReference<HensuState> lastCheckpoint) {
        return new ExecutionListener() {
            @Override
            public void onCheckpoint(HensuState state) {
                lastCheckpoint.set(state);
                stateRepository.save(tenantId, HensuSnapshot.from(state, "checkpoint"));
            }
        };
    }

    private static Map<String, Object> publicContext(Map<String, Object> context) {
        return context.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("_"))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
