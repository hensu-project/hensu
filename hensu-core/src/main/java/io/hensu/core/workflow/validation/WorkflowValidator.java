package io.hensu.core.workflow.validation;

import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.node.SubWorkflowNode;
import io.hensu.core.workflow.state.WorkflowStateSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Load-time validator for workflow schema consistency.
///
/// Validates that a workflow's `writes` declarations and prompt template variable
/// references are consistent with its declared {@link WorkflowStateSchema}.
/// Skipped entirely when no schema is declared (legacy workflows pass through unchanged).
///
/// ### Checks performed
/// - Every name in a node's `writes` is declared in the schema or is an engine variable
/// - Every `{variable}` reference in a node's prompt is declared in the schema or is
/// an engine variable
///
/// ### What is NOT checked
/// - Full dataflow analysis (definite assignment on cyclic graphs is not worth it)
/// - Type compatibility between writes and prompt references
/// - Reachability of nodes
///
/// @implNote **Thread-safe.** Stateless utility class; no mutable state. Called once per workflow
/// at load time by `io.hensu.dsl.builders.WorkflowBuilder`.
///
/// @see WorkflowStateSchema#ENGINE_VARIABLES for implicitly valid variable names
public final class WorkflowValidator {

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");

    private WorkflowValidator() {}

    /// Validates the workflow against its declared state schema.
    ///
    /// No-op when the workflow has no schema — legacy workflows always pass.
    ///
    /// @param workflow the workflow to validate, not null
    /// @throws IllegalStateException if any schema violation is found, listing all errors
    public static void validate(Workflow workflow) {
        WorkflowStateSchema schema = workflow.getStateSchema();
        if (schema == null) {
            return;
        }

        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, Node> entry : workflow.getNodes().entrySet()) {
            String nodeId = entry.getKey();
            Node node = entry.getValue();

            if (node instanceof SubWorkflowNode sub) {
                validateSubWorkflow(nodeId, sub, schema, errors);
                continue;
            }
            if (!(node instanceof StandardNode standardNode)) continue;

            for (String name : standardNode.getWrites()) {
                if (!schema.contains(name)) {
                    errors.add(
                            "Node '"
                                    + nodeId
                                    + "' writes '"
                                    + name
                                    + "' which is not declared in state schema");
                }
            }

            if (standardNode.getPrompt() != null) {
                Matcher m = TEMPLATE_VAR.matcher(standardNode.getPrompt());
                while (m.find()) {
                    String varName = m.group(1);
                    if (!schema.contains(varName)) {
                        errors.add(
                                "Node '"
                                        + nodeId
                                        + "' prompt references '{"
                                        + varName
                                        + "}' which is not declared in state schema");
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Workflow '"
                            + workflow.getId()
                            + "' has schema violations:\n"
                            + String.join("\n", errors));
        }
    }

    private static void validateSubWorkflow(
            String nodeId, SubWorkflowNode sub, WorkflowStateSchema schema, List<String> errors) {
        if (sub.getWorkflowId() == null || sub.getWorkflowId().isBlank()) {
            errors.add("Sub-workflow node '" + nodeId + "' has no target workflow id");
        }
        // Same-name discipline: input/output maps are identity. Check declared in parent schema.
        for (String name : sub.getInputMapping().keySet()) {
            if (!schema.contains(name)) {
                errors.add(
                        "Sub-workflow node '"
                                + nodeId
                                + "' imports '"
                                + name
                                + "' which is not declared in parent state schema");
            }
        }
        for (String name : sub.getOutputMapping().keySet()) {
            if (!schema.contains(name)) {
                errors.add(
                        "Sub-workflow node '"
                                + nodeId
                                + "' writes '"
                                + name
                                + "' which is not declared in parent state schema");
            }
        }
    }
}
