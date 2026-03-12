package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.rubric.RubricParser;
import io.hensu.core.rubric.model.Criterion;
import io.hensu.core.rubric.model.Rubric;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/// Injects rubric criteria into the node prompt.
///
/// Applied when the node declares a `rubricId`. This injector loads the rubric
/// (from the engine cache or from disk), appends a horizontal rule to create a
/// clear cognitive boundary between the content under review and the evaluator
/// instructions, then appends the criteria list.
///
/// ### Example output appended to prompt
///
/// ```
/// ---
///
/// Score the content above using these criteria:
///
/// - **Structure** — Is the content well-organized with clear sections?
/// - **Clarity** — Is the argument easy to follow?
/// ```
///
/// The `---` separator and role-neutral imperative phrasing ("Score the content
/// above") work for both modes:
///
/// - **Self-evaluation** — same node produces and scores (e.g. role = "Lead Chef")
/// - **Dedicated reviewer** — separate node whose role is already a reviewer
///
/// ### JSON output requirements
///
/// This injector does **not** append a JSON format block. The `score` and
/// `recommendation` fields are engine-managed variables owned by
/// {@link ScoreVariableInjector} and {@link RecommendationVariableInjector}
/// respectively — they fire based on the node's transition rules, keeping all
/// output requirements in a single consistent format.
///
/// @implNote **Immutable after construction.** Stateless; safe to share across
/// Virtual Threads.
///
/// @see EngineVariableInjector
/// @see io.hensu.core.rubric.model.Rubric
public class RubricPromptInjector implements EngineVariableInjector {

    @Override
    public String inject(String prompt, Node node, ExecutionContext ctx) {
        if (!(node instanceof StandardNode sn) || sn.getRubricId() == null) {
            return prompt;
        }
        Rubric rubric = loadRubric(sn.getRubricId(), ctx);
        return inject(prompt, rubric);
    }

    /// Injects rubric criteria directly from a `Rubric` object.
    ///
    /// @param prompt the base prompt text, not null
    /// @param rubric the rubric whose criteria are injected, not null
    /// @return the enriched prompt with criteria section appended, never null
    public final String inject(String prompt, Rubric rubric) {
        return buildCriteriaSection(prompt, rubric);
    }

    /// Appends a horizontal rule separator and a criteria section listing each criterion.
    ///
    /// The `---` separator creates a clear cognitive boundary between the content
    /// under review and the scoring instructions — critical for self-evaluation nodes
    /// where the same agent both produces and scores content.
    ///
    /// @param prompt the base prompt text, not null
    /// @param rubric the rubric whose criteria are appended, not null
    /// @return the prompt with separator and criteria section appended, never null
    protected String buildCriteriaSection(String prompt, Rubric rubric) {
        StringBuilder sb = new StringBuilder(prompt);
        sb.append("\n\n---\n\nScore the content above using these criteria:\n\n");
        for (Criterion criterion : rubric.getCriteria()) {
            sb.append("- **").append(criterion.getName()).append("**");
            if (criterion.getDescription() != null && !criterion.getDescription().isBlank()) {
                sb.append(" — ").append(criterion.getDescription()).append("?");
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private Rubric loadRubric(String rubricId, ExecutionContext ctx) {
        RubricEngine engine = ctx.getRubricEngine();
        Optional<Rubric> cached = engine.getRubric(rubricId);
        if (cached.isPresent()) {
            return cached.get();
        }
        Map<String, String> rubricPaths = ctx.getWorkflow().getRubrics();
        String path = rubricPaths.get(rubricId);
        if (path == null) {
            throw new IllegalStateException(
                    "No path configured for rubric '" + rubricId + "' in workflow definition");
        }
        Rubric rubric = RubricParser.parse(Path.of(path));
        engine.registerRubric(rubric);
        return rubric;
    }
}
