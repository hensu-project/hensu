package io.hensu.core.execution.action;

import java.nio.file.Path;
import java.util.List;

/// The configuration files a run may never rewrite, named once for every layer
/// that has to protect them.
///
/// `commands.yaml` and `mcp.yaml` decide what a deployment may execute and which
/// servers it may talk to. A run that can edit them can grant itself anything –
/// an added entry carries its own `network:` flag and its own `cache:` mounts –
/// so the allowlist would be advisory rather than binding.
///
/// Two independent paths reach these files and both have to refuse. The sandbox
/// launchers re-bind them read-only *after* the declared `write:` subtrees, so a
/// command granted `write: ["."]` still cannot touch them. The built-in file
/// tools run outside the sandbox entirely and would hand that capability
/// straight back, so they refuse the same names. The list lives here rather than
/// in either of them, because a protection expressed twice is a protection that
/// will eventually be expressed once.
///
/// @implNote **Immutable after construction.** Constants only; safe to share
/// across Virtual Threads.
/// @see CommandRegistry for the catalog `commands.yaml` compiles into
public final class ProtectedConfigFiles {

    /// File names refused to every agent-reachable write path.
    ///
    /// Names, not paths: the files are looked for in the working directory the
    /// run was given, so a deployment that moves its project moves its
    /// protection with it.
    public static final List<String> NAMES = List.of("commands.yaml", "mcp.yaml");

    private ProtectedConfigFiles() {
        // Utility class
    }

    /// Returns whether a path names one of the protected files in a given root.
    ///
    /// Both arguments are compared as given, so callers pass paths they have
    /// already resolved – this decides policy, it does not do containment.
    ///
    /// @param root the working directory the run is confined to, not null
    /// @param candidate the resolved path being written to, not null
    /// @return true if writing to the candidate would rewrite a protected file
    public static boolean isProtected(Path root, Path candidate) {
        return NAMES.stream().map(root::resolve).anyMatch(candidate::equals);
    }
}
