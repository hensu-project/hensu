package io.hensu.cli.sandbox;

import io.hensu.core.execution.action.SandboxPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Linux containment through bubblewrap.
///
/// The policy compiles to kernel mechanisms rather than to checks: mount
/// namespaces decide what the filesystem looks like, a network namespace decides
/// whether anything is reachable, and a PID namespace with `--die-with-parent`
/// decides how long the whole process tree may live. None of them inspect what
/// the command does, which is why a script that shells out, forks, or downloads
/// an interpreter hits the same wall as a direct attempt.
///
/// ### The skeleton is a constant
/// Every sandbox gets the same read-only host skeleton – `/usr`, `/lib`,
/// `/lib64`, `/bin`, `/sbin`, `/etc`, a fresh `/proc`, a minimal `/dev` and a
/// private `/tmp`. Without it nothing dynamically linked could start at all. It
/// is deliberately not per-command policy: a command can narrow what it writes,
/// never widen what it sees.
///
/// ### Ordering carries a guarantee
/// Bubblewrap applies mounts in order and the last one wins, so the command's own
/// `commands.yaml` and `mcp.yaml` are re-bound read-only *after* the `write:`
/// subtrees. A command granted `write: ["."]` therefore still cannot rewrite the
/// catalog that decides what it is allowed to run.
///
/// @implNote **Immutable after construction. Thread-safe.** The probe runs once in
/// the constructor and {@link #wrap} derives everything from its arguments.
/// @see SandboxLauncher for the contract and the macOS counterpart
public final class BwrapSandboxLauncher implements SandboxLauncher {

    /// Name recorded for this backend in prepared commands and audit events.
    public static final String BACKEND = "bwrap";

    private static final String BWRAP = "bwrap";
    private static final List<String> READ_ONLY_ROOTS =
            List.of("/usr", "/lib", "/lib64", "/bin", "/sbin", "/etc");

    private final List<String> skeleton;
    private final boolean available;
    private final String unavailabilityReason;

    /// Creates the backend and probes it once.
    ///
    /// @apiNote **Side effects**: runs `bwrap` against a trivial command under the
    /// full skeleton. A refusal is recorded, never thrown – the caller decides what
    /// an unavailable sandbox means for a given command.
    public BwrapSandboxLauncher() {
        this.skeleton = skeleton();
        List<String> probe = new ArrayList<>();
        probe.add(BWRAP);
        probe.addAll(skeleton);
        probe.add("--unshare-pid");
        probe.add("--die-with-parent");
        probe.add("--");
        probe.add("/bin/true");
        ProcessProbe.Result result = ProcessProbe.run(probe);
        this.available = result.ok();
        this.unavailabilityReason = result.diagnostics();
    }

    private static List<String> skeleton() {
        List<String> mounts = new ArrayList<>();
        for (String root : READ_ONLY_ROOTS) {
            if (Files.exists(Path.of(root))) {
                mounts.add("--ro-bind");
                mounts.add(root);
                mounts.add(root);
            }
        }
        mounts.add("--proc");
        mounts.add("/proc");
        mounts.add("--dev");
        mounts.add("/dev");
        mounts.add("--tmpfs");
        mounts.add("/tmp");
        return List.copyOf(mounts);
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
                    "bwrap is unavailable on this host: " + unavailabilityReason);
        }
        List<String> wrapped = new ArrayList<>();
        wrapped.add(BWRAP);
        wrapped.addAll(skeleton);

        bind(wrapped, "--ro-bind", workingDir, workingDir);
        for (String writePath : policy.writePaths()) {
            Path target = workingDir.resolve(writePath).toAbsolutePath().normalize();
            bind(wrapped, "--bind", target, target);
        }
        for (String cachePath : policy.cachePaths()) {
            Path target = Path.of(cachePath).toAbsolutePath().normalize();
            bind(wrapped, "--bind", target, target);
        }
        bind(wrapped, "--bind", privateHome, privateHome);

        wrapped.add("--unshare-pid");
        wrapped.add("--die-with-parent");
        wrapped.add("--new-session");
        wrapped.add("--cap-drop");
        wrapped.add("ALL");
        if (!policy.network()) {
            wrapped.add("--unshare-net");
        }
        wrapped.add("--chdir");
        wrapped.add(workingDir.toString());

        // Last mount wins: re-mask the catalog after any write: subtree covering it.
        for (String configFile : CommandRunner.PROTECTED_CONFIG_FILES) {
            Path config = workingDir.resolve(configFile);
            if (Files.exists(config)) {
                bind(wrapped, "--ro-bind", config, config);
            }
        }

        wrapped.add("--");
        wrapped.addAll(argv);
        return wrapped;
    }

    private static void bind(List<String> argv, String flag, Path source, Path destination) {
        argv.add(flag);
        argv.add(source.toString());
        argv.add(destination.toString());
    }
}
