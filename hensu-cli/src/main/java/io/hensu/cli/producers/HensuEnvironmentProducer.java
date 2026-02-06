package io.hensu.cli.producers;

import io.hensu.adapter.langchain4j.LangChain4jProvider;
import io.hensu.cli.action.CLIActionExecutor;
import io.hensu.cli.review.CLIReviewManager;
import io.hensu.core.HensuConfig;
import io.hensu.core.HensuEnvironment;
import io.hensu.core.HensuFactory;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.review.ReviewHandler;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.Config;

/// CDI producer for the Hensu runtime environment and its dependencies.
///
/// Wires together the core execution components: workflow executor, action executor,
/// review handler, and generic node handlers discovered via CDI.
///
/// ### Credential Discovery
/// Credentials are loaded from (in priority order):
/// 1. **Application properties** under `hensu.credentials.*`
/// 2. **Environment variables** matching patterns: `*_API_KEY`, `*_KEY`, `*_SECRET`, `*_TOKEN`
///
/// ### Configuration Properties
/// | Property | Type | Default | Description |
/// |----------|------|---------|-------------|
/// | `hensu.credentials.ANTHROPIC_API_KEY` | String | - | Anthropic API key |
/// | `hensu.credentials.OPENAI_API_KEY` | String | - | OpenAI API key |
/// | `hensu.credentials.GOOGLE_API_KEY` | String | - | Google AI Gemini API key |
/// | `hensu.stub.enabled` | Boolean | `false` | Enable stub mode for testing |
/// | `hensu.review.interactive` | Boolean | `false` | Enable human review prompts |
///
/// @implNote Application-scoped singleton. Thread-safe after initialization.
/// The `quarkus-langchain4j-*` extensions are on the classpath solely for
/// GraalVM reflection metadata — Hensu creates models programmatically via
/// {@link LangChain4jProvider}, not via `@RegisterAiService`.
///
/// @see io.hensu.core.HensuEnvironment
/// @see CLIReviewManager
@ApplicationScoped
public class HensuEnvironmentProducer {

    private static final Logger logger = Logger.getLogger(HensuEnvironmentProducer.class.getName());

    private HensuEnvironment hensuEnvironment;

    @Inject Config config;

    @Inject Instance<GenericNodeHandler> genericNodeHandlers;

    @Inject CLIActionExecutor actionExecutor;

    /// Produces the Hensu runtime environment for CDI injection.
    ///
    /// Configures virtual threads, loads credentials from `hensu.credentials.*`
    /// properties and environment variables, sets up the CLI action executor
    /// and review handler, then registers all discovered generic node handlers.
    ///
    /// @return configured environment singleton, never null
    @Produces
    @ApplicationScoped
    public HensuEnvironment hensuEnvironment() {
        Properties properties = extractHensuProperties();
        ReviewHandler reviewHandler = createReviewHandler();

        hensuEnvironment =
                HensuFactory.builder()
                        .config(HensuConfig.builder().useVirtualThreads(true).build())
                        .loadCredentials(properties)
                        .agentProviders(List.of(new LangChain4jProvider()))
                        .reviewHandler(reviewHandler)
                        .actionExecutor(actionExecutor)
                        .build();

        logger.info("Configured HensuEnvironment with CLIActionExecutor");

        // Register all discovered generic node handlers
        registerGenericHandlers();

        return hensuEnvironment;
    }

    /// Register all CDI-discovered GenericNodeHandler implementations.
    private void registerGenericHandlers() {
        for (GenericNodeHandler handler : genericNodeHandlers) {
            hensuEnvironment
                    .getNodeExecutorRegistry()
                    .registerGenericHandler(handler.getType(), handler);
            logger.info("Registered generic handler: " + handler.getType());
        }
    }

    /// Create review handler. Always returns CLIReviewManager which checks the
    /// hensu.review.interactive system property at runtime to decide whether to prompt or
    /// auto-approve.
    private ReviewHandler createReviewHandler() {
        return new CLIReviewManager();
    }

    /// Extracts `hensu.credentials.*` and `hensu.stub.enabled` from Quarkus config.
    private Properties extractHensuProperties() {
        Properties properties = new Properties();
        String credentialsPrefix = "hensu.credentials.";
        String stubEnabledKey = "hensu.stub.enabled";

        for (String propertyName : config.getPropertyNames()) {
            if (propertyName.startsWith(credentialsPrefix)) {
                config.getOptionalValue(propertyName, String.class)
                        .ifPresent(value -> properties.setProperty(propertyName, value));
            }
        }

        config.getOptionalValue(stubEnabledKey, String.class)
                .ifPresent(value -> properties.setProperty(stubEnabledKey, value));

        return properties;
    }

    /// Cleanup callback invoked when the application shuts down.
    ///
    /// Closes the HensuEnvironment to release resources (thread pools, connections).
    @PreDestroy
    public void cleanup() {
        if (hensuEnvironment != null) {
            hensuEnvironment.close();
        }
    }
}
