package io.hensu.core.workflow.transition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LoopConditionTest {

    @Nested
    class AlwaysConditionTest {

        @Test
        void shouldProvidesSingletonInstance() {
            // When
            LoopCondition.Always instance1 = LoopCondition.Always.INSTANCE;
            LoopCondition.Always instance2 = LoopCondition.Always.INSTANCE;

            // Then
            assertThat(instance1).isSameAs(instance2);
        }

        @Test
        void shouldBeInstanceOfLoopCondition() {
            // When
            LoopCondition condition = LoopCondition.Always.INSTANCE;

            // Then
            assertThat(condition).isInstanceOf(LoopCondition.class);
            assertThat(condition).isInstanceOf(LoopCondition.Always.class);
        }
    }

    @Nested
    class ExpressionConditionTest {

        @Test
        void shouldCreateWithExpression() {
            // When
            LoopCondition.Expression condition = new LoopCondition.Expression("count < 5");

            // Then
            assertThat(condition.getExpr()).isEqualTo("count < 5");
        }

        @Test
        void shouldBeInstanceOfLoopCondition() {
            // When
            LoopCondition condition = new LoopCondition.Expression("true");

            // Then
            assertThat(condition).isInstanceOf(LoopCondition.class);
            assertThat(condition).isInstanceOf(LoopCondition.Expression.class);
        }

        @Test
        void shouldStoreComplexExpressions() {
            // When
            LoopCondition.Expression condition =
                    new LoopCondition.Expression("score >= threshold && attempts < maxAttempts");

            // Then
            assertThat(condition.getExpr())
                    .isEqualTo("score >= threshold && attempts < maxAttempts");
        }

        @Test
        void shouldAllowNullExpression() {
            // When
            LoopCondition.Expression condition = new LoopCondition.Expression(null);

            // Then
            assertThat(condition.getExpr()).isNull();
        }

        @Test
        void shouldAllowEmptyExpression() {
            // When
            LoopCondition.Expression condition = new LoopCondition.Expression("");

            // Then
            assertThat(condition.getExpr()).isEmpty();
        }
    }

    @Test
    void shouldDistinguishBetweenConditionTypes() {
        // Given
        LoopCondition always = LoopCondition.Always.INSTANCE;
        LoopCondition expression = new LoopCondition.Expression("x > 0");

        // Then
        assertThat(always).isNotEqualTo(expression);
        assertThat(always.getClass()).isNotEqualTo(expression.getClass());
    }
}
