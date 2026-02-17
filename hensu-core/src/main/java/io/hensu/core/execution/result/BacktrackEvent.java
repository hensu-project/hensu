package io.hensu.core.execution.result;

import java.time.Instant;
import java.util.Objects;

/// Records a backtrack event during workflow execution.
///
/// Captures when and why execution jumped back to a previous node, enabling
/// time-travel debugging and workflow behavior analysis.
///
/// ### Contracts
/// - **Precondition**: `from` and `to` node IDs must be provided
/// - **Postcondition**: Immutable after construction
///
/// @see BacktrackType for event classification
/// @see ExecutionHistory for tracking backtracks
public class BacktrackEvent {

    private final String from;
    private final String to;
    private final String reason;
    private final BacktrackType type;
    private final Double rubricScore;
    private final Instant timestamp;

    /// Constructs a backtrack event from builder values.
    ///
    /// @param builder the builder containing event values, not null
    /// @throws NullPointerException if from or to is null
    public BacktrackEvent(Builder builder) {
        this.from = Objects.requireNonNull(builder.from, "From Node required");
        this.to = Objects.requireNonNull(builder.to, "To Node required");
        this.reason = builder.reason;
        this.type = builder.type;
        this.rubricScore = builder.rubricScore;
        this.timestamp = builder.timestamp;
    }

    /// Creates a new builder for constructing BacktrackEvent instances.
    ///
    /// @return a new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Returns the source node ID where backtrack originated.
    ///
    /// @return the source node ID, not null
    public String getFrom() {
        return from;
    }

    /// Returns the target node ID to backtrack to.
    ///
    /// @return the target node ID, not null
    public String getTo() {
        return to;
    }

    /// Returns the reason for backtracking.
    ///
    /// @return explanation of why backtrack occurred, may be null
    public String getReason() {
        return reason;
    }

    /// Returns the type of backtrack event.
    ///
    /// @return the backtrack type, may be null
    public BacktrackType getType() {
        return type;
    }

    /// Returns the rubric score that triggered automatic backtracking.
    ///
    /// @return the score value, may be null if not rubric-triggered
    public Double getRubricScore() {
        return rubricScore;
    }

    /// Returns when the backtrack occurred.
    ///
    /// @return the timestamp, may be null
    public Instant getTimestamp() {
        return timestamp;
    }

    /// Builder for constructing {@link BacktrackEvent} instances.
    public static final class Builder {
        private String from;
        private String to;
        private String reason;
        private BacktrackType type;
        private Double rubricScore;
        private Instant timestamp;

        private Builder() {}

        /// Sets the source node ID.
        ///
        /// @param from the source node ID, not null
        /// @return this builder for chaining
        public Builder from(String from) {
            this.from = from;
            return this;
        }

        /// Sets the target node ID.
        ///
        /// @param to the target node ID, not null
        /// @return this builder for chaining
        public Builder to(String to) {
            this.to = to;
            return this;
        }

        /// Sets the backtrack reason.
        ///
        /// @param reason explanation for the backtrack, may be null
        /// @return this builder for chaining
        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        /// Sets the backtrack type.
        ///
        /// @param type the type of backtrack, may be null
        /// @return this builder for chaining
        public Builder type(BacktrackType type) {
            this.type = type;
            return this;
        }

        /// Sets the rubric score that triggered backtracking.
        ///
        /// @param rubricScore the score value, may be null
        /// @return this builder for chaining
        public Builder rubricScore(Double rubricScore) {
            this.rubricScore = rubricScore;
            return this;
        }

        /// Sets when the backtrack occurred.
        ///
        /// @param timestamp the event timestamp, may be null
        /// @return this builder for chaining
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /// Builds the BacktrackEvent instance.
        ///
        /// @return a new BacktrackEvent, never null
        /// @throws NullPointerException if from or to is null
        public BacktrackEvent build() {
            return new BacktrackEvent(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        BacktrackEvent that = (BacktrackEvent) o;
        return Objects.equals(from, that.from)
                && Objects.equals(to, that.to)
                && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, timestamp);
    }

    @Override
    public String toString() {
        return "BacktrackEvent{"
                + "from='"
                + from
                + '\''
                + ", to='"
                + to
                + '\''
                + ", rubricScore="
                + rubricScore
                + ", timestamp="
                + timestamp
                + ", type="
                + type
                + '}';
    }
}
