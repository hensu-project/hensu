package io.hensu.server.security;

import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

/// Resolves the tenant ID for the current request.
///
/// Resolution strategy:
///
/// - **Production** (`LaunchMode.NORMAL`): Tenant is extracted exclusively
///   from the JWT `tenant_id` claim. The Quarkus HTTP auth permission policy
///   rejects unauthenticated requests before this bean is ever called.
/// - **Dev/Test** (`LaunchMode.DEVELOPMENT` or `TEST`): Falls back to
///   `hensu.tenant.default` when no JWT is present (auth policy set to permit).
/// - **In-memory profile** (`quarkus.profile=inmem`): Falls back to
///   `hensu.tenant.default` regardless of launch mode — enables zero-config
///   native image start via `QUARKUS_PROFILE=inmem`.
///
/// ### Defense in Depth
/// The default tenant fallback requires a non-production launch mode or the
/// explicit `inmem` profile — production binaries without either are unaffected.
///
/// @see io.hensu.server.tenant.TenantContext for ScopedValue-based propagation
@RequestScoped
public class RequestTenantResolver {

    @Inject JsonWebToken jwt;

    @Inject LaunchMode launchMode;

    @ConfigProperty(name = "hensu.tenant.default")
    Optional<String> defaultTenant;

    @ConfigProperty(name = "quarkus.profile")
    Optional<String> activeProfile;

    /// Returns the tenant ID for the current request.
    ///
    /// @return resolved tenant ID, never null or blank
    /// @throws ForbiddenException if no tenant can be resolved
    public String tenantId() {
        // 1. JWT claim (production — cryptographically bound)
        if (jwt != null && jwt.getSubject() != null) {
            String claim = jwt.getClaim("tenant_id");
            if (claim != null && !claim.isBlank()) {
                return claim;
            }
        }

        // 2. Default tenant (dev/test/inmem — guarded against accidental production use)
        boolean allowDefault =
                launchMode == LaunchMode.DEVELOPMENT
                        || launchMode == LaunchMode.TEST
                        || "inmem".equals(activeProfile.orElse(""));
        if (allowDefault && defaultTenant.isPresent() && !defaultTenant.get().isBlank()) {
            return defaultTenant.get();
        }

        throw new ForbiddenException("No tenant context available");
    }
}
