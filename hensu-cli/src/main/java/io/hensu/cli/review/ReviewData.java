package io.hensu.cli.review;

import java.util.List;
import java.util.Map;

/// Primitive data carrier for the review terminal UI.
///
/// Decouples the display layer ({@link ReviewTerminal}) from both the core domain
/// model and the daemon wire protocol. Adapters ({@link CLIReviewHandler},
/// {@link DaemonClientReviewer}) convert their respective data sources into this
/// record before delegating to {@code ReviewTerminal}.
///
/// @param nodeId           ID of the node awaiting review, not null
/// @param status           node result status string (e.g. {@code "SUCCESS"}), not null
/// @param output           full node output text, may be null
/// @param rubricScore      rubric score if evaluated; {@code null} if no rubric ran
/// @param rubricPassed     whether the rubric passed; {@code null} if no rubric ran
/// @param allowBacktrack   whether the reviewer may backtrack to a previous step
/// @param historySteps     ordered list of completed steps for backtrack and history display
/// @param promptTemplate   read-only prompt template for display in context editor header,
/// may be null
/// @param contextVariables current state context — editable during backtrack
record ReviewData(
        String nodeId,
        String status,
        String output,
        Double rubricScore,
        Boolean rubricPassed,
        boolean allowBacktrack,
        List<StepInfo> historySteps,
        String promptTemplate,
        Map<String, Object> contextVariables) {

    /// Compact step summary for history display and backtrack selection.
    ///
    /// @param nodeId         node identifier, not null
    /// @param status         result status string (e.g. {@code "SUCCESS"}), not null
    /// @param promptTemplate prompt template for this node, may be null
    record StepInfo(String nodeId, String status, String promptTemplate) {}
}
