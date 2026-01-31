package io.hensu.core.state;

import io.hensu.core.execution.result.ExecutionHistory;
import java.util.Map;

public class HensuSnapshot {

    private Map<String, Object> context;
    private ExecutionHistory history;
    private String executionId;
    private String workflowId;

    public HensuSnapshot from(HensuState state) {
        context = Map.copyOf(state.getContext());
        history = state.getHistory();
        executionId = state.getExecutionId();
        workflowId = state.getWorkflowId();
        return this;
    }

    public HensuState toWorkflowState() {
        return new HensuState.Builder()
                .context(context)
                .history(history)
                .executionId(executionId)
                .workflowId(workflowId)
                .build();
    }
}
