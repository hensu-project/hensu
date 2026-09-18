package io.hensu.core.execution.action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The environment a granted process starts from, named once for every launcher.
///
/// A process the engine starts on an operator's behalf inherits nothing by
/// default. The host environment of a developer machine or a CI runner carries
/// API keys, cloud credentials and session tokens, and a command that never
/// needed them should not be able to read them, let alone send them somewhere.
/// The child therefore begins from an empty map and receives only what is named
/// here plus what the entry declared for itself.
///
/// `HOME` is deliberately absent from the allowlist. Every launch gets its own
/// private home, so inheriting the operator's would defeat the isolation rather
/// than complete it.
///
/// The same rule governs command execution and MCP server launches, which is why
/// it lives in core rather than in either of them.
///
/// @implNote **Immutable after construction.** Constants and pure functions
/// only; safe to share across Virtual Threads.
/// @see CommandDefinition#environment() for what an entry adds on top
public final class HermeticEnvironment {

    /// Host variables a launched process is allowed to inherit.
    ///
    /// Locale and time zone so output is readable, `PATH` so an interpreter can
    /// find its own helpers, and `TMPDIR` so scratch files land where the
    /// platform expects. Nothing that identifies or authenticates the operator.
    public static final List<String> PASSTHROUGH =
            List.of("PATH", "LANG", "LC_ALL", "TZ", "TMPDIR");

    private HermeticEnvironment() {
        // Utility class
    }

    /// Copies the allowlisted variables out of a host environment.
    ///
    /// @param host the environment to draw from, not null (usually `System.getenv()`)
    /// @return a mutable map holding only the allowlisted entries that were
    ///     present, in allowlist order, never null
    public static Map<String, String> base(Map<String, String> host) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String key : PASSTHROUGH) {
            String value = host.get(key);
            if (value != null) {
                environment.put(key, value);
            }
        }
        return environment;
    }
}
