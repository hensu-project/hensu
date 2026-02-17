package io.hensu.core.rubric.model;

import java.util.Objects;

/// Immutable criterion definition for rubric evaluation.
///
/// A criterion represents a single evaluation dimension within a rubric.
/// Each criterion has a weight that determines its contribution to the
/// final score and a minimum score threshold for passing.
///
/// ### Validation Rules
/// - Weight must be non-negative
/// - Min score must be between 0 and 100
///
/// @implNote Immutable and thread-safe after construction.
///
/// @see Rubric for rubric structure
/// @see EvaluationType for evaluation method
public final class Criterion {

    private final String id;
    private final String name;
    private final String description;
    private final double weight;
    private final double minScore;
    private final boolean required;
    private final EvaluationType evaluationType;
    private final String evaluationLogic;

    private Criterion(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Criterion ID required");
        this.name = Objects.requireNonNull(builder.name, "Name required");
        this.description = builder.description;
        this.weight = builder.weight;
        this.minScore = builder.minScore;
        this.required = builder.required;
        this.evaluationType = builder.evaluationType;
        this.evaluationLogic = builder.evaluationLogic;

        validate();
    }

    private void validate() {
        if (weight < 0) {
            throw new IllegalArgumentException("Weight cannot be negative");
        }
        if (minScore < 0 || minScore > 100) {
            throw new IllegalArgumentException("Min score must be between 0 and 100");
        }
    }

    /// Returns the unique criterion identifier.
    ///
    /// @return criterion ID, never null
    public String getId() {
        return id;
    }

    /// Returns the display name.
    ///
    /// @return criterion name, never null
    public String getName() {
        return name;
    }

    /// Returns the detailed description.
    ///
    /// @return description text (default ""), never null
    public String getDescription() {
        return description;
    }

    /// Returns the weight for score calculation.
    ///
    /// @return non-negative weight (default 1.0)
    public double getWeight() {
        return weight;
    }

    /// Returns the minimum score to pass this criterion.
    ///
    /// @return threshold between 0-100 (default 0.0)
    public double getMinScore() {
        return minScore;
    }

    /// Checks if this criterion must pass for overall success.
    ///
    /// @return true if criterion failure fails the entire rubric
    public boolean isRequired() {
        return required;
    }

    /// Returns the evaluation method type.
    ///
    /// @return evaluation type (default MANUAL), never null
    public EvaluationType getEvaluationType() {
        return evaluationType;
    }

    /// Returns custom evaluation logic expression.
    ///
    /// @return evaluation logic (default ""), never null
    public String getEvaluationLogic() {
        return evaluationLogic;
    }

    /// Creates a new criterion builder.
    ///
    /// @return new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Builder for constructing immutable Criterion instances.
    ///
    /// Required fields: `id`, `name`
    public static final class Builder {
        private String id;
        private String name;
        private String description = "";
        private double weight = 1.0;
        private double minScore = 0.0;
        private boolean required = false;
        private EvaluationType evaluationType = EvaluationType.MANUAL;
        private String evaluationLogic = "";

        private Builder() {}

        /// Sets the criterion identifier (required).
        ///
        /// @param id unique criterion ID, not null
        /// @return this builder for chaining
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /// Sets the display name (required).
        ///
        /// @param name criterion name, not null
        /// @return this builder for chaining
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /// Sets the detailed description.
        ///
        /// @param description criterion description, not null
        /// @return this builder for chaining
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /// Sets the weight for score calculation.
        ///
        /// @param weight non-negative weight (default 1.0)
        /// @return this builder for chaining
        public Builder weight(double weight) {
            this.weight = weight;
            return this;
        }

        /// Sets the minimum passing score.
        ///
        /// @param minScore threshold 0-100 (default 0.0)
        /// @return this builder for chaining
        public Builder minScore(double minScore) {
            this.minScore = minScore;
            return this;
        }

        /// Sets whether this criterion is mandatory.
        ///
        /// @param required true if criterion must pass
        /// @return this builder for chaining
        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        /// Sets the evaluation method type.
        ///
        /// @param evaluationType how to evaluate (default MANUAL)
        /// @return this builder for chaining
        public Builder evaluationType(EvaluationType evaluationType) {
            this.evaluationType = evaluationType;
            return this;
        }

        /// Sets custom evaluation logic.
        ///
        /// @param evaluationLogic logic expression, not null
        /// @return this builder for chaining
        public Builder evaluationLogic(String evaluationLogic) {
            this.evaluationLogic = evaluationLogic;
            return this;
        }

        /// Builds the immutable criterion.
        ///
        /// @return new Criterion instance, never null
        /// @throws NullPointerException if id or name is null
        /// @throws IllegalArgumentException if weight or minScore invalid
        public Criterion build() {
            return new Criterion(this);
        }
    }
}
