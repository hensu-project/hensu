package io.hensu.core.execution.parallel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BranchTest {

    @Test
    void shouldCreateBranchWithAllFields() {
        // When
        Branch branch = new Branch("branch-1", "reviewer", "Review this code", "quality-rubric");

        // Then
        assertThat(branch.id()).isEqualTo("branch-1");
        assertThat(branch.getId()).isEqualTo("branch-1");
        assertThat(branch.agentId()).isEqualTo("reviewer");
        assertThat(branch.getAgentId()).isEqualTo("reviewer");
        assertThat(branch.prompt()).isEqualTo("Review this code");
        assertThat(branch.getPrompt()).isEqualTo("Review this code");
        assertThat(branch.rubricId()).isEqualTo("quality-rubric");
    }

    @Test
    void shouldAllowNullRubricId() {
        // When
        Branch branch = new Branch("branch-1", "reviewer", "Review this code", null);

        // Then
        assertThat(branch.rubricId()).isNull();
    }

    @Test
    void shouldImplementEquality() {
        // Given
        Branch branch1 = new Branch("branch-1", "agent-1", "prompt-1", null);
        Branch branch2 = new Branch("branch-1", "agent-1", "prompt-1", null);
        Branch branch3 = new Branch("branch-2", "agent-1", "prompt-1", null);

        // Then
        assertThat(branch1).isEqualTo(branch2);
        assertThat(branch1).isNotEqualTo(branch3);
        assertThat(branch1.hashCode()).isEqualTo(branch2.hashCode());
    }

    @Test
    void shouldReturnMeaningfulToString() {
        // Given
        Branch branch = new Branch("review-branch", "senior-reviewer", "Review carefully", null);

        // When
        String toString = branch.toString();

        // Then
        assertThat(toString).contains("review-branch");
        assertThat(toString).contains("senior-reviewer");
    }
}
