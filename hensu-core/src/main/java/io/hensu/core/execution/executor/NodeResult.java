package io.hensu.core.execution.executor;

import io.hensu.core.execution.result.ResultStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/// Immutable result of node execution containing status, output, and metadata.
///
/// Captures the outcome of executing a workflow node, including success/failure
/// status, the generated output, any errors, and additional metadata for
/// debugging and observability.
///
/// ### Factory Methods
/// Use the static factory methods for common cases:
/// - {@link #success(Object, Map)} for successful execution
/// - {@link #failure(Throwable)} for errors with exception
/// - {@link #failure(String)} for errors with message
/// - {@link #end()} for end node completion
/// - {@link #empty()} for nodes with no output
///
/// @implNote Immutable after construction. Metadata map is wrapped in
/// an unmodifiable view when built via the builder.
///
/// @see ResultStatus for possible status values
/// @see NodeExecutor for execution logic
public final class NodeResult {

    private final ResultStatus status;
    private final Object output;
    private final Map<String, Object> metadata;
    private Throwable error;
    private Instant timestamp;

    public NodeResult(Builder builder) {
        this.status = builder.status;
        this.output = builder.output;
        this.metadata = Collections.unmodifiableMap(builder.metadata);
        this.error = builder.error;
        this.timestamp = builder.timestamp;
    }

    public NodeResult(ResultStatus status, Object output, Map<String, Object> metadata) {
        this.status = status;
        this.output = output;
        this.metadata = metadata;
    }

    /// Returns the execution status.
    ///
    /// @return status indicating success, failure, pending or end, never null
    public ResultStatus getStatus() {
        return status;
    }

    /// Returns the execution output.
    ///
    /// @return output object (typically String for agent responses), may be null
    public Object getOutput() {
        return output;
    }

    /// Returns additional execution metadata.
    ///
    /// @return unmodifiable metadata map, never null
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /// Returns the error that caused failure, if any.
    ///
    /// @return the exception, or null if execution succeeded
    public Throwable getError() {
        return error;
    }

    /// Returns when the result was created.
    ///
    /// @return creation timestamp, may be null for legacy results
    public Instant getTimestamp() {
        return timestamp;
    }

    /// Checks if execution completed successfully.
    ///
    /// @return true if status is SUCCESS, false otherwise
    public boolean isSuccess() {
        return status == ResultStatus.SUCCESS;
    }

    /// Creates a success result with output and metadata.
    ///
    /// @param output the execution output, may be null
    /// @param metadata additional metadata, not null
    /// @return new success result, never null
    public static NodeResult success(Object output, Map<String, Object> metadata) {
        return builder()
                .status(ResultStatus.SUCCESS)
                .output(output)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    /// Creates a failure result from an exception.
    ///
    /// @param error the exception that caused failure, not null
    /// @return new failure result, never null
    public static NodeResult failure(Throwable error) {
        return builder().status(ResultStatus.FAILURE).error(error).timestamp(Instant.now()).build();
    }

    /// Creates a failure result with an error message.
    ///
    /// @param message the error description, not null
    /// @return new failure result, never null
    public static NodeResult failure(String message) {
        return builder()
                .status(ResultStatus.FAILURE)
                .output(message)
                .timestamp(Instant.now())
                .build();
    }

    /// Creates an end result indicating workflow end.
    ///
    /// @return new end result, never null
    public static NodeResult end() {
        return builder().status(ResultStatus.END).timestamp(Instant.now()).build();
    }

    /// Creates an empty success result with no output.
    ///
    /// @return new empty success result, never null
    public static NodeResult empty() {
        return builder().status(ResultStatus.SUCCESS).output("").timestamp(Instant.now()).build();
    }

    /// Creates a new result builder.
    ///
    /// @return new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Builder for constructing NodeResult instances.
    public static final class Builder {
        private ResultStatus status = ResultStatus.SUCCESS;
        private Object output = "";
        private Map<String, Object> metadata = Map.of();
        private Throwable error;
        private Instant timestamp = Instant.now();

        private Builder() {}

        /// Sets the execution status.
        ///
        /// @param status the result status, not null
        /// @return this builder for chaining
        public Builder status(ResultStatus status) {
            this.status = status;
            return this;
        }

        /// Sets the execution output.
        ///
        /// @param output the result output, may be null
        /// @return this builder for chaining
        public Builder output(Object output) {
            this.output = output;
            return this;
        }

        /// Sets additional metadata.
        ///
        /// @param metadata key-value pairs, not null
        /// @return this builder for chaining
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = Collections.unmodifiableMap(new HashMap<>(metadata));
            return this;
        }

        /// Sets the error that caused failure.
        ///
        /// @param error the exception, may be null
        /// @return this builder for chaining
        public Builder error(Throwable error) {
            this.error = error;
            return this;
        }

        /// Sets the result creation timestamp.
        ///
        /// @param timestamp when the result was created, not null
        /// @return this builder for chaining
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /// Builds the immutable NodeResult.
        ///
        /// @return new NodeResult instance, never null
        public NodeResult build() {
            return new NodeResult(this);
        }
    }
}
