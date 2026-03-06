package io.hensu.cli.daemon;

import java.nio.file.Files;
import java.nio.file.Path;

/// Resolves absolute paths to system binaries used by the daemon subsystem.
///
/// Each method probes a small set of well-known locations and returns the first
/// executable path found, falling back to the bare command name (for {@code systemctl}
/// and {@code launchctl}) or {@code null} (for {@code systemd-notify}, where absence
/// means the platform does not support sd_notify).
///
/// Using absolute paths in {@link ProcessBuilder} calls prevents PATH-injection attacks
/// (CWE-78 / CodeQL {@code java/exec-tainted-cmd}).
public final class SystemBinaries {

    private SystemBinaries() {}

    /// Resolves the absolute path to {@code systemctl}.
    ///
    /// @return absolute path if found in a standard location, otherwise {@code "systemctl"}
    public static String systemctl() {
        for (String c :
                new String[] {"/usr/bin/systemctl", "/bin/systemctl", "/usr/local/bin/systemctl"}) {
            Path p = Path.of(c);
            if (Files.isExecutable(p)) return p.toString();
        }
        return "systemctl";
    }

    /// Resolves the absolute path to {@code launchctl}.
    ///
    /// @return absolute path if found in a standard location, otherwise {@code "launchctl"}
    public static String launchctl() {
        for (String c : new String[] {"/bin/launchctl", "/usr/bin/launchctl"}) {
            Path p = Path.of(c);
            if (Files.isExecutable(p)) return p.toString();
        }
        return "launchctl";
    }

    /// Resolves the absolute path to {@code systemd-notify}.
    ///
    /// @return absolute path if found, or {@code null} if not available on this platform
    public static String systemdNotify() {
        for (String c : new String[] {"/usr/bin/systemd-notify", "/bin/systemd-notify"}) {
            Path p = Path.of(c);
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }
}
