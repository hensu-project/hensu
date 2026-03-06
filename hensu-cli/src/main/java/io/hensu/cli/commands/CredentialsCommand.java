package io.hensu.cli.commands;

import io.hensu.cli.daemon.DaemonClient;
import io.hensu.cli.daemon.DaemonPaths;
import io.hensu.cli.ui.AnsiStyles;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// Manages API credentials stored in {@link DaemonPaths#credentials()}.
///
/// Credentials are kept in {@code ~/.hensu/credentials} as bare {@code KEY=VALUE} lines.
/// This command group provides discoverable, safe alternatives to manual file editing:
/// values are never exposed in shell history, and the file is always written with
/// {@code 0600} permissions.
///
/// ### Subcommands
/// ```
/// hensu credentials set <KEY>    — Prompt for a masked value and persist it
/// hensu credentials list         — Show configured key names (values hidden)
/// hensu credentials unset <KEY>  — Remove a key from the credentials file
/// ```
///
/// ### Daemon restart
/// When the daemon is running at the time of a write, all subcommands print a restart
/// hint — credentials are read once at daemon startup so a restart is required to pick
/// up changes.
///
/// @see DaemonPaths#credentials()
/// @see io.hensu.cli.daemon.CredentialsLoader
@Command(
        name = "credentials",
        description = "Manage API credentials stored in ~/.hensu/credentials",
        subcommands = {
            CredentialsCommand.Set.class,
            CredentialsCommand.Keys.class,
            CredentialsCommand.Unset.class
        })
public class CredentialsCommand extends HensuCommand {

    @Override
    protected void execute() {
        // No-op: picocli prints usage when no subcommand given
    }

    // — set ————————————————————————————————————————————————————————————————

    /// Sets or updates a single credential.
    ///
    /// When a TTY is available the value is read via a masked prompt so it never
    /// appears in shell history. Pass {@code --stdin} in non-interactive environments
    /// (CI pipelines, scripts) to read the value from standard input instead.
    ///
    /// If the key already exists in the file the existing line is replaced in-place,
    /// preserving surrounding comments and ordering.
    ///
    /// ### Example
    /// ```
    /// hensu credentials set ANTHROPIC_API_KEY
    /// > Enter value for ANTHROPIC_API_KEY: ******* (hidden)
    /// ✓ ANTHROPIC_API_KEY saved to ~/.hensu/credentials
    /// ```
    @Command(
            name = "set",
            description =
                    "Set a credential key (prompts for masked value; use --stdin for scripts)")
    static class Set extends HensuCommand {

        @Parameters(index = "0", description = "Credential key name, e.g. ANTHROPIC_API_KEY")
        private String key;

        @Option(
                names = {"--stdin"},
                description = "Read value from stdin instead of an interactive prompt")
        private boolean stdin = false;

        // Package-private for testing; null falls back to DaemonPaths.credentials()
        Path credentialsPath;

        @Override
        protected void execute() {
            AnsiStyles styles = AnsiStyles.of(true);
            String value = readValue(styles);
            if (value == null) return;

            try {
                writeCredential(key.strip(), value);
                System.out.println(
                        styles.checkmark()
                                + " "
                                + styles.bold(key)
                                + " saved to "
                                + styles.gray(DaemonPaths.credentials().toString()));
                printDaemonRestartHint(styles);
            } catch (IOException e) {
                System.err.println(styles.error("Failed to write credentials: " + e.getMessage()));
            }
        }

        private String readValue(AnsiStyles styles) {
            if (stdin) {
                try (var reader =
                        new BufferedReader(
                                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line == null || line.isBlank()) {
                        System.err.println(styles.error("No value provided via stdin."));
                        return null;
                    }
                    return line.strip();
                } catch (IOException e) {
                    System.err.println(
                            styles.error("Failed to read from stdin: " + e.getMessage()));
                    return null;
                }
            }

            var console = System.console();
            if (console == null) {
                System.err.println(
                        styles.error("No TTY available — use --stdin to pipe the value."));
                return null;
            }
            System.out.print(styles.bold("Enter value for " + key + ": "));
            System.out.flush();
            char[] chars = console.readPassword();
            if (chars == null || chars.length == 0) {
                System.err.println(styles.error("No value provided."));
                return null;
            }
            return new String(chars).strip();
        }

        private void writeCredential(String key, String value) throws IOException {
            Path file = credentialsPath != null ? credentialsPath : DaemonPaths.credentials();
            Files.createDirectories(file.getParent());

            List<String> lines =
                    Files.exists(file)
                            ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                            : new ArrayList<>();

            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.startsWith("#") && !line.isBlank()) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && line.substring(0, eq).strip().equals(key)) {
                        lines.set(i, key + "=" + value);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                lines.add(key + "=" + value);
            }

            Files.write(file, lines, StandardCharsets.UTF_8);
            applyRestrictedPermissions(file);
        }
    }

    // — list ———————————————————————————————————————————————————————————————

    /// Lists the key names of all configured credentials with values masked.
    ///
    /// Comments and blank lines in the credentials file are skipped; only active
    /// {@code KEY=VALUE} entries are shown. Values are never printed.
    ///
    /// ### Example output
    /// ```
    /// Configured credentials  (~/.hensu/credentials)
    ///   ANTHROPIC_API_KEY   ***
    ///   GOOGLE_API_KEY      ***
    /// ```
    @Command(name = "list", description = "List configured credential keys (values masked)")
    static class Keys extends HensuCommand {

        // Package-private for testing; null falls back to DaemonPaths.credentials()
        Path credentialsPath;

        @Override
        protected void execute() {
            AnsiStyles styles = AnsiStyles.of(true);
            Path file = credentialsPath != null ? credentialsPath : DaemonPaths.credentials();

            if (!Files.exists(file)) {
                System.out.println(styles.gray("No credentials file at " + file));
                System.out.println(
                        styles.gray("Use `hensu credentials set <KEY>` to add credentials."));
                return;
            }

            try {
                List<String> keys =
                        Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                                .filter(line -> line.indexOf('=') > 0)
                                .map(
                                        line -> {
                                            int eq = line.indexOf('=');
                                            return line.substring(0, eq).strip();
                                        })
                                .toList();

                if (keys.isEmpty()) {
                    System.out.println(styles.gray("No credentials configured."));
                    return;
                }

                System.out.println(
                        styles.bold("Configured credentials")
                                + "  "
                                + styles.gray(file.toString()));
                for (String k : keys) {
                    System.out.println("  " + styles.accent(k) + "  " + styles.gray("***"));
                }
            } catch (IOException e) {
                System.err.println(styles.error("Failed to read credentials: " + e.getMessage()));
            }
        }
    }

    // — unset ——————————————————————————————————————————————————————————————

    /// Removes a single credential key from the credentials file.
    ///
    /// Comment lines and all other entries are preserved. If the key is not present
    /// a warning is printed and the file is left unchanged.
    ///
    /// ### Example
    /// ```
    /// hensu credentials unset OPENAI_API_KEY
    /// ✓ OPENAI_API_KEY removed.
    /// ⚠  Daemon is running — restart to apply: hensu daemon stop && hensu daemon start
    /// ```
    @Command(name = "unset", description = "Remove a credential key")
    static class Unset extends HensuCommand {

        @Parameters(index = "0", description = "Credential key name to remove")
        private String key;

        // Package-private for testing; null falls back to DaemonPaths.credentials()
        Path credentialsPath;

        @Override
        protected void execute() {
            AnsiStyles styles = AnsiStyles.of(true);
            Path file = credentialsPath != null ? credentialsPath : DaemonPaths.credentials();

            if (!Files.exists(file)) {
                System.out.println(styles.gray("No credentials file found — nothing to remove."));
                return;
            }

            try {
                List<String> original = Files.readAllLines(file, StandardCharsets.UTF_8);
                List<String> filtered =
                        original.stream()
                                .filter(
                                        line -> {
                                            if (line.isBlank() || line.startsWith("#")) return true;
                                            int eq = line.indexOf('=');
                                            return eq <= 0
                                                    || !line.substring(0, eq)
                                                            .strip()
                                                            .equals(key.strip());
                                        })
                                .toList();

                if (filtered.size() == original.size()) {
                    System.out.println(
                            styles.warn("Key " + styles.bold(key) + " not found in credentials."));
                    return;
                }

                Files.write(file, filtered, StandardCharsets.UTF_8);
                System.out.println(styles.checkmark() + " " + styles.bold(key) + " removed.");
                printDaemonRestartHint(styles);
            } catch (IOException e) {
                System.err.println(styles.error("Failed to update credentials: " + e.getMessage()));
            }
        }
    }

    // — shared helpers —————————————————————————————————————————————————————

    /// Prints a daemon restart hint when the daemon is currently running.
    ///
    /// Credentials are read once at daemon startup; a restart is required to pick
    /// up file changes made while the daemon is live.
    ///
    /// @param styles ANSI style helper, not null
    static void printDaemonRestartHint(AnsiStyles styles) {
        if (DaemonClient.isAlive()) {
            System.out.println(styles.warn("  Daemon is running — restart to apply changes:"));
            System.out.println(styles.gray("  hensu daemon stop && hensu daemon start"));
        }
    }

    /// Sets file permissions to {@code 0600} (owner read/write only) when the
    /// filesystem supports POSIX attributes.
    ///
    /// Silently skipped on non-POSIX filesystems (e.g. Windows NTFS).
    ///
    /// @param file path to the credentials file, not null; must already exist
    static void applyRestrictedPermissions(Path file) {
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (Exception ignored) {
        }
    }
}
