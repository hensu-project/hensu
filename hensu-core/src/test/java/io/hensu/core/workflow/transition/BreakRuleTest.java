package io.hensu.core.workflow.transition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BreakRuleTest {

    @Test
    void shouldCreateBreakRuleWithConditionAndTarget() {
        // Given
        LoopCondition condition = LoopCondition.Always.INSTANCE;

        // When
        BreakRule rule = new BreakRule(condition, "exit-node");

        // Then
        assertThat(rule.condition()).isEqualTo(condition);
        assertThat(rule.getCondition()).isEqualTo(condition);
        assertThat(rule.targetNode()).isEqualTo("exit-node");
        assertThat(rule.getTargetNode()).isEqualTo("exit-node");
    }

    @Test
    void shouldCreateBreakRuleWithExpressionCondition() {
        // Given
        LoopCondition.Expression condition = new LoopCondition.Expression("count > 10");

        // When
        BreakRule rule = new BreakRule(condition, "done");

        // Then
        assertThat(rule.condition()).isInstanceOf(LoopCondition.Expression.class);
        assertThat(((LoopCondition.Expression) rule.condition()).getExpr()).isEqualTo("count > 10");
    }

    @Test
    void shouldAllowNullValues() {
        // When
        BreakRule rule = new BreakRule(null, null);

        // Then
        assertThat(rule.condition()).isNull();
        assertThat(rule.targetNode()).isNull();
    }

    @Test
    void shouldImplementEquality() {
        // Given
        BreakRule rule1 = new BreakRule(LoopCondition.Always.INSTANCE, "exit");
        BreakRule rule2 = new BreakRule(LoopCondition.Always.INSTANCE, "exit");
        BreakRule rule3 = new BreakRule(LoopCondition.Always.INSTANCE, "other");

        // Then
        assertThat(rule1).isEqualTo(rule2);
        assertThat(rule1).isNotEqualTo(rule3);
        assertThat(rule1.hashCode()).isEqualTo(rule2.hashCode());
    }

    @Test
    void shouldReturnMeaningfulToString() {
        // Given
        BreakRule rule = new BreakRule(LoopCondition.Always.INSTANCE, "exit-node");

        // When
        String toString = rule.toString();

        // Then
        assertThat(toString).contains("exit-node");
    }
}
