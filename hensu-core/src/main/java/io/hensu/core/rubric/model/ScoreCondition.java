package io.hensu.core.rubric.model;

import java.util.Objects;

public record ScoreCondition(
        ComparisonOperator operator, Double value, DoubleRange range, String targetNode) {

    public Boolean matches(Double score) {
        return switch (operator) {
            case ComparisonOperator.GT -> score > value;
            case ComparisonOperator.GTE -> score >= value;
            case ComparisonOperator.LT -> score < value;
            case ComparisonOperator.LTE -> score <= value;
            case ComparisonOperator.EQ -> Objects.equals(score, value);
            case ComparisonOperator.RANGE -> range != null && range.contains(score);
        };
    }

    public ComparisonOperator getOperator() {
        return operator;
    }

    public Double getValue() {
        return value;
    }

    public String getTargetNode() {
        return targetNode;
    }
}
