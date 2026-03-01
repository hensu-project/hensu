package io.hensu.server.dev;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.workflow.node.GenericNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/// Dev-only handler for crash-recovery manual testing.
///
/// Sleeps for a configurable number of seconds, giving the operator time
/// to kill the server mid-execution and verify that {@code WorkflowRecoveryJob}
/// resumes the workflow from the last checkpoint.
///
/// ### Config Options
/// | Key               | Type | Default | Description               |
/// |-------------------|------|---------|---------------------------|
/// | `durationSeconds` | int  | `30`    | How long to sleep (s)     |
///
/// ### Manual Test Recipe
///
/// ```
/// 1. Set hensu.lease.stale-threshold=15s, hensu.lease.heartbeat-interval=5s
/// 2. Push crash-recovery.json, start execution
/// 3. Wait for log: "SleepHandler sleeping for N seconds"
/// 4. kill -9 <server PID>
/// 5. Restart server — WorkflowRecoveryJob claims the stale row after 15 s
/// 6. Verify execution status reaches COMPLETED
/// ```
///
/// @see io.hensu.server.workflow.WorkflowRecoveryJob
@ApplicationScoped
public class SleepHandler implements GenericNodeHandler {

    private static final Logger LOG = Logger.getLogger(SleepHandler.class);

    public static final String TYPE = "sleep";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public NodeResult handle(GenericNode node, ExecutionContext context) {
        int seconds = (int) node.getConfig().getOrDefault("durationSeconds", 30);
        LOG.infov(
                "SleepHandler sleeping for {0} seconds (execution={1}, node={2})",
                seconds, context.getState().getExecutionId(), node.getId());

        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NodeResult.failure("Sleep interrupted");
        }

        LOG.infov("SleepHandler done (execution={0})", context.getState().getExecutionId());
        return NodeResult.success(
                "Slept for " + seconds + " seconds", Map.of("slept_seconds", seconds));
    }
}
