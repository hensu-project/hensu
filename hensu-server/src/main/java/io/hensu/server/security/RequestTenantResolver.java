package io.hensu.server.security;

import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

/// Resolves the tenant ID for the current request.
///
/// Resolution strategy depends on the Quarkus launch mode:
///
/// - **Production** (`LaunchMode.NORMAL`): Tenant is extracted exclusively
///   from the JWT `tenant_id` claim. The Quarkus HTTP auth permission policy
///   rejects unauthenticated requests before this bean is ever called.
/// - **Dev/Test** (`LaunchMode.DEVELOPMENT` or `TEST`): Falls back to
///   `hensu.tenant.default` when no JWT is present (auth policy set to permit).
///
/// ### Defense in Depth
/// The default tenant fallback is guarded by `LaunchMode` — even if
/// `hensu.tenant.default` is accidentally set in production config,
/// the code refuses to use it.
///
/// @see io.hensu.server.tenant.TenantContext for ScopedValue-based propagation
@RequestScoped
public class RequestTenantResolver {

    @Inject JsonWebToken jwt;

    @Inject LaunchMode launchMode;

    @ConfigProperty(name = "hensu.tenant.default", defaultValue = "")
    String defaultTenant;

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

        // 2. Default tenant (dev/test only — LaunchMode guard prevents production use)
        if ((launchMode == LaunchMode.DEVELOPMENT || launchMode == LaunchMode.TEST)
                && defaultTenant != null
                && !defaultTenant.isBlank()) {
            return defaultTenant;
        }

        throw new ForbiddenException("No tenant context available");
    }
}
