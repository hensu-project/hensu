package io.hensu.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.server.config.ServerConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpConnectionFactoryTest {

    private McpConnectionFactory factory;

    @BeforeEach
    void setUp() {
        // Uses the stub implementation from ServerConfiguration
        factory = new ServerConfiguration().mcpConnectionFactory();
    }

    @Test
    void createShouldThrow() {
        assertThatThrownBy(
                        () ->
                                factory.create(
                                        "http://mcp:3000",
                                        Duration.ofSeconds(30),
                                        Duration.ofSeconds(60)))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void supportsShouldReturnFalse() {
        assertThat(factory.supports("http://mcp:3000")).isFalse();
        assertThat(factory.supports("http://any:8080")).isFalse();
    }
}
