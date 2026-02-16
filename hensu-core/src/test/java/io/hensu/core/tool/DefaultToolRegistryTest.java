package io.hensu.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DefaultToolRegistryTest {

    private DefaultToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultToolRegistry();
    }

    @Nested
    class Registration {

        @Test
        void shouldRegisterTool() {
            ToolDefinition tool = ToolDefinition.simple("search", "Search tool");

            registry.register(tool);

            assertThat(registry.contains("search")).isTrue();
            assertThat(registry.get("search")).hasValue(tool);
        }

        @Test
        void shouldReplaceExistingTool() {
            ToolDefinition original = ToolDefinition.simple("search", "Original");
            ToolDefinition replacement = ToolDefinition.simple("search", "Replacement");

            registry.register(original);
            registry.register(replacement);

            assertThat(registry.get("search"))
                    .hasValueSatisfying(t -> assertThat(t.description()).isEqualTo("Replacement"));
            assertThat(registry.size()).isEqualTo(1);
        }

        @Test
        void shouldThrowWhenToolIsNull() {
            assertThatThrownBy(() -> registry.register(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Retrieval {

        @Test
        void shouldReturnEmptyWhenToolNotFound() {
            Optional<ToolDefinition> result = registry.get("nonexistent");

            assertThat(result).isEmpty();
        }

        @Test
        void shouldThrowWhenGetNameIsNull() {
            assertThatThrownBy(() -> registry.get(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldReturnAllTools() {
            registry.register(ToolDefinition.simple("tool1", "Tool 1"));
            registry.register(ToolDefinition.simple("tool2", "Tool 2"));
            registry.register(ToolDefinition.simple("tool3", "Tool 3"));

            List<ToolDefinition> all = registry.all();

            assertThat(all).hasSize(3);
            assertThat(all.stream().map(ToolDefinition::name))
                    .containsExactlyInAnyOrder("tool1", "tool2", "tool3");
        }

        @Test
        void shouldReturnImmutableListFromAll() {
            registry.register(ToolDefinition.simple("tool", "Tool"));

            List<ToolDefinition> all = registry.all();

            assertThatThrownBy(() -> all.add(ToolDefinition.simple("new", "New")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Removal {

        @Test
        void shouldRemoveTool() {
            registry.register(ToolDefinition.simple("tool", "Tool"));

            boolean removed = registry.remove("tool");

            assertThat(removed).isTrue();
            assertThat(registry.contains("tool")).isFalse();
        }

        @Test
        void shouldReturnFalseWhenRemovingNonexistent() {
            boolean removed = registry.remove("nonexistent");

            assertThat(removed).isFalse();
        }

        @Test
        void shouldThrowWhenRemoveNameIsNull() {
            assertThatThrownBy(() -> registry.remove(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Contains {

        @Test
        void shouldReturnTrueForRegisteredTool() {
            registry.register(ToolDefinition.simple("tool", "Tool"));

            assertThat(registry.contains("tool")).isTrue();
        }

        @Test
        void shouldReturnFalseForUnregisteredTool() {
            assertThat(registry.contains("nonexistent")).isFalse();
        }

        @Test
        void shouldThrowWhenContainsNameIsNull() {
            assertThatThrownBy(() -> registry.contains(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Size {

        @Test
        void shouldReturnZeroForEmptyRegistry() {
            assertThat(registry.size()).isZero();
        }

        @Test
        void shouldReturnCorrectSize() {
            registry.register(ToolDefinition.simple("tool1", "Tool 1"));
            registry.register(ToolDefinition.simple("tool2", "Tool 2"));

            assertThat(registry.size()).isEqualTo(2);
        }
    }

    @Nested
    class InitialTools {

        @Test
        void shouldCreateWithInitialTools() {
            List<ToolDefinition> initial =
                    List.of(
                            ToolDefinition.simple("tool1", "Tool 1"),
                            ToolDefinition.simple("tool2", "Tool 2"));

            DefaultToolRegistry registryWithInitial = new DefaultToolRegistry(initial);

            assertThat(registryWithInitial.size()).isEqualTo(2);
            assertThat(registryWithInitial.contains("tool1")).isTrue();
            assertThat(registryWithInitial.contains("tool2")).isTrue();
        }

        @Test
        void shouldThrowWhenInitialToolsIsNull() {
            assertThatThrownBy(() -> new DefaultToolRegistry(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
