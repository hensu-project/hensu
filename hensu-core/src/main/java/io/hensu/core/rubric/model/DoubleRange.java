package io.hensu.core.rubric.model;

public record DoubleRange(double start, double end) {

    public DoubleRange {
        if (start > end) {
            throw new IllegalArgumentException("Start must be less than or equal to end");
        }
    }

    public boolean contains(double value) {
        return (start <= value) && (value <= end);
    }
}
