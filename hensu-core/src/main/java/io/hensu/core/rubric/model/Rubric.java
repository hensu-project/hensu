package io.hensu.core.rubric.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// Immutable rubric definition for quality evaluation.
///
/// A rubric defines the criteria used to evaluate workflow node outputs.
/// Each rubric has a pass threshold and a list of weighted criteria that
/// determine the final score.
///
/// ### Validation Rules
/// - Pass threshold must be between 0 and 100
/// - Must have at least one criterion
///
/// @implNote Immutable and thread-safe after construction. Criteria list
/// is wrapped in an unmodifiable view.
///
/// @see Criterion for individual evaluation criteria
/// @see io.hensu.core.rubric.RubricEngine for evaluation logic
/// @see RubricType for rubric classification
public final class Rubric {

    private final String id;
    private final String name;
    private final String version;
    private final RubricType type;
    private final double passThreshold;
    private final List<Criterion> criteria;

    private Rubric(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Rubric ID required");
        this.name = Objects.requireNonNull(builder.name, "Name required");
        this.version = builder.version;
        this.type = builder.type;
        this.passThreshold = builder.passThreshold;
        this.criteria = Collections.unmodifiableList(builder.criteria);

        validate();
    }

    private void validate() {
        if (passThreshold < 0 || passThreshold > 100) {
            throw new IllegalArgumentException("Pass threshold must be between 0 and 100");
        }
        if (criteria.isEmpty()) {
            throw new IllegalArgumentException("Rubric must have at least one criterion");
        }
    }

    /// Returns the unique rubric identifier.
    ///
    /// @return rubric ID, never null
    public String getId() {
        return id;
    }

    /// Returns the display name.
    ///
    /// @return rubric name, never null
    public String getName() {
        return name;
    }

    /// Returns the semantic version string.
    ///
    /// @return version (default "1.0.0"), may be null
    public String getVersion() {
        return version;
    }

    /// Returns the rubric classification type.
    ///
    /// @return rubric type (default STANDARD), may be null
    public RubricType getType() {
        return type;
    }

    /// Returns the minimum score required to pass.
    ///
    /// @return threshold between 0-100 (default 70.0)
    public double getPassThreshold() {
        return passThreshold;
    }

    /// Returns the evaluation criteria.
    ///
    /// @return unmodifiable list of criteria, never null or empty
    public List<Criterion> getCriteria() {
        return criteria;
    }

    /// Creates a new rubric builder.
    ///
    /// @return new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Builder for constructing immutable Rubric instances.
    ///
    /// Required fields: `id`, `name`, `criteria` (non-empty)
    public static final class Builder {
        private String id;
        private String name;
        private String version = "1.0.0";
        private RubricType type = RubricType.STANDARD;
        private double passThreshold = 70.0;
        private List<Criterion> criteria = List.of();

        private Builder() {}

        /// Sets the rubric identifier (required).
        ///
        /// @param id unique rubric ID, not null
        /// @return this builder for chaining
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /// Sets the display name (required).
        ///
        /// @param name rubric name, not null
        /// @return this builder for chaining
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /// Sets the semantic version.
        ///
        /// @param version semver string (default "1.0.0"), not null
        /// @return this builder for chaining
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /// Sets the rubric type.
        ///
        /// @param type classification (default STANDARD), not null
        /// @return this builder for chaining
        public Builder type(RubricType type) {
            this.type = type;
            return this;
        }

        /// Sets the minimum pass score.
        ///
        /// @param passThreshold score 0-100 (default 70.0)
        /// @return this builder for chaining
        public Builder passThreshold(double passThreshold) {
            this.passThreshold = passThreshold;
            return this;
        }

        /// Sets the evaluation criteria (required, non-empty).
        ///
        /// @param criteria list of criteria, not null or empty
        /// @return this builder for chaining
        public Builder criteria(List<Criterion> criteria) {
            this.criteria = List.copyOf(criteria);
            return this;
        }

        /// Builds the immutable rubric.
        ///
        /// @return new Rubric instance, never null
        /// @throws NullPointerException if id or name is null
        /// @throws IllegalArgumentException if threshold invalid or criteria empty
        public Rubric build() {
            return new Rubric(this);
        }
    }
}
