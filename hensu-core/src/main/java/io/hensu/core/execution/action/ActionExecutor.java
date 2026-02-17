package io.hensu.core.execution.action;

import java.nio.file.Path;
import java.util.Map;

/// Interface for executing workflow actions.
///
/// Implementations handle the actual execution of actions like:
///
/// - Sending notifications
/// - Running local commands (via {@link CommandRegistry})
/// - Making HTTP calls
///
/// The core module provides this interface; implementations live in hensu-cli or other modules
/// with appropriate dependencies.
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
