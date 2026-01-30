package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GenericNodeTest {

    @Nested
    class BuilderTest {

        @Test
        void shouldBuildGenericNodeWithMinimalFields() {
            // When
            GenericNode node =
                    GenericNode.builder().id("generic-1").executorType("validator").build();

            // Then
            assertThat(node.getId()).isEqualTo("generic-1");
            assertThat(node.getExecutorType()).isEqualTo("validator");
            assertThat(node.getNodeType()).isEqualTo(NodeType.GENERIC);
        }

        @Test
        void shouldBuildWithConfig() {
            // When
            GenericNode node =
                    GenericNode.builder()
                            .id("generic-1")
                            .executorType("validator")
                            .config(Map.of("minLength", 10, "maxLength", 1000))
                            .build();

            // Then
            assertThat(node.getConfig()).containsEntry("minLength", 10);
            assertThat(node.getConfig()).containsEntry("maxLength", 1000);
        }

        @Test
        void shouldBuildWithConfigEntry() {
            // When
            GenericNode node =
                    GenericNode.builder()
                            .id("generic-1")
                            .executorType("validator")
                            .configEntry("minLength", 10)
                            .configEntry("maxLength", 1000)
                            .build();

            // Then
            assertThat(node.getConfig()).containsEntry("minLength", 10);
            assertThat(node.getConfig()).containsEntry("maxLength", 1000);
        }

        @Test
        void shouldBuildWithTransitionRules() {
            // Given
            List<TransitionRule> rules = List.of(new SuccessTransition("next"));

            // When
            GenericNode node =
                    GenericNode.builder()
                            .id("generic-1")
                            .executorType("validator")
                            .transitionRules(rules)
                            .build();

            // Then
            assertThat(node.getTransitionRules()).hasSize(1);
        }

        @Test
        void shouldBuildWithRubricId() {
            // When
            GenericNode node =
                    GenericNode.builder()
                            .id("generic-1")
                            .executorType("validator")
                            .rubricId("quality-rubric")
                            .build();

            // Then
            assertThat(node.getRubricId()).isEqualTo("quality-rubric");
        }

        @Test
        void shouldThrowWhenIdIsNull() {
            // When/Then
            assertThatThrownBy(() -> GenericNode.builder().executorType("validator").build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("id is required");
        }

        @Test
        void shouldThrowWhenIdIsBlank() {
            // When/Then
            assertThatThrownBy(
                            () -> GenericNode.builder().id("   ").executorType("validator").build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("id is required");
        }

        @Test
        void shouldThrowWhenExecutorTypeIsNull() {
            // When/Then
            assertThatThrownBy(() -> GenericNode.builder().id("generic-1").build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("executorType is required");
        }

        @Test
        void shouldThrowWhenExecutorTypeIsBlank() {
            // When/Then
            assertThatThrownBy(() -> GenericNode.builder().id("generic-1").executorType("").build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("executorType is required");
        }
    }

    @Test
    void shouldReturnNullRubricIdWhenNotSet() {
        // When
        GenericNode node = GenericNode.builder().id("generic-1").executorType("validator").build();

        // Then
        assertThat(node.getRubricId()).isNull();
    }

    @Test
    void shouldReturnEmptyConfigWhenNotSet() {
        // When
        GenericNode node = GenericNode.builder().id("generic-1").executorType("validator").build();

        // Then
        assertThat(node.getConfig()).isEmpty();
    }

    @Test
    void shouldReturnEmptyTransitionRulesWhenNotSet() {
        // When
        GenericNode node = GenericNode.builder().id("generic-1").executorType("validator").build();

        // Then
        assertThat(node.getTransitionRules()).isEmpty();
    }

    @Test
    void shouldMakeConfigImmutable() {
        // Given
        GenericNode node =
                GenericNode.builder()
                        .id("generic-1")
                        .executorType("validator")
                        .config(Map.of("key", "value"))
                        .build();

        // Then
        assertThat(node.getConfig()).isUnmodifiable();
    }

    @Test
    void shouldHandleNullConfigGracefully() {
        // When
        GenericNode node =
                GenericNode.builder()
                        .id("generic-1")
                        .executorType("validator")
                        .config(null)
                        .build();

        // Then
        assertThat(node.getConfig()).isEmpty();
    }

    @Test
    void shouldAllowMultipleNodesWithSameExecutorType() {
        // When
        GenericNode node1 =
                GenericNode.builder()
                        .id("validate-input")
                        .executorType("validator")
                        .configEntry("field", "name")
                        .build();

        GenericNode node2 =
                GenericNode.builder()
                        .id("validate-email")
                        .executorType("validator")
                        .configEntry("field", "email")
                        .build();

        // Then
        assertThat(node1.getExecutorType()).isEqualTo(node2.getExecutorType());
        assertThat(node1.getId()).isNotEqualTo(node2.getId());
        assertThat(node1.getConfig()).isNotEqualTo(node2.getConfig());
    }
}
