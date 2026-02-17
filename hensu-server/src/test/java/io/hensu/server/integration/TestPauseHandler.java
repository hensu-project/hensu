package io.hensu.server.integration;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.workflow.node.GenericNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/// Test handler that pauses execution on first invocation and succeeds on subsequent calls.
///
/// Used by {@link StatePersistenceIntegrationTest} to simulate a workflow that
/// pauses mid-execution and resumes later.
///
/// @implNote Thread-safe via {@link AtomicBoolean}. Call {@link #reset()} between tests.
@ApplicationScoped
class TestPauseHandler implements GenericNodeHandler {

    private final AtomicBoolean paused = new AtomicBoolean(false);

    @Override
    public String getType() {
        return "pause";
    }

    @Override
    public NodeResult handle(GenericNode node, ExecutionContext context) {
        if (paused.compareAndSet(false, true)) {
            return new NodeResult(ResultStatus.PENDING, "Awaiting external input", Map.of());
        }
        return new NodeResult(ResultStatus.SUCCESS, "Resumed successfully", Map.of());
    }

    /// Resets the handler to its initial state for the next test.
    void reset() {
        paused.set(false);
    }
}
