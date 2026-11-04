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
/// TenantInfo tenant = TenantInfo.simple("tenant-1");
/// TenantContext.runAs(tenant, () -> {
///     // All code in this scope has access to tenant context
///     TenantInfo current = TenantContext.current();
///     // Use current.tenantId()
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

    /// Tenant identity.
    ///
    /// Only the identity: a tenant's tools are whatever its relay published to the
    /// shared catalog, reached through the store from any node, so nothing about where
    /// the relay is connected belongs here.
    ///
    /// @param tenantId unique tenant identifier, not null
    public record TenantInfo(String tenantId) {

        public TenantInfo {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
        }

        /// Creates a tenant info.
        ///
        /// @param tenantId unique tenant identifier, not null
        /// @return the tenant info, never null
        public static TenantInfo simple(String tenantId) {
            return new TenantInfo(tenantId);
        }
    }
}
