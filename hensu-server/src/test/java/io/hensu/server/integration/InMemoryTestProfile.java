package io.hensu.server.integration;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/// Quarkus test profile that activates the `inmem` configuration profile.
///
/// Disables PostgreSQL, Flyway, and JWT authentication so behavior tests
/// run with in-memory repositories and require no Docker, Testcontainers,
/// or valid JWT tokens.
///
/// The default tenant (`test-tenant`) is set so that
/// {@link io.hensu.server.security.RequestTenantResolver} can resolve a
/// tenant without JWT in test mode (`LaunchMode.TEST`).
///
/// @see IntegrationTestBase
public class InMemoryTestProfile implements QuarkusTestProfile {

    @Override
    public String getConfigProfile() {
        return "inmem";
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.datasource.active", "false",
                "quarkus.datasource.devservices.enabled", "false",
                "quarkus.flyway.migrate-at-start", "false",
                "hensu.tenant.default", "test-tenant");
    }
}
