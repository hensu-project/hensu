package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ForkNodeTest {

    @Nested
    class BuilderTest {

        @Test
        void shouldBuildForkNodeWithTargetsList() {
            // When
            ForkNode node =
                    ForkNode.builder("fork-1")
                            .targets(List.of("node-a", "node-b", "node-c"))
                            .build();

            // Then
            assertThat(node.getId()).isEqualTo("fork-1");
            assertThat(node.getNodeType()).isEqualTo(NodeType.FORK);
            assertThat(node.getTargets()).containsExactly("node-a", "node-b", "node-c");
        }

        @Test
        void shouldBuildForkNodeWithVarargTargets() {
            // When
            ForkNode node = ForkNode.builder("fork-1").targets("node-a", "node-b").build();

            // Then
            assertThat(node.getTargets()).containsExactly("node-a", "node-b");
        }

        @Test
        void shouldBuildWithTargetConfigs() {
            // Given
            Map<String, Object> configs =
                    Map.of(
                            "node-a", Map.of("timeout", 5000),
                            "node-b", Map.of("retries", 3));

            // When
            ForkNode node =
                    ForkNode.builder("fork-1")
                            .targets("node-a", "node-b")
                            .targetConfigs(configs)
                            .build();

            // Then
            assertThat(node.getTargetConfigs()).containsKeys("node-a", "node-b");
        }

        @Test
        void shouldBuildWithTransitionRules() {
            // Given
            List<TransitionRule> rules = List.of(new SuccessTransition("join-node"));

            // When
            ForkNode node =
                    ForkNode.builder("fork-1").targets("node-a").transitionRules(rules).build();

            // Then
            assertThat(node.getTransitionRules()).hasSize(1);
        }

        @Test
        void shouldBuildWithWaitForAll() {
            // When
            ForkNode node = ForkNode.builder("fork-1").targets("node-a").waitForAll(true).build();

            // Then
            assertThat(node.isWaitForAll()).isTrue();
        }

        @Test
        void shouldDefaultWaitForAllToFalse() {
            // When
            ForkNode node = ForkNode.builder("fork-1").targets("node-a").build();

            // Then
            assertThat(node.isWaitForAll()).isFalse();
        }

        @Test
        void shouldThrowWhenNoTargets() {
            // When/Then
            assertThatThrownBy(() -> ForkNode.builder("fork-1").build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least one target");
        }
    }

    @Test
    void shouldReturnNullRubricId() {
        // When
        ForkNode node = ForkNode.builder("fork-1").targets("node-a").build();

        // Then
        assertThat(node.getRubricId()).isNull();
    }

    @Test
    void shouldReturnEmptyTargetConfigsWhenNotSet() {
        // When
        ForkNode node = ForkNode.builder("fork-1").targets("node-a").build();

        // Then
        assertThat(node.getTargetConfigs()).isEmpty();
    }

    @Test
    void shouldReturnEmptyTransitionRulesWhenNotSet() {
        // When
        ForkNode node = ForkNode.builder("fork-1").targets("node-a").build();

        // Then
        assertThat(node.getTransitionRules()).isEmpty();
    }

    @Test
    void shouldMakeTargetsImmutable() {
        // Given
        ForkNode node = ForkNode.builder("fork-1").targets("node-a", "node-b").build();

        // Then
        assertThat(node.getTargets()).isUnmodifiable();
    }

    @Test
    void shouldMakeTargetConfigsImmutable() {
        // Given
        ForkNode node =
                ForkNode.builder("fork-1")
                        .targets("node-a")
                        .targetConfigs(Map.of("node-a", "config"))
                        .build();

        // Then
        assertThat(node.getTargetConfigs()).isUnmodifiable();
    }
}
