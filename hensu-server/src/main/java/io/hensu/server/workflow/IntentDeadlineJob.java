package io.hensu.server.workflow;

import io.hensu.server.mcp.ToolIntents;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/// Applies tool-intent deadlines: withdraws unacknowledged offers so they are offered
/// again, settles started calls past their deadline as `TIMEOUT`, and intents no relay took
/// as `UNAVAILABLE`.
///
/// Every node runs it; each transition is one conditional statement, so concurrent runs
/// apply each deadline once.
///
/// | Property                       | Default | Description                                  |
/// |--------------------------------|---------|----------------------------------------------|
/// | `hensu.mcp.ack-deadline`       | `10s`   | How long an offer waits for acknowledgement  |
/// | `hensu.mcp.call-deadline`      | `60s`   | How long an acknowledged call may take       |
/// | `hensu.mcp.dispatch-deadline`  | `5m`    | How long an intent waits for any relay       |
@ApplicationScoped
public class IntentDeadlineJob {

    private static final Logger LOG = Logger.getLogger(IntentDeadlineJob.class);

    private final ToolIntents intents;

    @Inject
    public IntentDeadlineJob(ToolIntents intents) {
        this.intents = intents;
    }

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        try {
            intents.expireDeadlines();
        } catch (RuntimeException e) {
            LOG.debugv("Deadline sweep failed: {0}", e.getMessage());
        }
    }
}
