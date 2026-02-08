package io.hensu.server.tenant;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/// Thread-safe tenant context using ScopedValues for multi-tenant isolation.
///
/// Provides tenant-scoped context propagation without explicit parameter passing.
/// Uses Java 25 ScopedValues for safe, immutable context binding.
///
/// ### Usage
/// {@snippet :
/// TenantInfo tenant = new TenantInfo("tenant-1", "http://mcp.local:8080", Map.of());
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

    /// Tenant information including identity and MCP connection details.
    ///
    /// @param tenantId unique tenant identifier, not null
    /// @param mcpEndpoint MCP server endpoint URL, may be null if not using MCP
    /// @param credentials tenant-specific credentials, not null (may be empty)
    public record TenantInfo(String tenantId, String mcpEndpoint, Map<String, String> credentials) {

        /// Compact constructor with validation.
        public TenantInfo {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            credentials = credentials != null ? Map.copyOf(credentials) : Map.of();
        }

        /// Creates a tenant info with just an ID (no MCP).
        ///
        /// @param tenantId the tenant identifier, not null
        /// @return new tenant info, never null
        public static TenantInfo simple(String tenantId) {
            return new TenantInfo(tenantId, null, Map.of());
        }

        /// Creates a tenant info with MCP endpoint.
        ///
        /// @param tenantId the tenant identifier, not null
        /// @param mcpEndpoint the MCP server endpoint, not null
        /// @return new tenant info, never null
        public static TenantInfo withMcp(String tenantId, String mcpEndpoint) {
            return new TenantInfo(tenantId, mcpEndpoint, Map.of());
        }

        /// Returns whether this tenant has MCP configured.
        ///
        /// @return true if mcpEndpoint is set
        public boolean hasMcp() {
            return mcpEndpoint != null && !mcpEndpoint.isBlank();
        }

        /// Returns a credential value.
        ///
        /// @param key the credential key
        /// @return the credential value, or null if not found
        public String credential(String key) {
            return credentials.get(key);
        }
    }
}
