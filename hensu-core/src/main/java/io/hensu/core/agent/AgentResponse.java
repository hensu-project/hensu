package io.hensu.core.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/// Immutable result of an agent execution.
///
/// Captures the outcome of an {@link Agent#execute(String, Map)} call, including
/// success/failure status, output text, metadata, and timing information.
///
/// Use the static factory methods for common cases:
/// - {@link #success(String)} - successful execution with output
/// - {@link #success(String, Map)} - successful execution with output and metadata
/// - {@link #failure(Throwable)} - failed execution with error
///
/// @implNote Thread-safe. All fields are immutable after construction.
/// The metadata map is defensively copied.
///
/// @see Agent#execute(String, Map) for the execution entry point
public final class AgentResponse {

    private final boolean success;
    private final String output;
    private final Map<String, Object> metadata;
    private final Instant timestamp;
    private final Throwable error;

    private AgentResponse(Builder builder) {
        this.success = builder.success;
        this.output = builder.output;
        this.metadata = Collections.unmodifiableMap(builder.metadata);
        this.timestamp = builder.timestamp;
        this.error = builder.error;
    }

    /// Returns whether the agent execution completed successfully.
    ///
    /// @return `true` if successful, `false` if an error occurred
    public boolean isSuccess() {
        return success;
    }

    /// Returns the agent's output text.
    ///
    /// @return output string, may be empty for failures, never null
    public String getOutput() {
        return output;
    }

    /// Returns execution metadata.
    ///
    /// Common metadata keys include:
    /// - `model` - the model used
    /// - `tokens_used` - token count
    /// - `latency_ms` - execution time
    ///
    /// @return unmodifiable metadata map, never null (may be empty)
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /// Returns when the execution completed.
    ///
    /// @return completion timestamp, never null
    public Instant getTimestamp() {
        return timestamp;
    }

    /// Returns the error that caused execution failure.
    ///
    /// @return the exception if {@link #isSuccess()} is `false`, null otherwise
    public Throwable getError() {
        return error;
    }

    /// Creates a successful response with the given output.
    ///
    /// @param output the agent's output text, not null
    /// @return a success response with current timestamp, never null
    public static AgentResponse success(String output) {
        return builder().success(true).output(output).timestamp(Instant.now()).build();
    }

    /// Creates a successful response with output and metadata.
    ///
    /// @param output the agent's output text, not null
    /// @param metadata additional execution metadata, not null
    /// @return a success response with current timestamp, never null
    public static AgentResponse success(String output, Map<String, Object> metadata) {
        return builder()
                .success(true)
                .output(output)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    /// Creates a failure response with the given error.
    ///
    /// @param error the exception that caused failure, not null
    /// @return a failure response with current timestamp, never null
    public static AgentResponse failure(Throwable error) {
        return builder().success(false).error(error).timestamp(Instant.now()).build();
    }

    /// Creates a new builder for constructing AgentResponse instances.
    ///
    /// @return a new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Builder for constructing immutable {@link AgentResponse} instances.
    ///
    /// @implNote Not thread-safe. Create one builder per thread or synchronize externally.
    public static final class Builder {
        private boolean success = true;
        private String output = "";
        private Map<String, Object> metadata = Map.of();
        private Instant timestamp = Instant.now();
        private Throwable error;

        private Builder() {}

        /// Sets the success status.
        ///
        /// @param success `true` for successful execution, `false` for failure
        /// @return this builder for chaining
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        /// Sets the output text.
        ///
        /// @param output the agent's response text, may be empty
        /// @return this builder for chaining
        public Builder output(String output) {
            this.output = output;
            return this;
        }

        /// Sets the execution metadata.
        ///
        /// @param metadata map of metadata key-value pairs, not null
        /// @return this builder for chaining
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = Map.copyOf(metadata);
            return this;
        }

        /// Sets the completion timestamp.
        ///
        /// @param timestamp when execution completed, not null
        /// @return this builder for chaining
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /// Sets the failure error.
        ///
        /// @param error the exception that caused failure, may be null
        /// @return this builder for chaining
        public Builder error(Throwable error) {
            this.error = error;
            return this;
        }

        /// Builds an immutable AgentResponse instance.
        ///
        /// @return the constructed response, never null
        public AgentResponse build() {
            return new AgentResponse(this);
        }
    }
}
