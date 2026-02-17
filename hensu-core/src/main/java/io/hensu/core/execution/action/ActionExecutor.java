package io.hensu.core.execution.action;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/// Interface for executing workflow actions.
///
/// Implementations handle the actual execution of actions like:
///
/// - Sending data to external systems via registered {@link ActionHandler} implementations
/// - Running local commands via {@link CommandRegistry}
///
/// ### Action Handler Registration
/// Send actions use a handler pattern similar to
/// {@link io.hensu.core.execution.executor.GenericNodeHandler}.
/// Users implement {@link ActionHandler} to encapsulate authentication, endpoints, and
/// request logic, then register handlers by ID. Workflows reference handlers by ID only.
///
/// {@snippet :
/// // Register handler
/// actionExecutor.registerHandler(new SlackHandler(webhookUrl));
///
/// // DSL usage: send("slack", mapOf("message" to "Done"))
/// }
///
/// The core module provides this interface; implementations live in hensu-cli or other modules
/// with appropriate dependencies.
///
/// @see ActionHandler
public interface ActionExecutor {

    /// Execute an action.
    ///
    /// @param action The action to execute
    /// @param context Workflow context for template resolution
    /// @return Result of the action execution
    ActionResult execute(Action action, Map<String, Object> context);

    /// Check if this executor supports the given action type.
    ///
    /// @param action The action to check
    /// @return true if this executor can handle the action
    default boolean supports(Action action) {
        return true;
    }

    /// Configure the working directory for this executor. Used to load command registries and
    /// resolve relative paths.
    ///
    /// @param workingDirectory The working directory path
    default void setWorkingDirectory(Path workingDirectory) {
        // Default no-op; implementations can override
    }

    // === Action Handler API ===

    /// Register an action handler by its handler ID.
    ///
    /// The handler's {@link ActionHandler#getHandlerId()} determines the registration key.
    /// When an `Action.Send` is executed, the matching handler is invoked.
    ///
    /// @param handler the action handler implementation, not null
    /// @see ActionHandler
    default void registerHandler(ActionHandler handler) {
        // Default no-op; implementations should override
    }

    /// Get the action handler for the given handler ID.
    ///
    /// @param handlerId the handler identifier, not null
    /// @return Optional containing the handler if registered
    default Optional<ActionHandler> getHandler(String handlerId) {
        return Optional.empty();
    }

    /// Check if an action handler is registered for the given handler ID.
    ///
    /// @param handlerId the handler identifier, not null
    /// @return true if a handler is registered
    default boolean hasHandler(String handlerId) {
        return getHandler(handlerId).isPresent();
    }

    /// Result of action execution.
    record ActionResult(boolean success, String message, Object output, Throwable error) {
        public static ActionResult success(String message) {
            return new ActionResult(true, message, null, null);
        }

        public static ActionResult success(String message, Object output) {
            return new ActionResult(true, message, output, null);
        }

        public static ActionResult failure(String message) {
            return new ActionResult(false, message, null, null);
        }

        public static ActionResult failure(String message, Throwable error) {
            return new ActionResult(false, message, null, error);
        }
    }
}
