package io.hensu.server.integration;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.HashMap;
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
        Map<String, String> config = new HashMap<>();
        config.put("quarkus.datasource.active", "false");
        config.put("quarkus.datasource.devservices.enabled", "false");
        config.put("quarkus.flyway.migrate-at-start", "false");
        config.put("hensu.tenant.default", "test-tenant");

        // Disable JWT verification — tests use @TestSecurity to bypass auth
        config.put("quarkus.smallrye-jwt.enabled", "false");

        return config;
    }
}
