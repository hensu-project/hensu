package io.hensu.core.workflow.validation;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.node.SubWorkflowNode;
import io.hensu.core.workflow.state.WorkflowStateSchema;
import io.hensu.core.workflow.transition.ApprovalArms;
import io.hensu.core.workflow.transition.TransitionRule;
import io.hensu.core.workflow.transition.TransitionTargets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Load-time validator for workflow schema consistency.
///
/// Validates that a workflow's `writes` declarations and prompt template variable
/// references are consistent with its declared {@link WorkflowStateSchema}.
/// Skipped entirely when no schema is declared (legacy workflows pass through unchanged).
///
/// ### Checks performed
/// - Every transition target names a node that exists in the workflow
/// - A node under human review that declares one approval arm declares the other as well
/// - Every name in a node's `writes` is declared in the schema and is not a reserved engine
/// variable — the engine infers `score`, `approved`, and `recommendation` from the node's
/// transition rules and consensus configuration
/// - Every `{variable}` reference in a node's prompt is declared in the schema or is
/// an engine variable, which prompts may legitimately reference
///
/// The graph checks run for every workflow; the schema checks are skipped when no schema
/// is declared.
///
/// ### Warnings
/// Logged rather than thrown, for shapes that are legal but almost certainly unintended:
/// a review node whose missing approval arm is absorbed by a following catch-all, and a rule
/// ordered after an unconditional transition that can therefore never match.
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

    private static final Logger logger = Logger.getLogger(WorkflowValidator.class.getName());

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");

    private WorkflowValidator() {}

    /// Validates the workflow against its declared state schema.
    ///
    /// No-op when the workflow has no schema — legacy workflows always pass.
    ///
    /// @param workflow the workflow to validate, not null
    /// @throws IllegalStateException if any schema violation is found, listing all errors
    public static void validate(Workflow workflow) {
        List<String> errors = new ArrayList<>();

        validateTransitionTargets(workflow, errors);
        validateReviewArms(workflow, errors);
        validateArmOrdering(workflow);

        WorkflowStateSchema schema = workflow.getStateSchema();
        if (schema == null) {
            if (!errors.isEmpty()) {
                throwErrors(workflow, errors);
            }
            return;
        }

        for (Map.Entry<String, Node> entry : workflow.getNodes().entrySet()) {
            String nodeId = entry.getKey();
            Node node = entry.getValue();

            if (node instanceof SubWorkflowNode sub) {
                validateSubWorkflow(nodeId, sub, schema, errors);
                continue;
            }
            if (!(node instanceof StandardNode standardNode)) continue;

            for (String name : standardNode.getWrites()) {
                if (EngineVariables.isEngineVar(name)) {
                    // schema.contains() whitelists engine variables so that prompts may
                    // reference {recommendation}; writes must not claim them.
                    errors.add(
                            "Node '"
                                    + nodeId
                                    + "' writes '"
                                    + name
                                    + "' which is a reserved engine variable — the engine infers"
                                    + " it from the node's transition rules and consensus"
                                    + " configuration");
                    continue;
                }
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
            throwErrors(workflow, errors);
        }
    }

    /// Requires a node under human review that declares one approval arm to declare the other.
    ///
    /// {@link io.hensu.core.execution.pipeline.ReviewPostProcessor} records the reviewer's
    /// verdict on the state and lets {@link io.hensu.core.workflow.transition.ApprovalTransition}
    /// route it, so a node offering only `onApproval` leaves a rejection with no matching rule
    /// and the executor dies at runtime with `No valid transition from <node>`.
    ///
    /// Two shapes are deliberately accepted:
    /// - A review node with **no** approval arm at all. There is nothing to route to, so a
    ///   rejection terminates the workflow by design.
    /// - A node whose missing arm is absorbed by a catch-all ordered after the arms; see
    ///   {@link ApprovalArms#absorbsMissingArm(List)}. That is legal but rarely intended — the
    ///   verdict lands on a rule that says nothing about approval — so it is logged as a
    ///   warning rather than accepted silently.
    ///
    /// The question asked is "does this node declare an approval arm?", answered by
    /// {@link ApprovalArms} rather than by inspecting
    /// {@link TransitionRule#requiredRoutingVars()}: a condition arm routing on a user
    /// variable that happens to be named `approved` would answer the latter yes while
    /// offering the verdict nowhere to go.
    ///
    /// @param workflow the workflow to inspect, not null
    /// @param errors collector for validation messages, not null
    private static void validateReviewArms(Workflow workflow, List<String> errors) {
        for (Map.Entry<String, Node> entry : workflow.getNodes().entrySet()) {
            if (!(entry.getValue() instanceof StandardNode standardNode)) continue;
            if (standardNode.getReviewConfig() == null) continue;

            List<TransitionRule> rules = standardNode.getTransitionRules();
            if (!ApprovalArms.declaresAny(rules)) continue;

            boolean absorbed = ApprovalArms.absorbsMissingArm(rules);
            if (!ApprovalArms.declares(rules, true)) {
                report(entry.getKey(), "onApproval", "an approval", absorbed, errors);
            }
            if (!ApprovalArms.declares(rules, false)) {
                report(entry.getKey(), "onRejection", "a rejection", absorbed, errors);
            }
        }
    }

    private static void report(
            String nodeId, String arm, String verdict, boolean absorbed, List<String> errors) {
        String message = missingArm(nodeId, arm, verdict);
        if (absorbed) {
            logger.warning(
                    message + ", so it falls through to the unconditional rule that follows");
            return;
        }
        errors.add(message);
    }

    private static String missingArm(String nodeId, String arm, String verdict) {
        return "Node '"
                + nodeId
                + "' is under human review and declares an approval arm but no '"
                + arm
                + "' transition, so "
                + verdict
                + " from the reviewer has nowhere to go";
    }

    /// Warns when a rule is ordered after an unconditional one, which makes it unreachable.
    ///
    /// Transition rules are evaluated in declaration order and the first match wins, so an
    /// `always goto` — or a bounded revise decorating one — placed before other arms silently
    /// disables them. This is the loop-shaped version of the mistake: a bounded backtrack arm
    /// written above the exit arm keeps the workflow revising until the budget runs out and the
    /// exit condition is never consulted.
    ///
    /// Diagnosed as a warning rather than an error: the shape is legal, and an author may have
    /// written it knowingly while building a graph up.
    ///
    /// @param workflow the workflow to inspect, not null
    private static void validateArmOrdering(Workflow workflow) {
        for (Map.Entry<String, Node> entry : workflow.getNodes().entrySet()) {
            if (!(entry.getValue() instanceof StandardNode standardNode)) continue;

            List<TransitionRule> rules = standardNode.getTransitionRules();
            for (int i = 0; i < rules.size() - 1; i++) {
                if (!ApprovalArms.isUnconditional(rules.get(i))) continue;
                logger.warning(
                        "Node '"
                                + entry.getKey()
                                + "' declares an unconditional transition at position "
                                + (i + 1)
                                + " of "
                                + rules.size()
                                + "; the "
                                + (rules.size() - i - 1)
                                + " rule(s) after it can never match");
                break;
            }
        }
    }

    private static void validateTransitionTargets(Workflow workflow, List<String> errors) {
        Set<String> nodeIds = workflow.getNodes().keySet();

        for (Map.Entry<String, Node> entry : workflow.getNodes().entrySet()) {
            String nodeId = entry.getKey();
            Node node = entry.getValue();
            if (!(node instanceof StandardNode standardNode)) continue;

            for (TransitionRule rule : standardNode.getTransitionRules()) {
                for (String target : TransitionTargets.of(rule)) {
                    if (!nodeIds.contains(target)) {
                        errors.add(
                                "Node '"
                                        + nodeId
                                        + "' has transition to '"
                                        + target
                                        + "' which does not exist in the workflow");
                    }
                }
            }
        }
    }

    private static void throwErrors(Workflow workflow, List<String> errors) {
        throw new IllegalStateException(
                "Workflow '"
                        + workflow.getId()
                        + "' has schema violations:\n"
                        + String.join("\n", errors));
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
