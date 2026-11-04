package io.hensu.server.health;

import io.hensu.server.execution.RunRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/// Reports the node not ready once it starts draining.
///
/// A draining node has released its executions and answers polls with no work; the load
/// balancer should stop sending it starts and polls. The datasource readiness check that
/// Quarkus contributes covers the other case – a node that cannot reach Postgres.
@Readiness
@ApplicationScoped
public class DrainingReadinessCheck implements HealthCheck {

    private final RunRegistry registry;

    @Inject
    public DrainingReadinessCheck(RunRegistry registry) {
        this.registry = registry;
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("execution-claims")
                .status(!registry.isDraining())
                .withData("runs", registry.size())
                .build();
    }
}
