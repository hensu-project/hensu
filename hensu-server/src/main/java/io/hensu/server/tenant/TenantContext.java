package io.hensu.server.tenant;

import java.util.Objects;
import java.util.concurrent.Callable;

/// Thread-safe tenant context using ScopedValues for multi-tenant isolation.
///
/// Provides tenant-scoped context propagation without explicit parameter passing.
/// Uses Java 25 ScopedValues for safe, immutable context binding.
///
/// ### Usage
/// {@snippet :
/// TenantInfo tenant = TenantInfo.withMcp("tenant-1", "sse://tenant-1");
/// TenantContext.runAs(tenant, () -> {
///     // All code in this scope has access to tenant context
///     TenantInfo current = TenantContext.current();
///     // Use current.tenantId(), current.mcpEndpoint(), etc.
/// });
/// }
///
/// ### Thread Safety
/// ScopedValues are thread-local but immutable once bound. Each thread
/// has its own copy, and nested runAs() calls create new bindings.
///
/// @see TenantInfo for tenant details
public final class TenantContext {

    private static final ScopedValue<TenantInfo> CURRENT = ScopedValue.newInstance();

    private TenantContext() {
        // Utility class
    }

    /// Returns the current tenant context.
    ///
    /// @return current tenant info, never null
    /// @throws IllegalStateException if no tenant context is bound
    public static TenantInfo current() {
        return CURRENT.orElseThrow(
                () ->
                        new IllegalStateException(
                                "No tenant context bound. Use TenantContext.runAs()"));
    }

    /// Returns the current tenant context if bound.
    ///
    /// @return current tenant info, or null if not bound
    public static TenantInfo currentOrNull() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /// Returns whether a tenant context is currently bound.
    ///
    /// @return true if tenant context is available
    public static boolean isBound() {
        return CURRENT.isBound();
    }

    /// Executes a task with the given tenant context.
    ///
    /// @param tenant the tenant context to bind, not null
    /// @param task the task to execute, not null
    /// @param <T> the return type
    /// @return the task result
    /// @throws Exception if the task throws
    public static <T> T runAs(TenantInfo tenant, Callable<T> task) throws Exception {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(task, "task must not be null");
        return ScopedValue.where(CURRENT, tenant).call(task::call);
    }

    /// Executes a runnable with the given tenant context.
    ///
    /// @param tenant the tenant context to bind, not null
    /// @param task the task to execute, not null
    public static void runAs(TenantInfo tenant, Runnable task) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(task, "task must not be null");
        ScopedValue.where(CURRENT, tenant).run(task);
    }

    /// Tenant identity and the handle of its MCP session.
    ///
    /// The server holds no tenant secrets. Every side effect a workflow asks
    /// for travels outbound over the split pipe to a server the tenant owns and
    /// authenticates for itself, so there is nothing here for a credential to
    /// unlock – see Decision 3 of `docs/unified-architecture.md`.
    ///
    /// @param tenantId unique tenant identifier, not null
    /// @param mcpEndpoint the tenant's MCP session handle in `sse://clientId`
    ///     form, derived from the tenant id; may be null when the tenant uses
    ///     no MCP
    public record TenantInfo(String tenantId, String mcpEndpoint) {

        /// Compact constructor with validation.
        public TenantInfo {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
        }

        /// Creates a tenant info with just an ID (no MCP).
        ///
        /// @param tenantId the tenant identifier, not null
        /// @return new tenant info, never null
        public static TenantInfo simple(String tenantId) {
            return new TenantInfo(tenantId, null);
        }

        /// Creates a tenant info with an MCP session handle.
        ///
        /// @param tenantId the tenant identifier, not null
        /// @param mcpEndpoint the `sse://clientId` session handle, not null
        /// @return new tenant info, never null
        public static TenantInfo withMcp(String tenantId, String mcpEndpoint) {
            return new TenantInfo(tenantId, mcpEndpoint);
        }

        /// Returns whether this tenant has MCP configured.
        ///
        /// @return true if mcpEndpoint is set
        public boolean hasMcp() {
            return mcpEndpoint != null && !mcpEndpoint.isBlank();
        }
    }
}
