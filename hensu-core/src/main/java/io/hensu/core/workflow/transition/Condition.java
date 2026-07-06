package io.hensu.core.workflow.transition;

import java.math.BigDecimal;

/// Sealed predicate vocabulary for {@link ConditionTransition}.
///
/// One variable, one predicate per rule. Multiple predicates compose as ordered
/// transition arms (first match wins) – there are no compound expressions and no
/// free-form parsing. A conjunction such as `status == "complete" AND score >= 80`
/// is deliberately inexpressible; have the agent emit a combined variable instead.
///
/// ### Coercion contract
/// - {@link Equals}/{@link NotEquals} compare against the value's canonical string
///   form: numbers via their decimal rendering (trailing zeros stripped), booleans
///   as `true`/`false`, strings as-is.
/// - {@link Compare} accepts `Number` values and numeric strings.
/// - Any value that cannot be coerced (absent variable, non-numeric string under a
///   numeric operator, structured JSON object) is a {@link MatchResult#TYPE_MISMATCH},
///   never a bare no-match – the engine surfaces it as a listener warning.
///
/// @see ConditionTransition for evaluation and warning emission
public sealed interface Condition {

    /// Three-valued match outcome distinguishing a clean no-match from an
    /// uncoercible value.
    enum MatchResult {
        /// The value satisfies the predicate.
        MATCH,
        /// The value was coercible but does not satisfy the predicate.
        NO_MATCH,
        /// The value could not be coerced to the predicate's expected form.
        TYPE_MISMATCH
    }

    /// Numeric comparison operators for {@link Compare}.
    enum Op {
        GT,
        GTE,
        LT,
        LTE
    }

    /// Tests the given context value against this predicate.
    ///
    /// @param value the raw context value, may be null (absent variable)
    /// @return the match outcome, never null
    MatchResult test(Object value);

    /// Returns a compact human-readable form of this predicate for warnings and
    /// visualization labels, e.g. `== "complete"` or `>= 0.9`.
    ///
    /// @return predicate description, never null
    String describe();

    /// Canonicalizes a scalar to its comparison string form: numbers via decimal
    /// rendering with trailing zeros stripped, booleans as `true`/`false`, strings
    /// as-is. Returns null for null or structured (non-scalar) values.
    ///
    /// @param value the raw value, may be null
    /// @return canonical string form, or null if the value is not a scalar
    static String canonicalize(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Boolean b -> b.toString();
            case Number n -> new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
            default -> null;
        };
    }

    /// Coerces a scalar to a double for numeric comparison – accepts `Number` and
    /// numeric strings. Returns null for anything else.
    ///
    /// @param value the raw value, may be null
    /// @return the numeric value, or null if not coercible
    static Double toNumber(Object value) {
        return switch (value) {
            case null -> null;
            case Number n -> n.doubleValue();
            case String s -> {
                try {
                    yield Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    /// Equality against the canonical string form of the value.
    ///
    /// @param expected canonical expected form, not null
    record Equals(String expected) implements Condition {
        public Equals {
            if (expected == null) throw new IllegalArgumentException("expected must not be null");
        }

        @Override
        public MatchResult test(Object value) {
            String actual = canonicalize(value);
            if (actual == null) return MatchResult.TYPE_MISMATCH;
            return actual.equals(expected) ? MatchResult.MATCH : MatchResult.NO_MATCH;
        }

        @Override
        public String describe() {
            return "== \"" + expected + "\"";
        }
    }

    /// Inequality against the canonical string form of the value.
    ///
    /// @param expected canonical expected form, not null
    record NotEquals(String expected) implements Condition {
        public NotEquals {
            if (expected == null) throw new IllegalArgumentException("expected must not be null");
        }

        @Override
        public MatchResult test(Object value) {
            String actual = canonicalize(value);
            if (actual == null) return MatchResult.TYPE_MISMATCH;
            return actual.equals(expected) ? MatchResult.NO_MATCH : MatchResult.MATCH;
        }

        @Override
        public String describe() {
            return "!= \"" + expected + "\"";
        }
    }

    /// Numeric comparison against a threshold. Accepts `Number` values and numeric
    /// strings; everything else is a type mismatch.
    ///
    /// @param op comparison operator, not null
    /// @param threshold the threshold to compare against
    record Compare(Op op, double threshold) implements Condition {
        public Compare {
            if (op == null) throw new IllegalArgumentException("op must not be null");
        }

        @Override
        public MatchResult test(Object value) {
            Double actual = toNumber(value);
            if (actual == null) return MatchResult.TYPE_MISMATCH;
            boolean matches =
                    switch (op) {
                        case GT -> actual > threshold;
                        case GTE -> actual >= threshold;
                        case LT -> actual < threshold;
                        case LTE -> actual <= threshold;
                    };
            return matches ? MatchResult.MATCH : MatchResult.NO_MATCH;
        }

        @Override
        public String describe() {
            String symbol =
                    switch (op) {
                        case GT -> ">";
                        case GTE -> ">=";
                        case LT -> "<";
                        case LTE -> "<=";
                    };
            return symbol + " " + canonicalize(threshold);
        }
    }
}
