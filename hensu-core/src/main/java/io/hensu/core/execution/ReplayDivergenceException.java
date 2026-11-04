package io.hensu.core.execution;

import java.io.Serial;

/// A recovered run decided differently at a step its predecessor journaled.
///
/// Replay is deterministic by construction – recorded model turns and tool outcomes
/// are consumed rather than requested again – so a divergence means the engine or a
/// workflow behaved non-deterministically between the original run and the recovery.
/// Continuing would act on a history the journal contradicts, so the execution fails.
public final class ReplayDivergenceException extends RunAbortedException {

    @Serial private static final long serialVersionUID = -7569162033491645675L;

    /// @param message the step and what differed, not null
    public ReplayDivergenceException(String message) {
        super(message, null);
    }
}
