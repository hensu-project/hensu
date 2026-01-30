package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.result.ExitStatus;
import org.junit.jupiter.api.Test;

class EndNodeTest {

    @Test
    void shouldBuildEndNodeWithSuccessStatus() {
        // When
        EndNode node = EndNode.builder().id("success-end").status(ExitStatus.SUCCESS).build();

        // Then
        assertThat(node.getId()).isEqualTo("success-end");
        assertThat(node.getStatus()).isEqualTo(ExitStatus.SUCCESS);
        assertThat(node.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        assertThat(node.getNodeType()).isEqualTo(NodeType.END);
    }

    @Test
    void shouldBuildEndNodeWithFailureStatus() {
        // When
        EndNode node = EndNode.builder().id("failure-end").status(ExitStatus.FAILURE).build();

        // Then
        assertThat(node.getStatus()).isEqualTo(ExitStatus.FAILURE);
    }

    @Test
    void shouldBuildEndNodeWithCancelStatus() {
        // When
        EndNode node = EndNode.builder().id("cancel-end").status(ExitStatus.CANCEL).build();

        // Then
        assertThat(node.getStatus()).isEqualTo(ExitStatus.CANCEL);
    }

    @Test
    void shouldThrowExceptionWhenIdIsNull() {
        // When/Then
        assertThatThrownBy(() -> EndNode.builder().status(ExitStatus.SUCCESS).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID required");
    }

    @Test
    void shouldThrowExceptionWhenStatusIsNull() {
        // When/Then
        assertThatThrownBy(() -> EndNode.builder().id("test-node").build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Exit status required");
    }

    @Test
    void shouldReturnEmptyStringForRubricId() {
        // Given
        EndNode node = EndNode.builder().id("end").status(ExitStatus.SUCCESS).build();

        // Then - exit nodes don't have rubrics
        assertThat(node.getRubricId()).isEmpty();
    }

    @Test
    void shouldReturnEmptyTransitionRules() {
        // Given
        EndNode node = EndNode.builder().id("end").status(ExitStatus.SUCCESS).build();

        // Then - exit nodes have no transitions
        assertThat(node.getTransitionRules()).isEmpty();
    }

    @Test
    void shouldImplementEqualsBasedOnId() {
        // Given
        EndNode node1 = EndNode.builder().id("end").status(ExitStatus.SUCCESS).build();

        EndNode node2 = EndNode.builder().id("end").status(ExitStatus.FAILURE).build();

        EndNode node3 = EndNode.builder().id("different-end").status(ExitStatus.SUCCESS).build();

        // Then
        assertThat(node1).isEqualTo(node2); // Same ID
        assertThat(node1).isNotEqualTo(node3); // Different ID
        assertThat(node1.hashCode()).isEqualTo(node2.hashCode());
    }

    @Test
    void shouldReturnMeaningfulToString() {
        // Given
        EndNode node = EndNode.builder().id("test-end-node").status(ExitStatus.SUCCESS).build();

        // When
        String toString = node.toString();

        // Then
        assertThat(toString).contains("test-end-node");
    }
}
