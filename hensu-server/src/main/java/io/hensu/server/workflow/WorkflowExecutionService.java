package io.hensu.server.workflow;

import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.util.LogSanitizer;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.execution.CanonicalDigest;
import io.hensu.server.execution.Claim;
import io.hensu.server.execution.ExecutionStore;
import io.hensu.server.execution.ExecutionStore.StartKey;
import io.hensu.server.execution.ExecutionStore.StartOutcome;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jboss.logging.Logger;

/// Starts new workflow executions.
///
/// Any node accepts a start: the execution is created durably, under its first claim,
/// before the request is answered, and then runs on this node. Nothing about the tenant –
/// where its relay is, which node last served it – decides where an execution may start;
/// tool calls reach the relay through the store from wherever the run is.
///
/// This service is the write path for new executions. Resuming paused executions lives in
/// {@link ExecutionStateService}; picking up released or orphaned ones in
/// {@link ExecutionPickupJob}; read-model queries in {@link ExecutionQueryService}.
@ApplicationScoped
public class WorkflowExecutionService {

    private static final Logger LOG = Logger.getLogger(WorkflowExecutionService.class);

    private final ExecutionStore store;
    private final ExecutionRunner runner;
    private final ExecutionEventBroadcaster eventBroadcaster;
    private final WorkflowRegistryService registryService;

    @Inject
    public WorkflowExecutionService(
            ExecutionStore store,
            ExecutionRunner runner,
            ExecutionEventBroadcaster eventBroadcaster,
            WorkflowRegistryService registryService) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.eventBroadcaster =
                Objects.requireNonNull(eventBroadcaster, "eventBroadcaster must not be null");
        this.registryService =
                Objects.requireNonNull(registryService, "registryService must not be null");
    }

    /// Creates an execution and runs it on this node.
    ///
    /// The execution row and its first claim are written before this returns, so an
    /// accepted execution survives this node dying a moment later: its claim expires and
    /// another node picks it up.
    ///
    /// A client that may retry a start – because it lost the answer to a timeout, a
    /// reset connection or a node that died after accepting – sends an idempotency key.
    /// The first start with the key creates the execution; every later one with the same
    /// key and the same request returns that execution and starts nothing, on whichever
    /// node it lands.
    ///
    /// @apiNote Issues a blocking JDBC write. Call from a worker thread or Virtual Thread,
    /// never from the event loop.
    ///
    /// @param tenantId the tenant requesting execution, not null
    /// @param workflowId the workflow to execute, not null
    /// @param context initial context variables, not null
    /// @param idempotencyKey the client's key for this start, or null
    /// @return the execution ID – new, or the one the key already names – never null
    /// @throws WorkflowNotFoundException if the workflow does not exist
    /// @throws IdempotencyKeyReusedException if the key belongs to a different request
    public ExecutionStartResult startExecution(
            String tenantId,
            String workflowId,
            Map<String, Object> context,
            String idempotencyKey) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(context, "context must not be null");

        LOG.infov("Accepting workflow execution: workflow={0}, tenant={1}", workflowId, tenantId);

        Workflow workflow = registryService.getWorkflow(tenantId, workflowId);
        String executionId = UUID.randomUUID().toString();

        Map<String, Object> executionContext = new HashMap<>(context);
        executionContext.put("_tenant_id", tenantId);
        executionContext.put("_execution_id", executionId);

        HensuState initial =
                new HensuState.Builder()
                        .executionId(executionId)
                        .workflowId(workflowId)
                        .currentNode(workflow.getStartNode())
                        .context(executionContext)
                        .history(new ExecutionHistory())
                        .build();
        StartKey key =
                idempotencyKey == null
                        ? null
                        : new StartKey(
                                idempotencyKey,
                                CanonicalDigest.of(
                                        Map.of("workflowId", workflowId, "context", context)));

        return switch (store.start(tenantId, HensuSnapshot.from(initial, "checkpoint"), key)) {
            case StartOutcome.Started(Claim claim) -> {
                eventBroadcaster.publish(
                        executionId,
                        ExecutionEvent.ExecutionStarted.now(executionId, workflowId, tenantId));
                runner.launch(claim, initial, workflow);
                LOG.infov("Workflow execution accepted: executionId={0}", executionId);
                yield new ExecutionStartResult(executionId, workflowId);
            }
            case StartOutcome.Existing(String existing, boolean sameRequest) when sameRequest -> {
                LOG.infov("Start repeated under its idempotency key: executionId={0}", existing);
                yield new ExecutionStartResult(existing, workflowId);
            }
            case StartOutcome.Existing(String existing, _) ->
                    throw new IdempotencyKeyReusedException(
                            "Idempotency key "
                                    + LogSanitizer.sanitize(idempotencyKey)
                                    + " already started execution "
                                    + existing
                                    + " with a different request");
        };
    }
}
