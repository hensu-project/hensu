package io.hensu.server.workflow;

import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.execution.Claim;
import io.hensu.server.execution.ExecutionStore;
import io.hensu.server.execution.RunRegistry;
import io.hensu.server.mcp.ClusterSignals;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/// Takes on executions no node is running: released by a draining node, or left behind by
/// one that stopped renewing.
///
/// Runs on a schedule, and at once whenever a node releases an execution (the
/// `hensu_runnable` notification), so a rolling deploy hands work over without anyone
/// waiting out a claim. Each pickup claims at most this node's spare capacity, and the
/// claim increments the epoch: the run that left the execution – if it is merely paused
/// – can no longer write to it.
///
/// The picked-up run continues from the main-flow checkpoint and replays the journal of
/// the attempt it re-enters: recorded model turns are not requested again, settled tool
/// calls are not issued again, and calls still in flight are waited on, not repeated.
///
/// | Property                       | Default | Description                         |
/// |--------------------------------|---------|-------------------------------------|
/// | `hensu.claim.pickup-interval`  | `5s`    | How often unclaimed work is swept   |
/// | `hensu.claim.max-runs`         | `200`   | Runs this node takes on at most     |
@ApplicationScoped
public class ExecutionPickupJob {

    private static final Logger LOG = Logger.getLogger(ExecutionPickupJob.class);

    private final ExecutionStore store;
    private final RunRegistry registry;
    private final ExecutionRunner runner;
    private final WorkflowRegistryService registryService;
    private final ClusterSignals signals;
    private final int maxRuns;
    private final AtomicBoolean sweeping = new AtomicBoolean();

    @Inject
    public ExecutionPickupJob(
            ExecutionStore store,
            RunRegistry registry,
            ExecutionRunner runner,
            WorkflowRegistryService registryService,
            ClusterSignals signals,
            @ConfigProperty(name = "hensu.claim.max-runs", defaultValue = "200") int maxRuns) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.registryService =
                Objects.requireNonNull(registryService, "registryService must not be null");
        this.signals = Objects.requireNonNull(signals, "signals must not be null");
        this.maxRuns = maxRuns;
    }

    void onStart(@Observes StartupEvent event) {
        signals.onRunnable(() -> Thread.ofVirtual().name("pickup-signal").start(this::pickUp));
    }

    @Scheduled(
            every = "${hensu.claim.pickup-interval:5s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        pickUp();
    }

    /// Claims and runs as many unclaimed executions as this node has room for.
    void pickUp() {
        if (registry.isDraining() || !sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Claim> claims = store.pickUp(Math.max(0, maxRuns - registry.size()));
            for (Claim claim : claims) {
                LOG.infov("Picked up {0}", claim);
                start(claim);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Pickup failed: {0}", e.getMessage());
        } finally {
            sweeping.set(false);
        }
    }

    private void start(Claim claim) {
        try {
            HensuSnapshot snapshot =
                    store.findByExecutionId(claim.tenantId(), claim.executionId())
                            .orElseThrow(() -> new IllegalStateException("No state for " + claim));
            Workflow workflow =
                    registryService.getWorkflow(claim.tenantId(), snapshot.workflowId());
            runner.launch(claim, snapshot.toState(), workflow);
        } catch (RuntimeException e) {
            // The claim expires and another pickup tries again; nothing was written.
            LOG.errorv(e, "Could not start picked-up {0}", claim);
        }
    }
}
