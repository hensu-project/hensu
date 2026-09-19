package io.hensu.core.tool;

/// A {@link ToolProvider} that adds behaviour in front of another one.
///
/// Decorating is how policy is added to the tool surface without editing the engine or
/// the sources it routes to — a deployment wraps what it discovers and the router never
/// learns about approval, budgets or anything else layered on later.
///
/// What the wrapper must not do is disguise the provider underneath. The router decides
/// precedence from whether a provider is a {@link BuiltInToolProvider}, and names the
/// providers involved when two of them publish the same tool. A decorator that answered
/// only for itself would put every built-in into the configured band, turning an MCP
/// server that publishes `read_file` from a warning into a refusal to start, and would
/// reduce every duplicate-name error to the decorator's own class name twice.
/// Implementing this interface is what keeps both readings honest.
///
/// ### Contracts
/// - **Invariant**: {@link #delegate()} returns the same instance for the life of the
///   decorator
/// - **Invariant**: the chain is finite — a decorator never reaches itself through
///   repeated {@link #delegate()} calls
///
/// @see ToolRouter#unwrap for where the chain is walked
public interface ToolProviderDecorator extends ToolProvider {

    /// Returns the provider this one wraps.
    ///
    /// @return the wrapped provider, never null
    ToolProvider delegate();
}
