package io.hensu.server.execution;

import io.hensu.core.execution.RunPreemptedException;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/// The runs live on this node, and the claims they hold.
///
/// ### Renewal
/// {@link #renewAll()} extends every claim in one statement. A claim the store does not
/// return has been superseded, and its run is preempted so it stops at its next step
/// instead of finishing work it can no longer record. Renewal is per claim, never per
/// node: a run that ended renews nothing, so no execution is kept alive by a node that is
/// not running it, and a node id is only a label.
///
/// When renewals keep failing – the store unreachable – for longer than the claim period,
/// another node may already have taken the executions, so every run here is preempted.
/// Its next write would be refused anyway; stopping first saves the model calls in between.
///
/// ### Drain
/// On shutdown every claim is released at once – the execution goes back to `RUNNABLE`
/// and another node picks it up without waiting out the claim – and every run is
/// preempted. A run caught mid-call loses nothing: its intent stays in the store and the
/// next claimant waits for that intent's outcome instead of issuing the call again.
///
/// @implNote Thread-safe.
@ApplicationScoped
public class RunRegistry {

    private static final Logger LOG = Logger.getLogger(RunRegistry.class);

    private final Map<Claim, RunHandle> runs = new ConcurrentHashMap<>();
    private final ExecutionStore store;
    private final Duration ttl;
    private volatile long lastRenewalNanos = System.nanoTime();
    private volatile boolean draining;

    @Inject
    public RunRegistry(
            ExecutionStore store,
            @ConfigProperty(name = "hensu.claim.ttl", defaultValue = "30s") Duration ttl) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    }

    /// Registers a run for a claim just taken.
    ///
    /// @param claim the claim, not null
    /// @return the run's handle, never null
    /// @throws RunPreemptedException if this node is draining
    public RunHandle register(Claim claim) {
        RunHandle run = new RunHandle(claim);
        if (draining) {
            release(run);
            throw new RunPreemptedException("Node is draining; " + claim + " handed back");
        }
        runs.put(claim, run);
        return run;
    }

    /// Forgets a run that ended.
    ///
    /// @param run the run, not null
    public void unregister(RunHandle run) {
        runs.remove(run.claim(), run);
    }

    /// @return the number of runs live on this node
    public int size() {
        return runs.size();
    }

    /// @return whether this node is shutting down and takes on no new runs
    public boolean isDraining() {
        return draining;
    }

    /// Renews every claim a run here holds and preempts the runs whose claim is gone.
    public void renewAll() {
        List<Claim> held = List.copyOf(runs.keySet());
        if (held.isEmpty()) {
            lastRenewalNanos = System.nanoTime();
            return;
        }
        Set<Claim> renewed;
        try {
            renewed = store.renew(held);
        } catch (RuntimeException e) {
            long silentNanos = System.nanoTime() - lastRenewalNanos;
            LOG.warnv("Claim renewal failed: {0}", e.getMessage());
            if (silentNanos > ttl.toNanos()) {
                runs.values()
                        .forEach(
                                run ->
                                        run.preempt(
                                                "claims unconfirmed for longer than "
                                                        + ttl.toSeconds()
                                                        + " s"));
            }
            return;
        }
        lastRenewalNanos = System.nanoTime();
        for (Claim claim : held) {
            if (!renewed.contains(claim)) {
                RunHandle run = runs.get(claim);
                if (run != null) {
                    LOG.infov("Claim {0} superseded; stopping its run", claim);
                    run.preempt("claim superseded");
                }
            }
        }
    }

    /// Hands every running execution back to the cluster before the node stops.
    ///
    /// Observes the shutdown event ahead of the datasource's own shutdown, so the releases
    /// still reach the store.
    void drain(@Observes @Priority(1) ShutdownEvent event) {
        draining = true;
        List<RunHandle> live = List.copyOf(runs.values());
        if (!live.isEmpty()) {
            LOG.infov("Draining {0} run(s) to the cluster", live.size());
        }
        live.forEach(this::release);
    }

    private void release(RunHandle run) {
        run.preempt("node draining");
        try {
            store.release(run.claim());
        } catch (RuntimeException e) {
            // Superseded already, or the store is unreachable: the claim expires instead.
            LOG.debugv("Release of {0} not recorded: {1}", run.claim(), e.getMessage());
        }
    }
}
