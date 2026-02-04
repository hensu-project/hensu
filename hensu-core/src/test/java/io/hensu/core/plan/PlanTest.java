package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.plan.Plan.PlanSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlanTest {

    @Nested
    class ConstructorValidation {

        @Test
        void shouldThrowWhenIdIsNull() {
            assertThatThrownBy(
                            () ->
                                    new Plan(
                                            null,
                                            "node",
                                            PlanSource.STATIC,
                                            List.of(),
                                            PlanConstraints.defaults()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("id");
        }

        @Test
        void shouldThrowWhenNodeIdIsNull() {
            assertThatThrownBy(
                            () ->
                                    new Plan(
                                            "plan-1",
                                            null,
                                            PlanSource.STATIC,
                                            List.of(),
                                            PlanConstraints.defaults()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("nodeId");
        }

        @Test
        void shouldDefaultSourceToStatic() {
            Plan plan = new Plan("plan-1", "node-1", null, List.of(), PlanConstraints.defaults());

            assertThat(plan.source()).isEqualTo(PlanSource.STATIC);
        }

        @Test
        void shouldDefaultStepsToEmptyList() {
            Plan plan =
                    new Plan(
                            "plan-1",
                            "node-1",
                            PlanSource.STATIC,
                            null,
                            PlanConstraints.defaults());

            assertThat(plan.steps()).isNotNull().isEmpty();
        }

        @Test
        void shouldDefaultConstraintsToDefaults() {
            Plan plan = new Plan("plan-1", "node-1", PlanSource.STATIC, List.of(), null);

            assertThat(plan.constraints()).isNotNull();
            assertThat(plan.constraints().maxSteps()).isEqualTo(10);
        }
    }

    @Nested
    class StaticFactoryMethods {

        @Test
        void shouldCreateStaticPlan() {
            List<PlannedStep> steps =
                    List.of(
                            PlannedStep.simple(0, "tool1", "Step 1"),
                            PlannedStep.simple(1, "tool2", "Step 2"));

            Plan plan = Plan.staticPlan("node-1", steps);

            assertThat(plan.id()).isNotNull().isNotBlank();
            assertThat(plan.nodeId()).isEqualTo("node-1");
            assertThat(plan.source()).isEqualTo(PlanSource.STATIC);
            assertThat(plan.steps()).hasSize(2);
            assertThat(plan.isStatic()).isTrue();
        }

        @Test
        void shouldCreateDynamicPlan() {
            List<PlannedStep> steps = List.of(PlannedStep.simple(0, "tool", "Step"));

            Plan plan = Plan.dynamicPlan("node-1", steps);

            assertThat(plan.source()).isEqualTo(PlanSource.LLM_GENERATED);
            assertThat(plan.isStatic()).isFalse();
        }
    }

    @Nested
    class StepAccess {

        @Test
        void shouldReportHasSteps() {
            Plan empty = Plan.staticPlan("node", List.of());
            Plan withSteps = Plan.staticPlan("node", List.of(PlannedStep.simple(0, "t", "")));

            assertThat(empty.hasSteps()).isFalse();
            assertThat(withSteps.hasSteps()).isTrue();
        }

        @Test
        void shouldReportStepCount() {
            List<PlannedStep> steps =
                    List.of(
                            PlannedStep.simple(0, "t1", ""),
                            PlannedStep.simple(1, "t2", ""),
                            PlannedStep.simple(2, "t3", ""));

            Plan plan = Plan.staticPlan("node", steps);

            assertThat(plan.stepCount()).isEqualTo(3);
        }

        @Test
        void shouldGetStepByIndex() {
            PlannedStep step = PlannedStep.pending(0, "tool", Map.of("key", "val"), "Desc");
            Plan plan = Plan.staticPlan("node", List.of(step));

            assertThat(plan.getStep(0)).isEqualTo(step);
        }

        @Test
        void shouldThrowOnInvalidIndex() {
            Plan plan = Plan.staticPlan("node", List.of(PlannedStep.simple(0, "t", "")));

            assertThatThrownBy(() -> plan.getStep(5)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class Immutability {

        @Test
        void shouldCreateNewPlanWithUpdatedSteps() {
            Plan original = Plan.staticPlan("node", List.of(PlannedStep.simple(0, "t1", "")));

            List<PlannedStep> newSteps =
                    List.of(PlannedStep.simple(0, "t2", ""), PlannedStep.simple(1, "t3", ""));

            Plan updated = original.withSteps(newSteps);

            assertThat(original.stepCount()).isEqualTo(1);
            assertThat(updated.stepCount()).isEqualTo(2);
            assertThat(updated.id()).isEqualTo(original.id());
        }

        @Test
        void shouldCreateNewPlanWithUpdatedConstraints() {
            Plan original = Plan.staticPlan("node", List.of());
            PlanConstraints newConstraints = PlanConstraints.defaults().withMaxSteps(50);

            Plan updated = original.withConstraints(newConstraints);

            assertThat(original.constraints().maxSteps()).isEqualTo(100); // forStaticPlan default
            assertThat(updated.constraints().maxSteps()).isEqualTo(50);
        }
    }
}
