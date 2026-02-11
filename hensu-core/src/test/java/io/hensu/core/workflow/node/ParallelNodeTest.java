package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.parallel.Branch;
import io.hensu.core.execution.parallel.ConsensusConfig;
import io.hensu.core.execution.parallel.ConsensusStrategy;
import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ParallelNodeTest {

    @Nested
    class BuilderTest {

        @Test
        void shouldBuildParallelNodeWithMinimalFields() {
            // When
            ParallelNode node =
                    ParallelNode.builder("parallel-1").branch("b1", "agent-1", "Prompt 1").build();

            // Then
            assertThat(node.getId()).isEqualTo("parallel-1");
            assertThat(node.getNodeType()).isEqualTo(NodeType.PARALLEL);
            assertThat(node.getBranches()).hasSize(1);
        }

        @Test
        void shouldBuildParallelNodeWithMultipleBranches() {
            // When
            ParallelNode node =
                    ParallelNode.builder("parallel-1")
                            .branch("b1", "agent-1", "Prompt 1")
                            .branch("b2", "agent-2", "Prompt 2")
                            .branch("b3", "agent-3", "Prompt 3")
                            .build();

            // Then
            assertThat(node.getBranches()).hasSize(3);
            assertThat(node.getBranchesList()).hasSize(3);
        }

        @Test
        void shouldBuildWithBranchIncludingRubric() {
            // When
            ParallelNode node =
                    ParallelNode.builder("parallel-1")
                            .branch("b1", "agent-1", "Prompt 1", "rubric-1")
                            .build();

            // Then
            assertThat(node.getBranches()[0].rubricId()).isEqualTo("rubric-1");
        }

        @Test
        void shouldBuildWithBranchObject() {
            // Given
            Branch branch = new Branch("b1", "agent-1", "Prompt 1", "rubric-1");

            // When
            ParallelNode node = ParallelNode.builder("parallel-1").branch(branch).build();

            // Then
            assertThat(node.getBranches()[0]).isEqualTo(branch);
        }

        @Test
        void shouldBuildWithBranchWeight() {
            // Given
            Branch branch = new Branch("b1", "agent-1", "Prompt 1", null, 2.5);

            // When
            ParallelNode node = ParallelNode.builder("parallel-1").branch(branch).build();

            // Then
            assertThat(node.getBranches()[0].getWeight()).isEqualTo(2.5);
        }

        @Test
        void shouldDefaultBranchWeightToOne() {
            // When
            ParallelNode node =
                    ParallelNode.builder("parallel-1").branch("b1", "agent-1", "Prompt 1").build();

            // Then
            assertThat(node.getBranches()[0].getWeight()).isEqualTo(1.0);
        }

        @Test
        void shouldBuildWithBranchesList() {
            // Given
            List<Branch> branches =
                    List.of(
                            new Branch("b1", "agent-1", "Prompt 1", null),
                            new Branch("b2", "agent-2", "Prompt 2", null));

            // When
            ParallelNode node = ParallelNode.builder("parallel-1").branches(branches).build();

            // Then
            assertThat(node.getBranches()).hasSize(2);
        }

        @Test
        void shouldBuildWithConsensusConfig() {
            // Given
            ConsensusConfig config =
                    new ConsensusConfig("judge", ConsensusStrategy.MAJORITY_VOTE, 0.7);

            // When
            ParallelNode node =
                    ParallelNode.builder("parallel-1")
                            .branch("b1", "agent-1", "Prompt 1")
                            .consensus(config)
                            .build();

            // Then
            assertThat(node.hasConsensus()).isTrue();
            assertThat(node.getConsensusConfig()).isEqualTo(config);
            assertThat(node.getConsensusConfig().getJudgeAgentId()).isEqualTo("judge");
        }

        @Test
        void shouldBuildWithConsensusShorthand() {
            // When
            ParallelNode node =
                    ParallelNode.builder("parallel-1")
                            .branch("b1", "agent-1", "Prompt 1")
                            .consensus("judge", ConsensusStrategy.JUDGE_DECIDES)
                            .build();

            // Then
            assertThat(node.hasConsensus()).isTrue();
            assertThat(node.getConsensusConfig().getStrategy())
                    .isEqualTo(ConsensusStrategy.JUDGE_DECIDES);
        }

        @Test
        void shouldBuildWithConsensusAndThreshold() {
            // When
            ParallelNode node =
                    ParallelNode.builder("parallel-1")
                            .branch("b1", "agent-1", "Prompt 1")
                            .consensus("judge", ConsensusStrategy.WEIGHTED_VOTE, 0.8)
                            .build();

            // Then
            assertThat(node.getConsensusConfig().getThreshold()).isEqualTo(0.8);
        }

        @Test
        void shouldBuildWithTransitionRules() {
            // Given
            List<TransitionRule> rules = List.of(new SuccessTransition("next"));

            // When
            ParallelNode node =
                    ParallelNode.builder("parallel-1")
                            .branch("b1", "agent-1", "Prompt 1")
                            .transitionRules(rules)
                            .build();

            // Then
            assertThat(node.getTransitionRules()).hasSize(1);
        }

        @Test
        void shouldThrowWhenNoBranches() {
            // When/Then
            assertThatThrownBy(() -> ParallelNode.builder("parallel-1").build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least one branch");
        }
    }

    @Nested
    class ConsensusTest {

        @Test
        void shouldReturnFalseWhenNoConsensus() {
            // When
            ParallelNode node =
                    ParallelNode.builder("parallel-1").branch("b1", "agent-1", "Prompt 1").build();

            // Then
            assertThat(node.hasConsensus()).isFalse();
            assertThat(node.getConsensusConfig()).isNull();
        }

        @Test
        void shouldReturnTrueWhenConsensusConfigured() {
            // When
            ParallelNode node =
                    ParallelNode.builder("parallel-1")
                            .branch("b1", "agent-1", "Prompt 1")
                            .consensus("judge", ConsensusStrategy.UNANIMOUS)
                            .build();

            // Then
            assertThat(node.hasConsensus()).isTrue();
        }
    }

    @Test
    void shouldReturnNullRubricId() {
        // When
        ParallelNode node =
                ParallelNode.builder("parallel-1").branch("b1", "agent-1", "Prompt 1").build();

        // Then
        assertThat(node.getRubricId()).isNull();
    }

    @Test
    void shouldReturnEmptyTransitionRulesWhenNotSet() {
        // When
        ParallelNode node =
                ParallelNode.builder("parallel-1").branch("b1", "agent-1", "Prompt 1").build();

        // Then
        assertThat(node.getTransitionRules()).isEmpty();
    }

    @Test
    void shouldMakeBranchesListImmutable() {
        // Given
        ParallelNode node =
                ParallelNode.builder("parallel-1").branch("b1", "agent-1", "Prompt 1").build();

        // Then
        assertThat(node.getBranchesList()).isUnmodifiable();
    }
}
