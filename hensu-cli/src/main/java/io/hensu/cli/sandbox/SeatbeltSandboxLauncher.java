package io.hensu.cli.sandbox;

import io.hensu.core.execution.action.SandboxPolicy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// macOS containment through Seatbelt.
///
/// The policy compiles to an SBPL profile that `sandbox-exec` applies to the
/// process and everything it spawns: the host filesystem stays readable, writes
/// are confined to the declared subtrees, and the network is denied unless the
/// command asked for it. As on Linux the mechanism never inspects the command –
/// it constrains the kernel's answers, so children and scripts are covered
/// without being understood.
///
/// ### What differs from the Linux backend
/// - No mount namespace, so there is no skeleton to build: system paths are
///   allowed for reading instead of being bound.
/// - No PID namespace, so lifetime is not enforced here. {@link CommandRunner}
///   supervises the tree and sweeps `ProcessHandle.descendants()` on timeout.
/// - SBPL resolves by last match, so the `deny file-write*` on the catalog files
///   is emitted after the `write:` allowances and therefore wins – the same
///   guarantee bubblewrap gets from mount ordering.
///
/// `sandbox-exec` is deprecated by Apple and has been for years, while remaining
/// load-bearing for Chromium and Bazel. The deprecation risk is confined to this
/// one class: a replacement is a new {@link SandboxLauncher}, not a change to any
/// caller.
///
/// @implNote **Immutable after construction. Thread-safe.** The probe runs once in
/// the constructor; each {@link #wrap} call writes its own profile beside the
/// call's private home, which the caller deletes with it.
/// @see SandboxLauncher for the contract and the Linux counterpart
public final class SeatbeltSandboxLauncher implements SandboxLauncher {

    /// Name recorded for this backend in prepared commands and audit events.
    public static final String BACKEND = "seatbelt";

    private static final String SANDBOX_EXEC = "/usr/bin/sandbox-exec";
    private static final List<String> READABLE_SYSTEM_PATHS =
            List.of("/usr", "/bin", "/sbin", "/System", "/Library", "/private/etc", "/dev");
    private static final String PROFILE_FILE = "sandbox.sbpl";

    private final boolean available;
    private final String unavailabilityReason;

    /// Creates the backend and probes it once.
    ///
    /// @apiNote **Side effects**: compiles a minimal profile into a temporary file
    /// and runs `/usr/bin/true` under it, then deletes the file. A refusal is
    /// recorded, never thrown.
    public SeatbeltSandboxLauncher() {
        ProcessProbe.Result result = probe();
        this.available = result.ok();
        this.unavailabilityReason = result.diagnostics();
    }

    private static ProcessProbe.Result probe() {
        Path profile = null;
        try {
            profile = Files.createTempFile("hensu-sandbox-probe", ".sbpl");
            Files.writeString(
                    profile,
                    """
                    (version 1)
                    (deny default)
                    (allow process-exec*)
                    (allow process-fork)
                    (allow file-read*)
                    (allow sysctl-read)
                    (allow mach-lookup)
                    """,
                    StandardCharsets.UTF_8);
            return ProcessProbe.run(
                    List.of(SANDBOX_EXEC, "-f", profile.toString(), "--", "/usr/bin/true"));
        } catch (IOException e) {
            return new ProcessProbe.Result(
                    false, "could not write a Seatbelt probe profile: " + e.getMessage());
        } finally {
            deleteQuietly(profile);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover probe profile in the system temp directory is harmless.
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String unavailabilityReason() {
        return unavailabilityReason;
    }

    @Override
    public String backendName() {
        return BACKEND;
    }

    @Override
    public List<String> wrap(
            List<String> argv, SandboxPolicy policy, Path workingDir, Path privateHome) {
        if (!available) {
            throw new UnsupportedOperationException(
                    "sandbox-exec is unavailable on this host: " + unavailabilityReason);
        }
        Path callDirectory = privateHome.getParent();
        Path profile = callDirectory.resolve(PROFILE_FILE);
        try {
            Files.writeString(
                    profile, buildProfile(policy, workingDir, privateHome), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the Seatbelt profile for this call", e);
        }

        List<String> wrapped =
                new ArrayList<>(List.of(SANDBOX_EXEC, "-f", profile.toString(), "--"));
        wrapped.addAll(argv);
        return wrapped;
    }

    /// Renders the policy as an SBPL profile.
    ///
    /// @param policy the containment scope, not null
    /// @param workingDir the directory the command runs in, not null
    /// @param privateHome the per-call home directory, not null
    /// @return the profile text, never null
    String buildProfile(SandboxPolicy policy, Path workingDir, Path privateHome) {
        StringBuilder profile = new StringBuilder();
        profile.append("(version 1)\n");
        profile.append("(deny default)\n");
        profile.append("(allow process-exec*)\n");
        profile.append("(allow process-fork)\n");
        profile.append("(allow sysctl-read)\n");
        profile.append("(allow mach-lookup)\n");

        profile.append("(allow file-read*");
        for (String path : READABLE_SYSTEM_PATHS) {
            profile.append(" (subpath ").append(quote(path)).append(')');
        }
        profile.append(" (subpath ").append(quote(workingDir.toString())).append(")");
        profile.append(" (subpath ").append(quote(privateHome.toString())).append("))\n");

        profile.append("(allow file-write*");
        profile.append(" (literal \"/dev/null\")");
        profile.append(" (subpath ").append(quote(privateHome.toString())).append(')');
        for (String writePath : policy.writePaths()) {
            Path target = workingDir.resolve(writePath).toAbsolutePath().normalize();
            profile.append(" (subpath ").append(quote(target.toString())).append(')');
        }
        for (String cachePath : policy.cachePaths()) {
            Path target = Path.of(cachePath).toAbsolutePath().normalize();
            profile.append(" (subpath ").append(quote(target.toString())).append(')');
        }
        profile.append(")\n");

        if (policy.network()) {
            profile.append("(allow network*)\n");
        }

        // SBPL resolves by last match, so this deny overrides any write: subtree
        // that happens to cover the catalog the command was selected from.
        profile.append("(deny file-write*");
        for (String configFile : CommandRunner.PROTECTED_CONFIG_FILES) {
            profile.append(" (literal ")
                    .append(quote(workingDir.resolve(configFile).toString()))
                    .append(')');
        }
        profile.append(")\n");
        return profile.toString();
    }

    private static String quote(String path) {
        return '"' + path.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
