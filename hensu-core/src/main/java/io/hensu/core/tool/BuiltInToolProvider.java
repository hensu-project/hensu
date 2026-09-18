package io.hensu.core.tool;

/// Marks a {@link ToolProvider} the engine ships rather than one an operator wired.
///
/// Built-ins are always present. Nobody opted into them, so a name collision
/// between a built-in and a configured source is not a misconfiguration the
/// operator can be asked to fix – it is an upgrade that would otherwise turn a
/// working deployment into a startup failure the moment the engine gained a
/// tool whose name a filesystem MCP server already published.
///
/// {@link ToolRouter} therefore gives providers carrying this marker the lowest
/// precedence: a built-in name already claimed by a configured provider is
/// dropped from the catalog with a warning naming the winner, while two
/// *configured* providers colliding still abort startup as before. The built-ins
/// yield; nobody yields to them.
///
/// @apiNote Implement this only on providers compiled into the engine. A
///     provider contributed by a deployment must not claim the carve-out –
///     doing so turns its own collisions into silence.
/// @see ToolRouter for how precedence is applied
public interface BuiltInToolProvider extends ToolProvider {}
