package io.hensu.server.workflow;

import java.util.Map;

/// Decision for resuming a paused execution.
///
/// @param approved whether the pending plan is approved
/// @param modifications optional context modifications to apply before resuming, may be null
public record ResumeDecision(boolean approved, Map<String, Object> modifications) {

    public static ResumeDecision approve() {
        return new ResumeDecision(true, Map.of());
    }

    public static ResumeDecision modify(Map<String, Object> mods) {
        return new ResumeDecision(true, mods);
    }
}
