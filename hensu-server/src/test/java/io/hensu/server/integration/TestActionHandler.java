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
/// Records all received payloads and returns a configurable result, allowing
/// integration tests to verify plan execution and action dispatch without
/// real side effects.
///
/// {@snippet :
/// testActionHandler.setNextResult(ActionResult.success("done", Map.of("key", "value")));
/// pushAndExecute(workflowWithPlan, context);
/// assertThat(testActionHandler.getReceivedPayloads()).hasSize(2);
/// }
///
/// ### Contracts
/// - **Precondition**: `reset()` must be called between tests
/// - **Postcondition**: payloads are recorded in call order
/// - **Invariant**: thread-safe via {@link CopyOnWriteArrayList}
///
/// @implNote Thread-safe. Uses a copy-on-write list for payload storage
/// and a volatile field for the next result.
///
/// @see io.hensu.server.action.ServerActionExecutor for action dispatch
/// @see io.hensu.core.plan.PlanExecutor for plan step execution
@ApplicationScoped
public class TestActionHandler implements ActionHandler {

    private final List<Map<String, Object>> receivedPayloads = new CopyOnWriteArrayList<>();
    private volatile ActionResult nextResult = ActionResult.success("OK", Map.of());

    @Override
    public String getHandlerId() {
        return "test-tool";
    }

    /// Sets the result to return on subsequent `execute()` calls.
    ///
    /// @param result the action result to return, not null
    public void setNextResult(ActionResult result) {
        this.nextResult = result;
    }

    /// Returns all payloads received since the last reset, in call order.
    ///
    /// @return unmodifiable view of received payloads, never null
    public List<Map<String, Object>> getReceivedPayloads() {
        return receivedPayloads;
    }

    /// Resets recorded payloads and restores the default success result.
    ///
    /// @apiNote **Side effects**: clears all recorded payloads
    public void reset() {
        receivedPayloads.clear();
        nextResult = ActionResult.success("OK", Map.of());
    }

    /// Records the payload and returns the configured result.
    ///
    /// @param payload the action payload from the workflow, not null
    /// @param context current execution context, not null
    /// @return the pre-configured action result, never null
    @Override
    public ActionResult execute(Map<String, Object> payload, Map<String, Object> context) {
        receivedPayloads.add(new HashMap<>(payload));
        return nextResult;
    }
}
