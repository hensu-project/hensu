package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.plan.Plan.PlanSource;
import io.hensu.core.plan.PlanEvent.PlanCompleted;
import io.hensu.core.plan.PlanEvent.PlanCreated;
import io.hensu.core.plan.PlanEvent.PlanPaused;
import io.hensu.core.plan.PlanEvent.PlanRevised;
import io.hensu.core.plan.PlanEvent.StepCompleted;
import io.hensu.core.plan.PlanEvent.StepStarted;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlanEventTest {

    @Nested
    class PlanCreatedTest {

        @Test
        void shouldCreateFromPlan() {
            Plan plan = Plan.staticPlan("node-1", List.of(PlannedStep.simple(0, "tool", "Step 1")));

            PlanCreated event = PlanCreated.now(plan);

            assertThat(event.planId()).isEqualTo(plan.id());
            assertThat(event.nodeId()).isEqualTo("node-1");
            assertThat(event.source()).isEqualTo(PlanSource.STATIC);
            assertThat(event.steps()).hasSize(1);
            assertThat(event.timestamp()).isNotNull();
        }
    }

    @Nested
    class StepStartedTest {

        @Test
        void shouldCreateWithCurrentTimestamp() {
            PlannedStep step = PlannedStep.simple(0, "search", "Search");
            Instant before = Instant.now();

            StepStarted event = StepStarted.now("plan-1", step);

            assertThat(event.planId()).isEqualTo("plan-1");
            assertThat(event.step()).isSameAs(step);
            assertThat(event.timestamp()).isAfterOrEqualTo(before);
        }
    }

    @Nested
    class StepCompletedTest {

        @Test
        void shouldCreateWithCurrentTimestamp() {
            StepResult result = StepResult.success(0, "tool", "output", Duration.ofMillis(100));

            StepCompleted event = StepCompleted.now("plan-1", result);

            assertThat(event.planId()).isEqualTo("plan-1");
            assertThat(event.result()).isSameAs(result);
            assertThat(event.timestamp()).isNotNull();
        }
    }

    @Nested
    class PlanRevisedTest {

        @Test
        void shouldCreateWithSteps() {
            List<PlannedStep> oldSteps = List.of(PlannedStep.simple(0, "old", ""));
            List<PlannedStep> newSteps = List.of(PlannedStep.simple(0, "new", ""));

            PlanRevised event = PlanRevised.now("plan-1", "Step failed", oldSteps, newSteps);

            assertThat(event.planId()).isEqualTo("plan-1");
            assertThat(event.reason()).isEqualTo("Step failed");
            assertThat(event.oldSteps()).isEqualTo(oldSteps);
            assertThat(event.newSteps()).isEqualTo(newSteps);
        }
    }

    @Nested
    class PlanCompletedTest {

        @Test
        void shouldCreateSuccessEvent() {
            PlanCompleted event = PlanCompleted.success("plan-1", "Final result");

            assertThat(event.planId()).isEqualTo("plan-1");
            assertThat(event.success()).isTrue();
            assertThat(event.output()).isEqualTo("Final result");
        }

        @Test
        void shouldCreateFailureEvent() {
            PlanCompleted event = PlanCompleted.failure("plan-1", "Error occurred");

            assertThat(event.planId()).isEqualTo("plan-1");
            assertThat(event.success()).isFalse();
            assertThat(event.output()).isEqualTo("Error occurred");
        }
    }

    @Nested
    class PlanPausedTest {

        @Test
        void shouldCreateWithCheckpointId() {
            PlanPaused event = PlanPaused.now("plan-1", "User requested", "checkpoint-123");

            assertThat(event.planId()).isEqualTo("plan-1");
            assertThat(event.reason()).isEqualTo("User requested");
            assertThat(event.checkpointId()).isEqualTo("checkpoint-123");
        }
    }

    @Nested
    class PlanObserverTest {

        @Test
        void shouldReceiveEvents() {
            List<PlanEvent> received = new ArrayList<>();
            PlanObserver observer = received::add;

            Plan plan = Plan.staticPlan("node", List.of());
            PlanCreated event = PlanCreated.now(plan);

            observer.onEvent(event);

            assertThat(received).containsExactly(event);
        }

        @Test
        void shouldPatternMatchEvents() {
            List<String> messages = new ArrayList<>();
            PlanObserver observer =
                    event -> {
                        String message =
                                switch (event) {
                                    case StepStarted s -> "Started: " + s.step().toolName();
                                    case StepCompleted c -> "Completed: " + c.result().success();
                                    case PlanCompleted p -> "Done: " + p.success();
                                    default -> "Other";
                                };
                        messages.add(message);
                    };

            PlannedStep step = PlannedStep.simple(0, "search", "");
            observer.onEvent(StepStarted.now("p1", step));
            observer.onEvent(
                    StepCompleted.now("p1", StepResult.success(0, "search", "out", Duration.ZERO)));
            observer.onEvent(PlanCompleted.success("p1", "done"));

            assertThat(messages)
                    .containsExactly("Started: search", "Completed: true", "Done: true");
        }
    }
}
