package io.hensu.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CapabilityGapsTest {

    @Nested
    class WhatCountsAsAGap {

        @Test
        void shouldNotRecordOutcomesWhereTheToolActuallyRan() {
            Map<String, Object> context = new HashMap<>();

            CapabilityGaps.record(context, "node1", "run-tests", ToolCallStatus.FAILURE, List.of());
            CapabilityGaps.record(context, "node1", "run-tests", ToolCallStatus.TIMEOUT, List.of());
            CapabilityGaps.record(
                    context, "node1", "run-tests", ToolCallStatus.VALIDATION_FAILED, List.of());
            CapabilityGaps.record(
                    context, "node1", "run-tests", ToolCallStatus.BUDGET_EXHAUSTED, List.of());

            // A workflow routing on gaps must not be dragged to its "we are blocked" arm by
            // a test suite that failed or an argument the agent got wrong.
            assertThat(context).doesNotContainKey(CapabilityGaps.STATE_KEY);
            assertThat(context).doesNotContainKey(CapabilityGaps.COUNT_KEY);
        }

        @Test
        void shouldRecordEveryRefusalTheDeploymentCouldHaveGranted() {
            Map<String, Object> context = new HashMap<>();

            CapabilityGaps.record(context, "n", "t", ToolCallStatus.UNKNOWN_TOOL, List.of());
            CapabilityGaps.record(context, "n", "t", ToolCallStatus.DENIED, List.of());
            CapabilityGaps.record(context, "n", "t", ToolCallStatus.SANDBOX_UNAVAILABLE, List.of());
            CapabilityGaps.record(context, "n", "t", ToolCallStatus.SANDBOX_REFUSED, List.of());

            assertThat(CapabilityGaps.of(context))
                    .extracting(record -> record.get(CapabilityGaps.FIELD_GATE))
                    .containsExactly(
                            "UNKNOWN_TOOL", "DENIED", "SANDBOX_UNAVAILABLE", "SANDBOX_REFUSED");
        }
    }

    @Nested
    class RecordContents {

        @Test
        void shouldCarryArgumentKeysAndNeverTheirValues() {
            Map<String, Object> context = new HashMap<>();

            CapabilityGaps.record(
                    context,
                    "publish",
                    "deploy",
                    ToolCallStatus.DENIED,
                    List.of("environment", "token"));

            Map<String, Object> record = CapabilityGaps.of(context).getFirst();
            assertThat(record)
                    .containsEntry(CapabilityGaps.FIELD_TOOL, "deploy")
                    .containsEntry(CapabilityGaps.FIELD_NODE, "publish")
                    .containsEntry(
                            CapabilityGaps.FIELD_ARGUMENT_KEYS, List.of("environment", "token"));
            // The secret's *name* is the whole point of the record; its value must have no
            // way into state that survives checkpoint, resume and the run summary.
            assertThat(record.toString()).doesNotContain("sk-");
        }

        @Test
        void shouldKeepTheRoutableCountInStepWithTheRecords() {
            Map<String, Object> context = new HashMap<>();

            CapabilityGaps.record(context, "n1", "deploy", ToolCallStatus.DENIED, List.of());
            CapabilityGaps.record(context, "n2", "publish", ToolCallStatus.UNKNOWN_TOOL, List.of());

            // Conditions coerce scalars only, so a workflow routes on the count. A count
            // that drifts from the list routes a blocked run down the wrong arm.
            assertThat(context.get(CapabilityGaps.COUNT_KEY)).isEqualTo(2);
            assertThat(CapabilityGaps.of(context)).hasSize(2);
        }
    }

    @Nested
    class ReadingBack {

        @Test
        void shouldSurviveTheRoundTripAResumedRunPutsItThrough() {
            Map<String, Object> written = new HashMap<>();
            CapabilityGaps.record(
                    written, "node1", "deploy", ToolCallStatus.DENIED, List.of("environment"));

            // Deserialization hands back plain maps and lists, not the instances written.
            Map<String, Object> resumed = new HashMap<>();
            resumed.put(
                    CapabilityGaps.STATE_KEY,
                    List.copyOf(
                            CapabilityGaps.of(written).stream()
                                    .map(record -> (Object) new HashMap<>(record))
                                    .toList()));

            assertThat(CapabilityGaps.of(resumed))
                    .singleElement()
                    .satisfies(
                            record ->
                                    assertThat(
                                                    CapabilityGaps.text(
                                                            record, CapabilityGaps.FIELD_TOOL))
                                            .isEqualTo("deploy"));
        }

        @Test
        void shouldTreatAnUnrelatedValueUnderTheKeyAsNoGapsRatherThanFailing() {
            Map<String, Object> context = new HashMap<>();
            context.put(CapabilityGaps.STATE_KEY, "clobbered by something else");

            assertThat(CapabilityGaps.of(context)).isEmpty();
        }
    }
}
