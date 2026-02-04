package io.hensu.core.execution.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuSnapshot;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExecutionStepTest {

    @Nested
    class BuilderTest {

        @Test
        void shouldBuildExecutionStepWithRequiredFields() {
            // Given
            NodeResult result = NodeResult.success("output", Map.of());

            // When
            ExecutionStep step = ExecutionStep.builder().nodeId("node-1").result(result).build();

            // Then
            assertThat(step.getNodeId()).isEqualTo("node-1");
            assertThat(step.getResult()).isSameAs(result);
        }

        @Test
        void shouldBuildWithTimestamp() {
            // Given
            NodeResult result = NodeResult.success("output", Map.of());
            Instant timestamp = Instant.now();

            // When
            ExecutionStep step =
                    ExecutionStep.builder()
                            .nodeId("node-1")
                            .result(result)
                            .timestamp(timestamp)
                            .build();

            // Then
            assertThat(step.getTimestamp()).isEqualTo(timestamp);
        }

        @Test
        void shouldBuildWithSnapshot() {
            // Given
            NodeResult result = NodeResult.success("output", Map.of());
            HensuSnapshot snapshot = createTestSnapshot();

            // When
            ExecutionStep step =
                    ExecutionStep.builder()
                            .nodeId("node-1")
                            .result(result)
                            .snapshot(snapshot)
                            .build();

            // Then
            assertThat(step.getSnapshot()).isSameAs(snapshot);
        }

        @Test
        void shouldThrowWhenNodeIdIsNull() {
            // Given
            NodeResult result = NodeResult.success("output", Map.of());

            // When/Then
            assertThatThrownBy(() -> ExecutionStep.builder().result(result).build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Node ID required");
        }

        @Test
        void shouldThrowWhenResultIsNull() {
            // When/Then
            assertThatThrownBy(() -> ExecutionStep.builder().nodeId("node-1").build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Result required");
        }
    }

    @Nested
    class DirectConstructorTest {

        @Test
        void shouldCreateWithAllParameters() {
            // Given
            NodeResult result = NodeResult.success("output", Map.of());
            HensuSnapshot snapshot = createTestSnapshot();
            Instant timestamp = Instant.now();

            // When
            ExecutionStep step = new ExecutionStep("node-1", snapshot, result, timestamp);

            // Then
            assertThat(step.getNodeId()).isEqualTo("node-1");
            assertThat(step.getSnapshot()).isSameAs(snapshot);
            assertThat(step.getResult()).isSameAs(result);
            assertThat(step.getTimestamp()).isEqualTo(timestamp);
        }
    }

    @Nested
    class EqualsAndHashCodeTest {

        @Test
        void shouldBeEqualWhenNodeIdsMatch() {
            // Given
            NodeResult result1 = NodeResult.success("output1", Map.of());
            NodeResult result2 = NodeResult.success("output2", Map.of());

            ExecutionStep step1 = ExecutionStep.builder().nodeId("node-1").result(result1).build();

            ExecutionStep step2 = ExecutionStep.builder().nodeId("node-1").result(result2).build();

            // Then
            assertThat(step1).isEqualTo(step2);
            assertThat(step1.hashCode()).isEqualTo(step2.hashCode());
        }

        @Test
        void shouldNotBeEqualWhenNodeIdsDiffer() {
            // Given
            NodeResult result = NodeResult.success("output", Map.of());

            ExecutionStep step1 = ExecutionStep.builder().nodeId("node-1").result(result).build();

            ExecutionStep step2 = ExecutionStep.builder().nodeId("node-2").result(result).build();

            // Then
            assertThat(step1).isNotEqualTo(step2);
        }

        @Test
        void shouldBeEqualToItself() {
            // Given
            ExecutionStep step =
                    ExecutionStep.builder()
                            .nodeId("node-1")
                            .result(NodeResult.success("output", Map.of()))
                            .build();

            // Then
            assertThat(step).isEqualTo(step);
        }

        @Test
        void shouldNotBeEqualToNull() {
            // Given
            ExecutionStep step =
                    ExecutionStep.builder()
                            .nodeId("node-1")
                            .result(NodeResult.success("output", Map.of()))
                            .build();

            // Then
            assertThat(step).isNotEqualTo(null);
        }

        @Test
        void shouldNotBeEqualToDifferentType() {
            // Given
            ExecutionStep step =
                    ExecutionStep.builder()
                            .nodeId("node-1")
                            .result(NodeResult.success("output", Map.of()))
                            .build();

            // Then
            assertThat(step).isNotEqualTo("string");
        }
    }

    @Test
    void shouldReturnMeaningfulToString() {
        // Given
        ExecutionStep step =
                ExecutionStep.builder()
                        .nodeId("my-node")
                        .result(NodeResult.success("output", Map.of()))
                        .build();

        // When
        String toString = step.toString();

        // Then
        assertThat(toString).contains("my-node");
    }

    private static HensuSnapshot createTestSnapshot() {
        return new HensuSnapshot(
                "workflow-1",
                "exec-1",
                "node-1",
                Map.of(),
                new ExecutionHistory(),
                null,
                Instant.now(),
                null);
    }
}
