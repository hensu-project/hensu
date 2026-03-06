package io.hensu.cli.daemon;

import java.nio.file.Path;

/// Well-known filesystem paths used by the Hensu daemon.
///
/// All paths are rooted under {@code $XDG_DATA_HOME/hensu} if the environment
/// variable is set, otherwise under {@code ~/.hensu}.
///
/// ```
/// ~/.hensu/
///   daemon.sock   — Unix domain socket (deleted on clean shutdown)
///   daemon.pid    — PID of the running daemon process
///   daemon.log    — Daemon stdout/stderr when spawned in background
/// ```
public final class DaemonPaths {

    private DaemonPaths() {}

    private static final Path BASE = resolveBase();

    private static Path resolveBase() {
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "hensu");
        }
        return Path.of(System.getProperty("user.home"), ".hensu");
    }

    /// Returns the base directory: {@code ~/.hensu} or {@code $XDG_DATA_HOME/hensu}.
    ///
    /// @return base directory path, never null
    public static Path base() {
        return BASE;
    }

    /// Returns the Unix domain socket path: {@code ~/.hensu/daemon.sock}.
    ///
    /// @return socket path, never null; file may not yet exist
    public static Path socket() {
        return BASE.resolve("daemon.sock");
    }

    /// Returns the PID file path: {@code ~/.hensu/daemon.pid}.
    ///
    /// @return pid file path, never null; file may not yet exist
    public static Path pidFile() {
        return BASE.resolve("daemon.pid");
    }

    /// Returns the daemon log file path: {@code ~/.hensu/daemon.log}.
    ///
    /// @return log file path, never null; file may not yet exist
    public static Path logFile() {
        return BASE.resolve("daemon.log");
    }

    /// Returns the credentials file path: {@code ~/.hensu/credentials}.
    ///
    /// Format: one {@code KEY=VALUE} per line; lines starting with {@code #} are comments.
    /// Keys are bare credential names (e.g. {@code GOOGLE_API_KEY}), without the
    /// {@code hensu.credentials.} prefix — {@link CredentialsLoader} applies the prefix
    /// when loading.
    ///
    /// @return credentials file path, never null; file may not yet exist
    public static Path credentials() {
        return BASE.resolve("credentials");
    }
}
