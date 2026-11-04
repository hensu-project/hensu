package io.hensu.core.tool;

import java.util.Map;

/// Invocation half of the tool seam, consumed by the agent tool loop.
///
/// The loop depends on this narrow interface rather than on {@link ToolRegistry}
/// so that discovery and invocation stay separable: a registry answers "which
/// tools exist", an invoker answers "run this one". {@link ToolRouter} implements
/// both and is the invoker wired into execution contexts.
///
/// @see ToolProvider for the runtime integrations behind an invoker
public interface ToolInvoker {

    /// Invokes a tool and returns its result.
    ///
    /// @param call the invocation: its key, tool name and arguments, not null
    /// @param context the workflow state context, not null (may be empty)
    /// @return the invocation result, never null; unknown tools yield a failure result
    /// @throws io.hensu.core.execution.RunAbortedException if the run must stop
    ToolCallResult call(ToolCall call, Map<String, Object> context);
}
