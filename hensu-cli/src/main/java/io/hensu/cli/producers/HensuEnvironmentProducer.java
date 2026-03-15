package io.hensu.cli.producers;

import io.hensu.adapter.langchain4j.LangChain4jProvider;
import io.hensu.cli.action.CLIActionExecutor;
import io.hensu.cli.daemon.CredentialsLoader;
import io.hensu.cli.review.CLIReviewHandler;
import io.hensu.cli.review.DaemonReviewHandler;
import io.hensu.core.HensuConfig;
import io.hensu.core.HensuEnvironment;
import io.hensu.core.HensuFactory;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.review.ReviewHandler;
import io.hensu.serialization.WorkflowSerializer;
import io.hensu.serialization.plan.JacksonPlanResponseParser;
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
/// Credentials are loaded from `~/.hensu/credentials` — bare `KEY=VALUE` entries read at
/// CDI producer time, bypassing Quarkus Config entirely. This makes credentials available
/// to both the daemon and direct CLI runs regardless of how the process was launched.
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
/// @see DaemonReviewHandler
@ApplicationScoped
public class HensuEnvironmentProducer {

    private static final Logger logger = Logger.getLogger(HensuEnvironmentProducer.class.getName());

    private HensuEnvironment hensuEnvironment;

    @Inject Config config;

    @Inject Instance<GenericNodeHandler> genericNodeHandlers;

    @Inject CLIActionExecutor actionExecutor;

    @Inject DaemonReviewHandler daemonReviewHandler;

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
                        .planResponseParser(
                                new JacksonPlanResponseParser(WorkflowSerializer.createMapper()))
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

    /// Returns the review handler.
    ///
    /// {@link DaemonReviewHandler} handles both modes: daemon executions receive
    /// interactive review frames over the socket; inline executions fall back to
    /// {@link CLIReviewHandler} via {@code System.in}.
    private ReviewHandler createReviewHandler() {
        return daemonReviewHandler;
    }

    /// Builds the credentials and stub-enabled properties.
    ///
    /// Priority order (highest wins):
    /// 1. {@code ~/.hensu/credentials} file — bypasses Quarkus Config entirely, so the
    ///    daemon never suffers from env-var snapshot timing issues at startup.
    /// 2. Quarkus Config / env vars under {@code hensu.credentials.*} (blank values skipped).
    private Properties extractHensuProperties() {
        Properties properties = new Properties();
        String credentialsPrefix = "hensu.credentials.";

        // Layer 1: Quarkus Config (env vars, application.properties) — skip blank values
        for (String propertyName : config.getPropertyNames()) {
            if (propertyName.startsWith(credentialsPrefix)) {
                config.getOptionalValue(propertyName, String.class)
                        .filter(value -> !value.isBlank())
                        .ifPresent(value -> properties.setProperty(propertyName, value));
            }
        }

        // Layer 2: credentials file wins over env vars
        properties.putAll(CredentialsLoader.load());

        config.getOptionalValue("hensu.stub.enabled", String.class)
                .ifPresent(value -> properties.setProperty("hensu.stub.enabled", value));

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
