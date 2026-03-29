package io.hensu.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultToolRegistryTest {

    private DefaultToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultToolRegistry();
    }

    @Test
    void shouldRejectNullToolRegistration() {
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tool");
    }

    @Test
    void shouldReturnDefensiveCopyFromAll() {
        registry.register(ToolDefinition.simple("tool", "Tool"));

        List<ToolDefinition> snapshot = registry.all();

        // Subsequent registry mutations must not alter a previously returned snapshot
        registry.register(ToolDefinition.simple("tool2", "Tool 2"));
        assertThat(snapshot).hasSize(1);
    }

    @Test
    void shouldReplaceToolWithSameName() {
        registry.register(ToolDefinition.simple("search", "Original"));
        registry.register(ToolDefinition.simple("search", "Replacement"));

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get("search"))
                .hasValueSatisfying(t -> assertThat(t.description()).isEqualTo("Replacement"));
    }
}
