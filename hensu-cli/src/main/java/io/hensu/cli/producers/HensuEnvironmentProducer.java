package io.hensu.cli.producers;

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
import java.util.Properties;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.Config;

/// CDI producer for Hensu components. Credentials are discovered from:
///
/// - **Environment variables** matching patterns: *_API_KEY, *_KEY, *_SECRET, *_TOKEN
/// - **Application properties** under `hensu.credentials.*`.
/// Example `hensu.credentials.ANTHROPIC_API_KEY=`
///
/// ### Properties take precedence over environment variables.
///
/// ### Human review mode can be enabled via:
///
/// - **Property**: hensu.review.interactive=true
/// - **System property**: -Dhensu.review.interactive=true
@ApplicationScoped
public class HensuEnvironmentProducer {

    private static final Logger logger = Logger.getLogger(HensuEnvironmentProducer.class.getName());

    private HensuEnvironment hensuEnvironment;

    @Inject Config config;

    @Inject Instance<GenericNodeHandler> genericNodeHandlers;

    @Inject CLIActionExecutor actionExecutor;

    @Produces
    @ApplicationScoped
    public HensuEnvironment hensuEnvironment() {
        Properties properties = extractHensuProperties();
        ReviewHandler reviewHandler = createReviewHandler();

        hensuEnvironment =
                HensuFactory.builder()
                        .config(HensuConfig.builder().useVirtualThreads(true).build())
                        .loadCredentials(properties)
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

    /// Extract all hensu.* properties from Quarkus config. Includes credentials
    /// (hensu.credentials.*) and stub mode (hensu.stub.enabled).
    private Properties extractHensuProperties() {
        Properties properties = new Properties();
        String credentialsPrefix = "hensu.credentials.";
        String stubEnabledKey = "hensu.stub.enabled";

        for (String propertyName : config.getPropertyNames()) {
            // Extract credentials
            if (propertyName.startsWith(credentialsPrefix)) {
                config.getOptionalValue(propertyName, String.class)
                        .ifPresent(value -> properties.setProperty(propertyName, value));
            }
        }

        // Extract stub mode setting
        config.getOptionalValue(stubEnabledKey, String.class)
                .ifPresent(value -> properties.setProperty(stubEnabledKey, value));

        return properties;
    }

    @PreDestroy
    public void cleanup() {
        if (hensuEnvironment != null) {
            hensuEnvironment.close();
        }
    }
}
