package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.result.BacktrackEvent;
import io.hensu.core.rubric.RubricParser;
import io.hensu.core.rubric.model.Rubric;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.service.WorkflowService.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Integration tests for rubric-based quality evaluation during workflow execution.
///
/// Covers rubric pass/fail evaluation and automatic backtracking triggered by
/// rubric score thresholds. Rubrics are pre-registered in the repository before
/// execution so that `WorkflowExecutor.registerRubricIfAbsent()` finds them
/// already present, avoiding filesystem path resolution in tests.
///
/// ### Rubric Pre-Registration Strategy
/// The workflow JSON fixtures map `rubricId` to a definition identifier
/// (e.g., `"quality" -> "quality-high"`). At runtime, the executor would treat
/// the definition value as a filesystem path. In tests, we resolve the classpath
/// rubric file to a temp path, parse it with [RubricParser], and register the
/// resulting [Rubric] under the `rubricId` key used by the workflow. This lets
/// `registerRubricIfAbsent()` skip file parsing entirely.
///
/// ### Score Normalization
/// The [DefaultRubricEvaluator][io.hensu.core.rubric.evaluator.DefaultRubricEvaluator]
/// extracts a self-reported `score` from the agent's JSON output and returns it
/// directly. The [RubricEngine][io.hensu.core.rubric.RubricEngine] then computes
/// the final score as `(weightedSum / maxWeight) * 100`. With a single default
/// criterion (weight 1.0), the final score equals `selfScore * 100`. Therefore,
/// self-reported scores must use the 0-1 scale (e.g., `0.85` for 85%) for the
/// threshold arithmetic to produce meaningful results.
///
/// ### Contracts
/// - **Precondition**: Stub mode enabled (`hensu.stub.enabled=true`)
/// - **Invariant**: All executions use [#TEST_TENANT]
///
/// @see IntegrationTestBase for shared test infrastructure
/// @see io.hensu.core.rubric.RubricEngine for evaluation logic
/// @see io.hensu.core.rubric.evaluator.DefaultRubricEvaluator for score extraction
@QuarkusTest
class RubricEvaluationIntegrationTest extends IntegrationTestBase {

    /// Verifies that a workflow completes successfully when the agent's
    /// self-reported score exceeds the rubric pass threshold.
    ///
    /// The stub response includes a JSON `score` field that the
    /// [DefaultRubricEvaluator][io.hensu.core.rubric.evaluator.DefaultRubricEvaluator]
    /// extracts as the self-evaluation score. A normalized score of `0.85`
    /// yields a final score of 85, which exceeds the default rubric pass
    /// threshold (70). No backtracking occurs and the workflow transitions
    /// directly to the end node.
    @Test
    void shouldCompleteWhenRubricPasses() {
        parseAndRegisterRubric("quality", "quality-high.md");
        Workflow workflow = loadWorkflow("rubric-evaluation-pass.json");

        registerStub(
                "draft",
                """
                {"score": 0.85, "content": "A comprehensive article about artificial intelligence \
                covering machine learning, neural networks, and practical applications."}""");

        ExecutionStartResult result =
                pushAndExecute(workflow, Map.of("topic", "artificial intelligence"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("done");
    }

    /// Verifies that a minor rubric failure triggers self-node retry
    /// backtracking, and that the workflow eventually completes once
    /// the maximum retry count is exhausted.
    ///
    /// The `rubric-backtrack-critical.json` fixture defines a two-step
    /// workflow: `research -> draft -> done`, with rubric evaluation on
    /// `draft`. The stub always returns a self-reported score of `0.65`,
    /// yielding a final score of 65 -- below the pass threshold (70) and
    /// within the minor failure range (60-80). The executor retries the
    /// same node up to 3 times (`DEFAULT_MAX_BACKTRACK_RETRIES`). After
    /// retries are exhausted, the auto-backtrack mechanism returns null
    /// and normal transition evaluation proceeds, completing the workflow.
    ///
    /// The test asserts that backtrack events were recorded in the
    /// execution history, confirming the retry mechanism was triggered.
    @Test
    void shouldRetryAndCompleteOnMinorRubricFailure() {
        parseAndRegisterRubric("quality", "quality-low.md");
        Workflow workflow = loadWorkflow("rubric-backtrack-critical.json");

        registerStub("research", "Research findings about quantum computing.");
        registerStub(
                "draft",
                """
                {"score": 0.65, "recommendation": "The draft could benefit from \
                more specific examples and stronger source citations."}\
                """);

        ExecutionStartResult result =
                pushAndExecute(workflow, Map.of("topic", "quantum computing"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");

        // Minor failure retries the same node, so backtrack events record draft -> draft
        List<BacktrackEvent> backtracks = snapshot.history().getBacktracks();
        assertThat(backtracks)
                .as("Expected retry backtrack events from minor rubric failure on 'draft'")
                .isNotEmpty();
        assertThat(backtracks.getFirst().getFrom()).isEqualTo("draft");
        assertThat(backtracks.getFirst().getTo()).isEqualTo("draft");
    }

    /// Parses a rubric from the classpath and registers it in the rubric
    /// repository under the specified `rubricId`.
    ///
    /// The parsed rubric's internal ID (derived from the temp file name)
    /// is replaced by building a new rubric with the desired `rubricId`,
    /// preserving all criteria and thresholds from the original file.
    ///
    /// @param rubricId     the ID the workflow expects (e.g. `"quality"`), not null
    /// @param resourceName rubric file under `/rubrics/` (e.g. `"quality-high.md"`), not null
    private void parseAndRegisterRubric(String rubricId, String resourceName) {
        String rubricPath = resolveRubricPath(resourceName);
        Rubric parsed = RubricParser.parse(Path.of(rubricPath));

        // Rebuild with the rubricId the workflow expects
        Rubric rubric =
                Rubric.builder()
                        .id(rubricId)
                        .name(parsed.getName())
                        .version(parsed.getVersion())
                        .type(parsed.getType())
                        .passThreshold(parsed.getPassThreshold())
                        .criteria(parsed.getCriteria())
                        .build();

        hensuEnvironment.getRubricRepository().save(rubric);
    }
}
