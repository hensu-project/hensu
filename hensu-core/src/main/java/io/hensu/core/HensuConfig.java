package io.hensu.core;

/// Configuration options for the Hensu workflow execution environment.
///
/// Controls storage backend selection and other environment settings.
/// Use the {@link Builder} for fluent configuration or construct directly
/// with setters for mutable configuration.
///
/// ### Default Values
/// - `rubricStorageType`: `"memory"` (in-memory storage)
///
/// @implNote **Not thread-safe**. This is a mutable configuration object
/// intended to be configured before passing to {@link HensuFactory}.
/// Do not modify after environment creation.
///
/// @see HensuFactory#createEnvironment(HensuConfig)
/// @see Builder
public class HensuConfig {
    private String rubricStorageType = "memory";

    /// Creates a configuration with default values.
    public HensuConfig() {}

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
