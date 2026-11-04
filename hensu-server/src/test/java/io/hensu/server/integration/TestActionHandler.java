package io.hensu.server.integration;

import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.execution.action.ActionHandler;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/// Test {@link ActionHandler} for the `"test-tool"` handler ID.
///
/// Records all received payloads and answers every call with success, allowing
/// integration tests to verify action dispatch without real side effects.
///
/// {@snippet :
/// pushAndExecute(workflowWithActions, context);
/// assertThat(testActionHandler.getReceivedPayloads()).hasSize(2);
/// }
///
/// ### Contracts
/// - **Precondition**: `reset()` must be called between tests
/// - **Postcondition**: payloads are recorded in call order
/// - **Invariant**: thread-safe via {@link CopyOnWriteArrayList}
///
/// @implNote Thread-safe. Uses a copy-on-write list for payload storage.
///
/// @see io.hensu.server.action.ServerActionExecutor for action dispatch
@ApplicationScoped
public class TestActionHandler implements ActionHandler {

    private static final ActionResult OK = ActionResult.success("OK", Map.of());

    private final List<Map<String, Object>> receivedPayloads = new CopyOnWriteArrayList<>();

    @Override
    public String getHandlerId() {
        return "test-tool";
    }

    /// Returns all payloads received since the last reset, in call order.
    ///
    /// @return unmodifiable view of received payloads, never null
    public List<Map<String, Object>> getReceivedPayloads() {
        return receivedPayloads;
    }

    /// Resets recorded payloads.
    ///
    /// @apiNote **Side effects**: clears all recorded payloads
    public void reset() {
        receivedPayloads.clear();
    }

    /// Records the payload and returns success.
    ///
    /// @param payload the action payload from the workflow, not null
    /// @param context current execution context, not null
    /// @return a successful action result, never null
    @Override
    public ActionResult execute(Map<String, Object> payload, Map<String, Object> context) {
        receivedPayloads.add(new HashMap<>(payload));
        return OK;
    }
}
