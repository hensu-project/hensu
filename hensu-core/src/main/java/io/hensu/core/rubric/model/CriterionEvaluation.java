package io.hensu.core.rubric.model;

import java.util.Objects;

/// Immutable criterion evaluation result.
public final class CriterionEvaluation {

    private final String criterionId;
    private final double score;
    private final double weightedScore;
    private final boolean passed;
    private final String feedback;

    private CriterionEvaluation(Builder builder) {
        this.criterionId = Objects.requireNonNull(builder.criterionId);
        this.score = builder.score;
        this.weightedScore = builder.weightedScore;
        this.passed = builder.passed;
        this.feedback = builder.feedback;
    }

    // Getters
    public String getCriterionId() {
        return criterionId;
    }

    public double getScore() {
        return score;
    }

    public double getWeightedScore() {
        return weightedScore;
    }

    public boolean isPassed() {
        return passed;
    }

    public String getFeedback() {
        return feedback;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String criterionId;
        private double score;
        private double weightedScore;
        private boolean passed;
        private String feedback = "";

        private Builder() {}

        public Builder criterionId(String criterionId) {
            this.criterionId = criterionId;
            return this;
        }

        public Builder score(double score) {
            this.score = score;
            return this;
        }

        public Builder weightedScore(double weightedScore) {
            this.weightedScore = weightedScore;
            return this;
        }

        public Builder passed(boolean passed) {
            this.passed = passed;
            return this;
        }

        public Builder feedback(String feedback) {
            this.feedback = feedback;
            return this;
        }

        public CriterionEvaluation build() {
            return new CriterionEvaluation(this);
        }
    }
}
