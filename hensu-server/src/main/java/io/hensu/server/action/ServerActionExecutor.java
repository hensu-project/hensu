package io.hensu.server.action;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.action.ActionHandler;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.template.TemplateResolver;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/// Server implementation of {@link ActionExecutor}.
///
/// Delegates tool calls to registered {@link ActionHandler} implementations
/// (e.g., {@link io.hensu.server.mcp.McpSidecar} for MCP protocol).
///
/// ### Server Mode Restrictions
/// - **Send actions**: Supported - forwards to registered handlers
/// - **Execute actions**: Not supported - server doesn't run local commands
///
/// All tool execution happens via MCP protocol to customer's external servers.
///
/// @implNote Thread-safe. Uses ConcurrentHashMap for handler storage.
/// @see io.hensu.server.mcp.McpSidecar
@ApplicationScoped
public class ServerActionExecutor implements ActionExecutor {

    private static final Logger LOG = Logger.getLogger(ServerActionExecutor.class);

    private final TemplateResolver templateResolver = new SimpleTemplateResolver();
    private final Map<String, ActionHandler> handlers = new ConcurrentHashMap<>();

    @Override
    public void setWorkingDirectory(Path workingDirectory) {
        // No-op: server mode doesn't use filesystem-based configuration
    }

    @Override
    public void registerHandler(ActionHandler handler) {
        String handlerId = handler.getHandlerId();
        handlers.put(handlerId, handler);
        LOG.infov("Registered action handler: {0}", handlerId);
    }

    @Override
    public Optional<ActionHandler> getHandler(String handlerId) {
        return Optional.ofNullable(handlers.get(handlerId));
    }

    @Override
    public ActionResult execute(Action action, Map<String, Object> context) {
        return switch (action) {
            case Action.Send send -> executeSend(send, context);
            case Action.Execute exec -> {
                LOG.warnv(
                        "Command execution not supported in server mode: {0}", exec.getCommandId());
                yield ActionResult.failure(
                        "Server mode does not support local command execution. "
                                + "Use MCP tool calls instead.");
            }
        };
    }

    private ActionResult executeSend(Action.Send send, Map<String, Object> context) {
        String handlerId = send.getHandlerId();

        ActionHandler handler = handlers.get(handlerId);
        if (handler == null) {
            LOG.warnv(
                    "Action handler not found: {0}. Registered: {1}", handlerId, handlers.keySet());
            return ActionResult.failure("Action handler not found: " + handlerId);
        }

        LOG.debugv("Executing send action via handler: {0}", handlerId);

        Map<String, Object> resolvedPayload = resolvePayload(send.getPayload(), context);
        return handler.execute(resolvedPayload, context);
    }

    private Map<String, Object> resolvePayload(
            Map<String, Object> payload, Map<String, Object> context) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String stringValue) {
                resolved.put(entry.getKey(), templateResolver.resolve(stringValue, context));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }
}
