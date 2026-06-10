package io.hensu.cli.daemon;

import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

/// Reads API credentials from {@link DaemonPaths#credentials()}.
///
/// ### File format
/// ```
/// # Hensu credentials
/// GOOGLE_API_KEY=AIza...
/// ANTHROPIC_API_KEY=sk-ant-...
/// ```
/// Keys are bare names (no {@code hensu.credentials.} prefix). The loader applies the prefix
/// so the returned {@link Properties} are ready for
/// {@link io.hensu.core.HensuFactory.Builder#loadCredentials(Properties)}.
///
/// ### Why not Quarkus Config?
/// SmallRye Config resolves env-var placeholders once at startup into a frozen snapshot.
/// A long-running daemon started before an env var is exported will never see it. This
/// loader performs a direct file read at CDI producer time — predictable,
/// synchronous, and daemon-restart-proof.
///
/// @see DaemonPaths#credentials()
/// @see io.hensu.cli.producers.HensuEnvironmentProducer
public final class CredentialsLoader {

    private static final Logger log = Logger.getLogger(CredentialsLoader.class.getName());
    private static final String PREFIX = "hensu.credentials.";

    private CredentialsLoader() {}

    /// Loads credentials from {@link DaemonPaths#credentials()}.
    ///
    /// Returns an empty {@link Properties} if the file does not exist.
    /// Logs a warning (does not throw) if the file exists but cannot be read.
    ///
    /// @return properties keyed as {@code hensu.credentials.<KEY>}, never null
    public static Properties load() {
        Properties props = new Properties();
        try {
            CredentialsStore.ofDefaults()
                    .loadAll()
                    .forEach((k, v) -> props.setProperty(PREFIX + k, v));
        } catch (IOException e) {
            log.warning(
                    "Could not read credentials file "
                            + DaemonPaths.credentials()
                            + ": "
                            + e.getMessage());
        }
        return props;
    }
}
