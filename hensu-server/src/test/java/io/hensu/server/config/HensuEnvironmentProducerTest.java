package io.hensu.server.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.HensuEnvironment;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HensuEnvironmentProducerTest {

    private HensuEnvironmentProducer producer;
    private Config config;

    @BeforeEach
    void setUp() throws Exception {
        producer = new HensuEnvironmentProducer();
        config = mock(Config.class);

        // Inject mocked config via reflection
        var configField = HensuEnvironmentProducer.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(producer, config);
    }

    @Nested
    class ExtractProperties {

        @Test
        void shouldExtractCredentialProperties() throws Exception {
            when(config.getPropertyNames())
                    .thenReturn(
                            List.of(
                                    "hensu.credentials.ANTHROPIC_API_KEY",
                                    "hensu.credentials.OPENAI_API_KEY",
                                    "quarkus.http.port"));

            when(config.getOptionalValue("hensu.credentials.ANTHROPIC_API_KEY", String.class))
                    .thenReturn(Optional.of("sk-ant-123"));
            when(config.getOptionalValue("hensu.credentials.OPENAI_API_KEY", String.class))
                    .thenReturn(Optional.of("sk-openai-456"));
            when(config.getOptionalValue("hensu.stub.enabled", String.class))
                    .thenReturn(Optional.empty());

            // Invoke private extractHensuProperties via reflection
            Method method =
                    HensuEnvironmentProducer.class.getDeclaredMethod("extractHensuProperties");
            method.setAccessible(true);
            java.util.Properties props = (java.util.Properties) method.invoke(producer);

            org.assertj.core.api.Assertions.assertThat(props)
                    .containsEntry("hensu.credentials.ANTHROPIC_API_KEY", "sk-ant-123")
                    .containsEntry("hensu.credentials.OPENAI_API_KEY", "sk-openai-456")
                    .doesNotContainKey("quarkus.http.port");
        }

        @Test
        void shouldExtractStubEnabled() throws Exception {
            when(config.getPropertyNames()).thenReturn(List.of());
            when(config.getOptionalValue("hensu.stub.enabled", String.class))
                    .thenReturn(Optional.of("true"));

            Method method =
                    HensuEnvironmentProducer.class.getDeclaredMethod("extractHensuProperties");
            method.setAccessible(true);
            java.util.Properties props = (java.util.Properties) method.invoke(producer);

            org.assertj.core.api.Assertions.assertThat(props)
                    .containsEntry("hensu.stub.enabled", "true");
        }
    }

    @Nested
    class Cleanup {

        @Test
        void shouldCloseEnvironmentOnCleanup() throws Exception {
            HensuEnvironment env = mock(HensuEnvironment.class);

            // Inject environment via reflection
            var envField = HensuEnvironmentProducer.class.getDeclaredField("hensuEnvironment");
            envField.setAccessible(true);
            envField.set(producer, env);

            producer.cleanup();

            verify(env).close();
        }

        @Test
        void shouldNotThrowWhenEnvironmentIsNull() {
            // hensuEnvironment is null by default
            assertThatCode(() -> producer.cleanup()).doesNotThrowAnyException();
        }
    }
}
