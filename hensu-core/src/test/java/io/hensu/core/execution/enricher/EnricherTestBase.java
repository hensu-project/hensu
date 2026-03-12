package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.state.WorkflowStateSchema;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Shared test infrastructure for enricher unit tests.
///
/// Provides minimal-boilerplate builders for the common objects needed across all enricher tests:
/// a throwaway {@link HensuState}, a plain {@link StandardNode}, and a configured
/// {@link ExecutionContext}.
abstract class EnricherTestBase {

    /// Builds a minimal state with no history and an empty context map.
    protected HensuState minimalState() {
        return new HensuState.Builder()
                .executionId("test")
                .workflowId("wf")
                .currentNode("node")
                .context(new HashMap<>())
                .history(new ExecutionHistory())
                .build();
    }

    /// Builds a plain node with no rubric, no writes, and a single success transition.
    protected StandardNode minimalNode() {
        return StandardNode.builder()
                .id("node")
                .transitionRules(List.of(new SuccessTransition("next")))
                .build();
    }

    /// Builds an {@link ExecutionContext} with configurable rubric paths and state schema.
    ///
    /// @param rubricPaths rubric ID to file path map; pass empty map for no rubric paths
    /// @param schema      optional state schema; pass null for no schema
    /// @param engine      optional rubric engine; pass null if not needed
    protected ExecutionContext ctx(
            Map<String, String> rubricPaths, WorkflowStateSchema schema, RubricEngine engine) {
        var workflowBuilder =
                Workflow.builder()
                        .id("wf")
                        .startNode("node")
                        .nodes(Map.of("node", minimalNode()))
                        .rubrics(rubricPaths);
        if (schema != null) {
            workflowBuilder.stateSchema(schema);
        }
        var ctxBuilder =
                ExecutionContext.builder().state(minimalState()).workflow(workflowBuilder.build());
        if (engine != null) {
            ctxBuilder.rubricEngine(engine);
        }
        return ctxBuilder.build();
    }
}
