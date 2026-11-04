package io.hensu.server.workflow;

import io.hensu.server.execution.RunRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/// Renews the claims of the runs live on this node, and stops runs whose claim is gone.
///
/// | Property                      | Default | Description                            |
/// |-------------------------------|---------|----------------------------------------|
/// | `hensu.claim.renew-interval`  | `10s`   | How often live claims are extended     |
/// | `hensu.claim.ttl`             | `30s`   | How long a claim lasts without renewal |
@ApplicationScoped
public class ClaimRenewalJob {

    private final RunRegistry registry;

    @Inject
    public ClaimRenewalJob(RunRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(
            every = "${hensu.claim.renew-interval:10s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        registry.renewAll();
    }
}
