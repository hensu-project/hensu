package io.hensu.server.workflow;

import io.hensu.core.execution.CompositeExecutionListener;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.ReplayDivergenceException;
import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.util.LogSanitizer;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.execution.CheckpointingListener;
import io.hensu.server.execution.Claim;
import io.hensu.server.execution.ClaimJournal;
import io.hensu.server.execution.ExecutionStore;
import io.hensu.server.execution.LoggingExecutionListener;
import io.hensu.server.execution.RunContext;
import io.hensu.server.execution.RunHandle;
import io.hensu.server.execution.RunRegistry;
import io.hensu.server.execution.ToolAuditListener;
import io.hensu.server.execution.ToolStreamingExecutionListener;
import io.hensu.server.persistence.ToolAuditRepository;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/// Drives one claimed execution on this node, from whatever state its claim found it in.
///
/// Every run – a new execution, a reviewer's resume, a pickup after another node stopped –
/// goes through here, so all three share one shape: register the claim, bind the run's
/// claim, tenant and event scope, replay and extend the journal through the engine, and
/// record the outcome under the claim. A run that loses its claim stops without writing;
/// any other failure is recorded as the execution's failure.
@ApplicationScoped
public class ExecutionRunner {

    private static final Logger LOG = Logger.getLogger(ExecutionRunner.class);

    /// How a run on this node ended.
    public enum RunOutcome {
        /// The outcome – completed, rejected, paused or failed by the workflow – is recorded.
        SETTLED,
        /// The run stopped because its claim is gone; another run continues the execution.
        PREEMPTED,
        /// The run failed outside the workflow's own handling; recorded as failed.
        FAILED
    }

    @ConfigProperty(name = "hensu.verbose.enabled", defaultValue = "false")
    boolean verboseEnabled;

    private final WorkflowExecutor workflowExecutor;
    private final ExecutionStore store;
    private final RunRegistry registry;
    private final ExecutionEventBroadcaster eventBroadcaster;
    private final ToolAuditRepository toolAuditRepository;

    @Inject
    public ExecutionRunner(
            WorkflowExecutor workflowExecutor,
            ExecutionStore store,
            RunRegistry registry,
            ExecutionEventBroadcaster eventBroadcaster,
            ToolAuditRepository toolAuditRepository) {
        this.workflowExecutor =
                Objects.requireNonNull(workflowExecutor, "workflowExecutor must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.eventBroadcaster =
                Objects.requireNonNull(eventBroadcaster, "eventBroadcaster must not be null");
        this.toolAuditRepository =
                Objects.requireNonNull(toolAuditRepository, "toolAuditRepository must not be null");
    }

    /// Runs a claimed execution on a new virtual thread.
    ///
    /// @param claim the claim just taken, not null
    /// @param state the execution's state as the claim found it, not null
    /// @param workflow the workflow definition, not null
    public void launch(Claim claim, HensuState state, Workflow workflow) {
        RunHandle run;
        try {
            run = registry.register(claim);
        } catch (RunPreemptedException e) {
            LOG.infov("Not running {0}: {1}", claim, e.getMessage());
            return;
        }
        Thread.ofVirtual()
                .name("wf-run-" + claim.executionId())
                .start(() -> runRegistered(run, state, workflow));
    }

    /// Runs a claimed execution on the calling thread until it settles or stops.
    ///
    /// @param claim the claim just taken, not null
    /// @param state the execution's state as the claim found it, not null
    /// @param workflow the workflow definition, not null
    /// @return how the run ended, never null
    public RunOutcome run(Claim claim, HensuState state, Workflow workflow) {
        RunHandle run;
        try {
            run = registry.register(claim);
        } catch (RunPreemptedException e) {
            return RunOutcome.PREEMPTED;
        }
        return runRegistered(run, state, workflow);
    }

    private RunOutcome runRegistered(RunHandle run, HensuState state, Workflow workflow) {
        Claim claim = run.claim();
        String executionId = claim.executionId();
        CheckpointingListener listener =
                new CheckpointingListener(store, run, observers(claim.tenantId(), executionId));
        try {
            ExecutionResult result =
                    eventBroadcaster.runAs(
                            executionId,
                            () ->
                                    TenantContext.runAs(
                                            TenantInfo.simple(claim.tenantId()),
                                            () ->
                                                    RunContext.runAs(
                                                            run,
                                                            () ->
                                                                    workflowExecutor.executeFrom(
                                                                            workflow,
                                                                            state,
                                                                            listener,
                                                                            new ClaimJournal(
                                                                                    store, run)))));
            ExecutionResultHandler.handle(
                    result, claim, workflow.getId(), store, eventBroadcaster, LOG);
            return RunOutcome.SETTLED;
        } catch (RunPreemptedException e) {
            // Another run holds the execution, or the store is out of reach and the claim
            // will expire into one. Recording anything here would be refused, and false if
            // it were not.
            LOG.infov(
                    "Run of {0} stopped on this node: {1}",
                    LogSanitizer.sanitize(claim.toString()), e.getMessage());
            return RunOutcome.PREEMPTED;
        } catch (Exception e) {
            if (e instanceof ReplayDivergenceException) {
                LOG.errorv(e, "Replay of {0} diverged from its journal", claim);
            } else {
                LOG.errorv(e, "Run of {0} failed", claim);
            }
            eventBroadcaster.publish(
                    executionId,
                    ExecutionEvent.ExecutionError.now(
                            executionId, e.getClass().getSimpleName(), e.getMessage(), null));
            HensuState last = listener.lastCheckpoint() != null ? listener.lastCheckpoint() : state;
            ExecutionResultHandler.saveFailed(
                    store, claim, HensuSnapshot.from(last, "failed"), e, LOG);
            return RunOutcome.FAILED;
        } finally {
            registry.unregister(run);
            // A paused execution may wait days for a reviewer; holding its event stream
            // open that long is wasteful. Clients re-subscribe after resuming.
            eventBroadcaster.complete(executionId);
        }
    }

    private ExecutionListener observers(String tenantId, String executionId) {
        // The audit listener is not behind verboseEnabled: the log lines are a debugging
        // aid, the rows are the record.
        ExecutionListener toolStream =
                new ToolStreamingExecutionListener(eventBroadcaster, executionId);
        ExecutionListener audit = new ToolAuditListener(toolAuditRepository, tenantId, executionId);
        return verboseEnabled
                ? new CompositeExecutionListener(toolStream, audit, new LoggingExecutionListener())
                : new CompositeExecutionListener(toolStream, audit);
    }
}
