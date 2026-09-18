package io.hensu.core.execution.action;

import java.util.List;

/// Containment scope a command runs under, declared per command in `commands.yaml`.
///
/// The policy is the single source each sandbox backend compiles to its own
/// mechanism – bubblewrap flags on Linux, an SBPL profile on macOS – so a
/// command describes what it needs once and the guarantee does not depend on
/// which host it lands on.
///
/// ### Defaults are the closed ones
/// A command that declares no `sandbox:` block gets {@link #restrictive()}: no
/// network, no writable path beyond the private home the runner creates for the
/// call, and no host cache. Widening is always explicit in configuration.
///
/// ### `network: false` is stricter than it reads
/// Disabling the network puts the process in a namespace holding only loopback.
/// That removes the internet, and with it every host-local service: a database
/// on `localhost`, a Testcontainers daemon, an HTTP proxy. A command that needs
/// any of those must declare `network: true`; there is no middle setting.
///
/// ### `write:` versus `cache:`
/// Both widen the filesystem, and they are deliberately not interchangeable.
/// `write:` entries are working-directory-relative subtrees – the command's own
/// output – and are rejected at load if they escape it. `cache:` entries are
/// absolute host directories outside the working directory, such as a Gradle or
/// npm cache, which exist so a hermetic private home does not force every build
/// to re-download the world. Because a cache mount reaches outside the project,
/// it is accepted only from human-authored configuration, never derived from an
/// agent argument.
///
/// @param network whether the process may reach the network
/// @param writePaths working-directory-relative subtrees the process may write,
///     not null (may be empty)
/// @param cachePaths absolute host directories bound read-write for caching,
///     not null (may be empty)
/// @implNote **Immutable after construction.** Safe to share across Virtual Threads.
/// @see CommandDefinition for the command carrying this policy
public record SandboxPolicy(boolean network, List<String> writePaths, List<String> cachePaths) {

    private static final SandboxPolicy RESTRICTIVE = new SandboxPolicy(false, List.of(), List.of());

    /// Compact constructor taking defensive copies.
    public SandboxPolicy {
        writePaths = writePaths != null ? List.copyOf(writePaths) : List.of();
        cachePaths = cachePaths != null ? List.copyOf(cachePaths) : List.of();
    }

    /// Returns the policy applied to a command that declares no `sandbox:` block.
    ///
    /// @return the closed default policy, never null
    public static SandboxPolicy restrictive() {
        return RESTRICTIVE;
    }
}
