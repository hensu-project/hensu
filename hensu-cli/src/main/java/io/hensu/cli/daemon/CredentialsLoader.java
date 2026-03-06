package io.hensu.cli.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
/// loader performs a direct {@link Files#readAllLines} at CDI producer time — predictable,
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
        var file = DaemonPaths.credentials();
        if (!Files.exists(file)) {
            return props;
        }
        try {
            Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .forEach(
                            line -> {
                                int eq = line.indexOf('=');
                                if (eq > 0) {
                                    String key = line.substring(0, eq).strip();
                                    String value = line.substring(eq + 1).strip();
                                    if (!key.isBlank() && !value.isBlank()) {
                                        props.setProperty(PREFIX + key, value);
                                    }
                                }
                            });
        } catch (IOException e) {
            log.warning("Could not read credentials file " + file + ": " + e.getMessage());
        }
        return props;
    }
}
