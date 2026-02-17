package io.hensu.core.rubric.evaluator;

import io.hensu.core.rubric.model.CriterionEvaluation;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/// Immutable rubric evaluation result.
public final class RubricEvaluation {

    private final String rubricId;
    private final double score;
    private final boolean passed;
    private final List<CriterionEvaluation> criterionEvaluations;
    private final Instant timestamp;

    private RubricEvaluation(Builder builder) {
        this.rubricId = Objects.requireNonNull(builder.rubricId);
        this.score = builder.score;
        this.passed = builder.passed;
        this.criterionEvaluations = Collections.unmodifiableList(builder.criterionEvaluations);
        this.timestamp = builder.timestamp;
    }

    // Getters
    public String getRubricId() {
        return rubricId;
    }

    public double getScore() {
        return score;
    }

    public boolean isPassed() {
        return passed;
    }

    public List<CriterionEvaluation> getCriterionEvaluations() {
        return criterionEvaluations;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    /// Get failed criteria.
    public List<CriterionEvaluation> getFailedCriteria() {
        return criterionEvaluations.stream()
                .filter(eval -> !eval.isPassed())
                .collect(Collectors.toList());
    }

    /// Get suggestions for improvement.
    public List<String> getSuggestions() {
        return getFailedCriteria().stream()
                .map(eval -> "Improve: " + eval.getCriterionId())
                .collect(Collectors.toList());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String rubricId;
        private double score;
        private boolean passed;
        private List<CriterionEvaluation> criterionEvaluations = List.of();
        private Instant timestamp = Instant.now();

        private Builder() {}

        public Builder rubricId(String rubricId) {
            this.rubricId = rubricId;
            return this;
        }

        public Builder score(double score) {
            this.score = score;
            return this;
        }

        public Builder passed(boolean passed) {
            this.passed = passed;
            return this;
        }

        public Builder criterionEvaluations(List<CriterionEvaluation> evaluations) {
            this.criterionEvaluations = List.copyOf(evaluations);
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public RubricEvaluation build() {
            return new RubricEvaluation(this);
        }
    }
}
