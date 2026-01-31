package io.hensu.core;

/// Configuration options for the Hensu workflow execution environment.
///
/// Controls threading behavior, pool sizing, and storage backend selection.
/// Use the {@link Builder} for fluent configuration or construct directly
/// with setters for mutable configuration.
///
/// ### Default Values
/// - `useVirtualThreads`: `true` (Java 21+ virtual threads)
/// - `threadPoolSize`: `10` (only used when virtual threads disabled)
/// - `rubricStorageType`: `"memory"` (in-memory storage)
///
/// @implNote **Not thread-safe**. This is a mutable configuration object
/// intended to be configured before passing to {@link HensuFactory}.
/// Do not modify after environment creation.
///
/// @see HensuFactory#createEnvironment(HensuConfig)
/// @see Builder
public class HensuConfig {
    private boolean useVirtualThreads = true;
    private int threadPoolSize = 10;
    private String rubricStorageType = "memory";

    /// Creates a configuration with default values.
    public HensuConfig() {}

    /// Returns whether Java 21+ virtual threads are enabled for parallel execution.
    ///
    /// @return `true` if virtual threads are used, `false` for platform thread pool
    public boolean isUseVirtualThreads() {
        return useVirtualThreads;
    }

    /// Enables or disables Java 21+ virtual threads for parallel execution.
    ///
    /// When enabled, creates a virtual thread per task executor. When disabled,
    /// uses a fixed thread pool with size specified by {@link #getThreadPoolSize()}.
    ///
    /// @param useVirtualThreads `true` to use virtual threads, `false` for platform threads
    public void setUseVirtualThreads(boolean useVirtualThreads) {
        this.useVirtualThreads = useVirtualThreads;
    }

    /// Returns the thread pool size for platform thread execution.
    ///
    /// @return the fixed thread pool size, only relevant when virtual threads are disabled
    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    /// Sets the thread pool size for platform thread execution.
    ///
    /// ### Contracts
    /// - **Precondition**: `threadPoolSize` should be positive
    ///
    /// @param threadPoolSize the number of threads in the fixed pool, must be positive
    public void setThreadPoolSize(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
    }

    /// Returns the storage backend type for rubric definitions.
    ///
    /// @return the storage type identifier, never null. One of: `"memory"`, `"file"`, `"database"`
    public String getRubricStorageType() {
        return rubricStorageType;
    }

    /// Sets the storage backend type for rubric definitions.
    ///
    /// @param rubricStorageType the storage type identifier, not null.
    ///        Supported values: `"memory"`, `"file"`, `"database"`
    public void setRubricStorageType(String rubricStorageType) {
        this.rubricStorageType = rubricStorageType;
    }

    /// Creates a new builder for fluent configuration construction.
    ///
    /// @return a new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Fluent builder for constructing {@link HensuConfig} instances.
    ///
    /// @implNote The builder mutates a single config instance and returns
    /// it on {@link #build()}. The returned config can still be modified
    /// via setters after building.
    public static class Builder {
        private final HensuConfig config = new HensuConfig();

        /// Enables or disables Java 21+ virtual threads.
        ///
        /// @param useVirtualThreads `true` to use virtual threads, `false` for platform threads
        /// @return this builder for chaining, never null
        public Builder useVirtualThreads(boolean useVirtualThreads) {
            config.useVirtualThreads = useVirtualThreads;
            return this;
        }

        /// Sets the thread pool size for platform thread execution.
        ///
        /// @param threadPoolSize the number of threads, must be positive
        /// @return this builder for chaining, never null
        public Builder threadPoolSize(int threadPoolSize) {
            config.threadPoolSize = threadPoolSize;
            return this;
        }

        /// Sets the storage backend type for rubric definitions.
        ///
        /// @param rubricStorageType storage type: `"memory"`, `"file"`, or `"database"`, not null
        /// @return this builder for chaining, never null
        public Builder rubricStorageType(String rubricStorageType) {
            config.rubricStorageType = rubricStorageType;
            return this;
        }

        /// Builds and returns the configured {@link HensuConfig} instance.
        ///
        /// @return the configured instance, never null
        public HensuConfig build() {
            return config;
        }
    }
}
