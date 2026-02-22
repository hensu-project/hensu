package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.plan.Planner.PlanRequest;
import io.hensu.core.plan.Planner.RevisionContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StaticPlannerTest {

    @Nested
    class Construction {

        @Test
        void shouldThrowWhenPlanIsNull() {
            assertThatThrownBy(() -> new StaticPlanner(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("predefinedPlan");
        }
    }

    @Nested
    class CreatePlan {

        @Test
        void shouldReturnPredefinedPlan() {
            Plan predefined =
                    Plan.staticPlan("node", List.of(PlannedStep.simple(0, "tool", "Step 1")));

            StaticPlanner planner = new StaticPlanner(predefined);
            Plan result = planner.createPlan(PlanRequest.simple("Goal"));

            assertThat(result.nodeId()).isEqualTo("node");
            assertThat(result.stepCount()).isEqualTo(1);
        }

        @Test
        void shouldResolvePlaceholdersInArguments() {
            Plan predefined =
                    Plan.staticPlan(
                            "node",
                            List.of(
                                    PlannedStep.pending(
                                            0,
                                            "get_order",
                                            Map.of("id", "{orderId}"),
                                            "Fetch order")));

            StaticPlanner planner = new StaticPlanner(predefined);
            Plan result =
                    planner.createPlan(
                            new PlanRequest("Goal", List.of(), Map.of("orderId", "12345"), null));

            assertThat(result.getStep(0).arguments()).containsEntry("id", "12345");
        }

        @Test
        void shouldResolvePlaceholdersInDescription() {
            Plan predefined =
                    Plan.staticPlan(
                            "node",
                            List.of(
                                    PlannedStep.pending(
                                            0, "process", Map.of(), "Process order {orderId}")));

            StaticPlanner planner = new StaticPlanner(predefined);
            Plan result =
                    planner.createPlan(
                            new PlanRequest("Goal", List.of(), Map.of("orderId", "ABC"), null));

            assertThat(result.getStep(0).description()).isEqualTo("Process order ABC");
        }

        @Test
        void shouldResolveMultiplePlaceholders() {
            Plan predefined =
                    Plan.staticPlan(
                            "node",
                            List.of(
                                    PlannedStep.pending(
                                            0,
                                            "notify",
                                            Map.of("message", "Order {orderId} for {customer}"),
                                            "Notify")));

            StaticPlanner planner = new StaticPlanner(predefined);
            Plan result =
                    planner.createPlan(
                            new PlanRequest(
                                    "Goal",
                                    List.of(),
                                    Map.of("orderId", "123", "customer", "John"),
                                    null));

            assertThat(result.getStep(0).arguments())
                    .containsEntry("message", "Order 123 for John");
        }

        @Test
        void shouldLeaveUnresolvedPlaceholdersAsIs() {
            Plan predefined =
                    Plan.staticPlan(
                            "node",
                            List.of(
                                    PlannedStep.pending(
                                            0, "tool", Map.of("key", "{unknown}"), "Desc")));

            StaticPlanner planner = new StaticPlanner(predefined);
            Plan result = planner.createPlan(new PlanRequest("Goal", List.of(), Map.of(), null));

            assertThat(result.getStep(0).arguments()).containsEntry("key", "{unknown}");
        }

        @Test
        void shouldResolveNestedMapValues() {
            Plan predefined =
                    Plan.staticPlan(
                            "node",
                            List.of(
                                    PlannedStep.pending(
                                            0,
                                            "api",
                                            Map.of(
                                                    "payload",
                                                    Map.of("id", "{userId}", "action", "update")),
                                            "Call API")));

            StaticPlanner planner = new StaticPlanner(predefined);
            Plan result =
                    planner.createPlan(
                            new PlanRequest("Goal", List.of(), Map.of("userId", "U789"), null));

            Map<String, Object> payload =
                    (Map<String, Object>) result.getStep(0).arguments().get("payload");
            assertThat(payload).containsEntry("id", "U789").containsEntry("action", "update");
        }

        @Test
        void shouldResolveListValues() {
            Plan predefined =
                    Plan.staticPlan(
                            "node",
                            List.of(
                                    PlannedStep.pending(
                                            0,
                                            "batch",
                                            Map.of("items", List.of("{item1}", "{item2}")),
                                            "Batch")));

            StaticPlanner planner = new StaticPlanner(predefined);
            Plan result =
                    planner.createPlan(
                            new PlanRequest(
                                    "Goal", List.of(), Map.of("item1", "A", "item2", "B"), null));

            List<String> items = (List<String>) result.getStep(0).arguments().get("items");
            assertThat(items).containsExactly("A", "B");
        }

        @Test
        void shouldConvertNonStringContextValueToString() {
            // Context values are Object — e.g. Integer set by a previous node.
            // A naive (String) cast crashes the workflow; planner must call toString().
            Plan predefined =
                    Plan.staticPlan(
                            "node",
                            List.of(
                                    PlannedStep.pending(
                                            0, "echo", Map.of("count", "{itemCount}"), "Echo")));

            StaticPlanner planner = new StaticPlanner(predefined);
            Plan result =
                    planner.createPlan(
                            new PlanRequest("Goal", List.of(), Map.of("itemCount", 42), null));

            assertThat(result.getStep(0).arguments()).containsEntry("count", "42");
        }
    }

    @Nested
    class RevisePlan {

        @Test
        void shouldThrowOnRevision() {
            Plan predefined = Plan.staticPlan("node", List.of());
            StaticPlanner planner = new StaticPlanner(predefined);

            StepResult failedResult = StepResult.failure(0, "tool", "error", Duration.ZERO);
            RevisionContext context = RevisionContext.fromFailure(failedResult);

            assertThatThrownBy(() -> planner.revisePlan(predefined, context))
                    .isInstanceOf(PlanRevisionException.class)
                    .hasMessageContaining("Static plans cannot be revised");
        }
    }

    @Nested
    class PlanRequestTest {

        @Test
        void shouldCreateSimpleRequest() {
            PlanRequest request = PlanRequest.simple("Do something");

            assertThat(request.goal()).isEqualTo("Do something");
            assertThat(request.availableTools()).isEmpty();
            assertThat(request.context()).isEmpty();
            assertThat(request.constraints()).isNotNull();
        }

        @Test
        void shouldDefaultNullValues() {
            PlanRequest request = new PlanRequest(null, null, null, null);

            assertThat(request.goal()).isEmpty();
            assertThat(request.availableTools()).isEmpty();
            assertThat(request.context()).isEmpty();
            assertThat(request.constraints()).isNotNull();
        }
    }

    @Nested
    class RevisionContextTest {

        @Test
        void shouldCreateFromFailure() {
            StepResult failure =
                    StepResult.failure(2, "api_call", "Timeout", Duration.ofSeconds(5));

            RevisionContext context = RevisionContext.fromFailure(failure);

            assertThat(context.failedAtStep()).isEqualTo(2);
            assertThat(context.failureResult()).isSameAs(failure);
            assertThat(context.revisionReason()).contains("Step 2 failed").contains("Timeout");
        }
    }
}
