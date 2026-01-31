package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JoinNodeTest {

    @Nested
    class BuilderTest {

        @Test
        void shouldBuildJoinNodeWithTargetsList() {
            // When
            JoinNode node =
                    JoinNode.builder("join-1").awaitTargets(List.of("fork-1", "fork-2")).build();

            // Then
            assertThat(node.getId()).isEqualTo("join-1");
            assertThat(node.getNodeType()).isEqualTo(NodeType.JOIN);
            assertThat(node.getAwaitTargets()).containsExactly("fork-1", "fork-2");
        }

        @Test
        void shouldBuildJoinNodeWithVarargTargets() {
            // When
            JoinNode node = JoinNode.builder("join-1").awaitTargets("fork-1", "fork-2").build();

            // Then
            assertThat(node.getAwaitTargets()).containsExactly("fork-1", "fork-2");
        }

        @Test
        void shouldBuildWithMergeStrategy() {
            // When
            JoinNode node =
                    JoinNode.builder("join-1")
                            .awaitTargets("fork-1")
                            .mergeStrategy(MergeStrategy.CONCATENATE)
                            .build();

            // Then
            assertThat(node.getMergeStrategy()).isEqualTo(MergeStrategy.CONCATENATE);
        }

        @Test
        void shouldDefaultMergeStrategyToCollectAll() {
            // When
            JoinNode node = JoinNode.builder("join-1").awaitTargets("fork-1").build();

            // Then
            assertThat(node.getMergeStrategy()).isEqualTo(MergeStrategy.COLLECT_ALL);
        }

        @Test
        void shouldBuildWithOutputField() {
            // When
            JoinNode node =
                    JoinNode.builder("join-1")
                            .awaitTargets("fork-1")
                            .outputField("merged_data")
                            .build();

            // Then
            assertThat(node.getOutputField()).isEqualTo("merged_data");
        }

        @Test
        void shouldDefaultOutputFieldToForkResults() {
            // When
            JoinNode node = JoinNode.builder("join-1").awaitTargets("fork-1").build();

            // Then
            assertThat(node.getOutputField()).isEqualTo("fork_results");
        }

        @Test
        void shouldBuildWithTimeoutMs() {
            // When
            JoinNode node =
                    JoinNode.builder("join-1").awaitTargets("fork-1").timeoutMs(30000).build();

            // Then
            assertThat(node.getTimeoutMs()).isEqualTo(30000);
        }

        @Test
        void shouldDefaultTimeoutToZero() {
            // When
            JoinNode node = JoinNode.builder("join-1").awaitTargets("fork-1").build();

            // Then
            assertThat(node.getTimeoutMs()).isEqualTo(0);
        }

        @Test
        void shouldBuildWithFailOnAnyError() {
            // When
            JoinNode node =
                    JoinNode.builder("join-1").awaitTargets("fork-1").failOnAnyError(false).build();

            // Then
            assertThat(node.isFailOnAnyError()).isFalse();
        }

        @Test
        void shouldDefaultFailOnAnyErrorToTrue() {
            // When
            JoinNode node = JoinNode.builder("join-1").awaitTargets("fork-1").build();

            // Then
            assertThat(node.isFailOnAnyError()).isTrue();
        }

        @Test
        void shouldBuildWithTransitionRules() {
            // Given
            List<TransitionRule> rules = List.of(new SuccessTransition("next"));

            // When
            JoinNode node =
                    JoinNode.builder("join-1")
                            .awaitTargets("fork-1")
                            .transitionRules(rules)
                            .build();

            // Then
            assertThat(node.getTransitionRules()).hasSize(1);
        }

        @Test
        void shouldThrowWhenNoAwaitTargets() {
            // When/Then
            assertThatThrownBy(() -> JoinNode.builder("join-1").build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least one await target");
        }
    }

    @Test
    void shouldReturnNullRubricId() {
        // When
        JoinNode node = JoinNode.builder("join-1").awaitTargets("fork-1").build();

        // Then
        assertThat(node.getRubricId()).isNull();
    }

    @Test
    void shouldReturnEmptyTransitionRulesWhenNotSet() {
        // When
        JoinNode node = JoinNode.builder("join-1").awaitTargets("fork-1").build();

        // Then
        assertThat(node.getTransitionRules()).isEmpty();
    }

    @Test
    void shouldMakeAwaitTargetsImmutable() {
        // Given
        JoinNode node = JoinNode.builder("join-1").awaitTargets("fork-1", "fork-2").build();

        // Then
        assertThat(node.getAwaitTargets()).isUnmodifiable();
    }

    @Nested
    class MergeStrategyTest {

        @Test
        void shouldSupportAllMergeStrategies() {
            // Then
            assertThat(MergeStrategy.values())
                    .containsExactlyInAnyOrder(
                            MergeStrategy.COLLECT_ALL,
                            MergeStrategy.FIRST_COMPLETED,
                            MergeStrategy.CONCATENATE,
                            MergeStrategy.MERGE_MAPS,
                            MergeStrategy.CUSTOM);
        }

        @Test
        void shouldBuildWithFirstCompleted() {
            // When
            JoinNode node =
                    JoinNode.builder("join-1")
                            .awaitTargets("fork-1")
                            .mergeStrategy(MergeStrategy.FIRST_COMPLETED)
                            .build();

            // Then
            assertThat(node.getMergeStrategy()).isEqualTo(MergeStrategy.FIRST_COMPLETED);
        }

        @Test
        void shouldBuildWithMergeMaps() {
            // When
            JoinNode node =
                    JoinNode.builder("join-1")
                            .awaitTargets("fork-1")
                            .mergeStrategy(MergeStrategy.MERGE_MAPS)
                            .build();

            // Then
            assertThat(node.getMergeStrategy()).isEqualTo(MergeStrategy.MERGE_MAPS);
        }

        @Test
        void shouldBuildWithCustomStrategy() {
            // When
            JoinNode node =
                    JoinNode.builder("join-1")
                            .awaitTargets("fork-1")
                            .mergeStrategy(MergeStrategy.CUSTOM)
                            .build();

            // Then
            assertThat(node.getMergeStrategy()).isEqualTo(MergeStrategy.CUSTOM);
        }
    }
}
