package io.hensu.server.workflow;

import io.hensu.core.execution.CompositeExecutionListener;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.execution.CheckpointingListener;
import io.hensu.server.execution.LoggingExecutionListener;
import io.hensu.server.execution.ToolAuditListener;
import io.hensu.server.execution.ToolStreamingExecutionListener;
import io.hensu.server.mcp.McpConnections;
import io.hensu.server.persistence.ExecutionLeaseManager;
import io.hensu.server.persistence.LeaseLostException;
import io.hensu.server.persistence.ToolAuditRepository;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
    private final ToolAuditRepository toolAuditRepository;
    private final ExecutionLeaseManager leaseManager;

    @Inject
    public WorkflowExecutionService(
            WorkflowExecutor workflowExecutor,
            WorkflowStateRepository stateRepository,
            ExecutionEventBroadcaster eventBroadcaster,
            WorkflowRegistryService registryService,
            ToolAuditRepository toolAuditRepository,
            ExecutionLeaseManager leaseManager) {
        this.workflowExecutor =
                Objects.requireNonNull(workflowExecutor, "workflowExecutor must not be null");
        this.stateRepository =
                Objects.requireNonNull(stateRepository, "stateRepository must not be null");
        this.eventBroadcaster =
                Objects.requireNonNull(eventBroadcaster, "eventBroadcaster must not be null");
        this.registryService =
                Objects.requireNonNull(registryService, "registryService must not be null");
        this.toolAuditRepository =
                Objects.requireNonNull(toolAuditRepository, "toolAuditRepository must not be null");
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager must not be null");
    }

    /// Accepts a new workflow execution and dispatches it asynchronously.
    ///
    /// Validates that the workflow exists, then immediately returns an execution ID while
    /// running the workflow on a background virtual thread. Progress and outcome are
    /// delivered via SSE events.
    ///
    /// Refuses the execution when the tenant's MCP session is held by another
    /// node: its first tool call would fail here, and the lease an execution takes
    /// at its first checkpoint must sit on the node holding the session. This is
    /// what makes tenant affinity at the load balancer a deployment requirement.
    ///
    /// @apiNote Issues a blocking JDBC read for the session owner. Call from a
    /// worker thread or Virtual Thread, never from the event loop.
    ///
    /// @param tenantId the tenant requesting execution, not null
    /// @param workflowId the workflow to execute, not null
    /// @param context initial context variables, not null
    /// @return execution result containing the assigned execution ID, never null
    /// @throws WorkflowNotFoundException if the workflow does not exist
    /// @throws ExecutionNotLocalException if the tenant's MCP session is held by another node
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
            tenant = TenantInfo.withMcp(tenantId, McpConnections.endpointFor(tenantId));
        }

        // Fail fast before accepting the request if the workflow is not registered.
        registryService.getWorkflow(tenantId, workflowId);

        leaseManager
                .sessionElsewhere(tenantId)
                .ifPresent(
                        node -> {
                            throw new ExecutionNotLocalException(
                                    ExecutionLeaseManager.describeSessionElsewhere(tenantId, node)
                                            + "; executions must start there");
                        });

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
        // The audit listener is not behind verboseEnabled: the log lines are a
        // debugging aid, the rows are the record.
        ExecutionListener toolStream =
                new ToolStreamingExecutionListener(eventBroadcaster, executionId);
        ExecutionListener audit = new ToolAuditListener(toolAuditRepository, tenantId, executionId);
        CheckpointingListener listener =
                new CheckpointingListener(
                        stateRepository,
                        tenantId,
                        verboseEnabled
                                ? new CompositeExecutionListener(
                                        toolStream, audit, new LoggingExecutionListener())
                                : new CompositeExecutionListener(toolStream, audit));
        leaseManager.beginRun(tenantId, executionId);
        try {
            eventBroadcaster.runAs(
                    executionId,
                    () -> {
                        TenantContext.runAs(
                                tenant,
                                () -> {
                                    Workflow workflow =
                                            registryService.getWorkflow(tenantId, workflowId);

                                    ExecutionResult result =
                                            workflowExecutor.execute(
                                                    workflow, executionContext, listener);
                                    ExecutionResultHandler.handle(
                                            result,
                                            tenantId,
                                            executionId,
                                            workflowId,
                                            stateRepository,
                                            eventBroadcaster,
                                            LOG,
                                            "execute");
                                    return null;
                                });
                        return null;
                    });
        } catch (Exception e) {
            if (LeaseLostException.isCause(e)) {
                // Another node claimed this execution and is running it; recording a
                // failure here would be refused, and would be false if it were not.
                LOG.warnv("Execution {0} stopped on this node: {1}", executionId, e.getMessage());
                return;
            }
            LOG.errorv(
                    e, "Workflow execution failed: workflow={0}, tenant={1}", workflowId, tenantId);
            eventBroadcaster.publish(
                    executionId,
                    ExecutionEvent.ExecutionError.now(
                            executionId, e.getClass().getSimpleName(), e.getMessage(), null));
            HensuState ls = listener.lastCheckpoint();
            HensuSnapshot failed =
                    ls != null
                            ? HensuSnapshot.from(ls, "failed")
                            : new HensuSnapshot(
                                    workflowId,
                                    executionId,
                                    null,
                                    executionContext,
                                    Map.of(),
                                    null,
                                    null,
                                    Instant.now(),
                                    "failed");
            ExecutionResultHandler.saveFailed(stateRepository, tenantId, failed, e, LOG);
        } finally {
            leaseManager.endRun(tenantId, executionId);
            // Always close the SSE stream. Paused executions may wait days/weeks
            // for human review — holding an HTTP connection open is wasteful and
            // will be killed by idle timeouts long before then. Clients must
            // re-subscribe after submitting a review to receive post-resume events.
            eventBroadcaster.complete(executionId);
        }
    }
}
