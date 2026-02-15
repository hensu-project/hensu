package io.hensu.server.validation;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.parallel.Branch;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowMetadata;
import io.hensu.core.workflow.node.*;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// Validates a {@link Workflow} at the REST API boundary.
///
/// Ensures all identifier fields match the safe-ID pattern
/// ({@code [a-zA-Z0-9][a-zA-Z0-9._-]{0,254}}) and all free-text
/// fields are free of dangerous control characters.
///
/// Free-text fields (prompts, instructions, rubric content) may contain
/// newlines — those are legitimate. Only null bytes and other non-printable
/// control characters (U+0000–U+0008, U+000B, U+000C, U+000E–U+001F, U+007F)
/// are rejected.
///
/// @see ValidWorkflow
/// @see ValidId for the identifier pattern definition
/// @see LogSanitizer for defense-in-depth at log call sites
public class ValidWorkflowValidator implements ConstraintValidator<ValidWorkflow, Workflow> {

    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}");

    /// Control characters that are never legitimate in workflow content.
    /// Excludes TAB (0x09), LF (0x0A), CR (0x0D) which are valid in free text.
    private static final Pattern DANGEROUS_CONTROL =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    @Override
    public boolean isValid(Workflow workflow, ConstraintValidatorContext ctx) {
        if (workflow == null) {
            return true; // @NotNull handles null check separately
        }

        var errors = new ArrayList<String>();

        requireSafeId(errors, "id", workflow.getId());
        rejectDangerousChars(errors, "version", workflow.getVersion());
        requireSafeId(errors, "startNode", workflow.getStartNode());

        validateAgents(errors, workflow.getAgents());
        validateNodes(errors, workflow.getNodes());
        validateRubrics(errors, workflow.getRubrics());

        var meta = workflow.getMetadata();
        if (meta != null) {
            validateMetadata(errors, meta);
        }

        if (!errors.isEmpty()) {
            ctx.disableDefaultConstraintViolation();
            for (var error : errors) {
                ctx.buildConstraintViolationWithTemplate(error).addConstraintViolation();
            }
            return false;
        }
        return true;
    }

    // ———————————————— Agents ————————————————

    private static void validateAgents(List<String> errors, Map<String, AgentConfig> agents) {
        if (agents == null) return;
        agents.forEach(
                (key, agent) -> {
                    requireSafeId(errors, "agents[key]", key);
                    if (agent != null) {
                        requireSafeId(errors, "agents." + key + ".id", agent.getId());
                        rejectDangerousChars(errors, "agents." + key + ".role", agent.getRole());
                        rejectDangerousChars(errors, "agents." + key + ".model", agent.getModel());
                        rejectDangerousChars(
                                errors, "agents." + key + ".instructions", agent.getInstructions());
                        var tools = agent.getTools();
                        if (tools != null) {
                            tools.forEach(
                                    t ->
                                            rejectDangerousChars(
                                                    errors, "agents." + key + ".tools[]", t));
                        }
                    }
                });
    }

    // ———————————————— Nodes ————————————————

    private static void validateNodes(List<String> errors, Map<String, Node> nodes) {
        if (nodes == null) return;
        nodes.forEach(
                (key, node) -> {
                    requireSafeId(errors, "nodes[key]", key);
                    if (node != null) validateNode(errors, key, node);
                });
    }

    private static void validateNode(List<String> errors, String key, Node node) {
        var p = "nodes." + key;
        requireSafeId(errors, p + ".id", node.getId());

        switch (node) {
            case StandardNode sn -> {
                optionalSafeId(errors, p + ".agentId", sn.getAgentId());
                rejectDangerousChars(errors, p + ".prompt", sn.getPrompt());
                optionalSafeId(errors, p + ".rubricId", sn.getRubricId());
                optionalSafeId(errors, p + ".planFailureTarget", sn.getPlanFailureTarget());
                var params = sn.getOutputParams();
                if (params != null) {
                    params.forEach(param -> requireSafeId(errors, p + ".outputParams[]", param));
                }
            }
            case ParallelNode pn -> {
                for (var branch : pn.getBranches()) {
                    validateBranch(errors, p, branch);
                }
            }
            case SubWorkflowNode swn -> {
                requireSafeId(errors, p + ".workflowId", swn.getWorkflowId());
                validateStringMap(errors, p + ".inputMapping", swn.getInputMapping());
                validateStringMap(errors, p + ".outputMapping", swn.getOutputMapping());
            }
            case ForkNode fn -> {
                var targets = fn.getTargets();
                if (targets != null) {
                    targets.forEach(t -> requireSafeId(errors, p + ".targets[]", t));
                }
            }
            case JoinNode jn -> {
                var awaits = jn.getAwaitTargets();
                if (awaits != null) {
                    awaits.forEach(t -> requireSafeId(errors, p + ".awaitTargets[]", t));
                }
                optionalSafeId(errors, p + ".outputField", jn.getOutputField());
            }
            case GenericNode gn -> {
                requireSafeId(errors, p + ".executorType", gn.getExecutorType());
                optionalSafeId(errors, p + ".rubricId", gn.getRubricId());
            }
            case EndNode _ -> {
                /* enum status only — no string fields */
            }
            default -> {
                /* unknown node subtype — skip */
            }
        }
    }

    private static void validateBranch(List<String> errors, String prefix, Branch branch) {
        requireSafeId(errors, prefix + ".branch.id", branch.id());
        requireSafeId(errors, prefix + ".branch.agentId", branch.agentId());
        rejectDangerousChars(errors, prefix + ".branch.prompt", branch.prompt());
        optionalSafeId(errors, prefix + ".branch.rubricId", branch.rubricId());
    }

    // ———————————————— Rubrics ————————————————

    private static void validateRubrics(List<String> errors, Map<String, String> rubrics) {
        if (rubrics == null) return;
        rubrics.forEach(
                (key, value) -> {
                    requireSafeId(errors, "rubrics[key]", key);
                    rejectDangerousChars(errors, "rubrics." + key, value);
                });
    }

    // ———————————————— Metadata ————————————————

    private static void validateMetadata(List<String> errors, WorkflowMetadata meta) {
        rejectDangerousChars(errors, "metadata.name", meta.name());
        rejectDangerousChars(errors, "metadata.description", meta.description());
        rejectDangerousChars(errors, "metadata.author", meta.author());
        var tags = meta.tags();
        tags.forEach(t -> rejectDangerousChars(errors, "metadata.tags[]", t));
    }

    // ———————————————— Helpers ————————————————

    private static void requireSafeId(List<String> errors, String field, String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            errors.add(field + ": invalid identifier");
        }
    }

    private static void optionalSafeId(List<String> errors, String field, String value) {
        if (value != null && !SAFE_ID.matcher(value).matches()) {
            errors.add(field + ": invalid identifier");
        }
    }

    private static void rejectDangerousChars(List<String> errors, String field, String value) {
        if (value != null && DANGEROUS_CONTROL.matcher(value).find()) {
            errors.add(field + ": contains illegal control characters");
        }
    }

    private static void validateStringMap(
            List<String> errors, String prefix, Map<String, String> map) {
        if (map == null) return;
        map.forEach(
                (k, v) -> {
                    requireSafeId(errors, prefix + "[key]", k);
                    rejectDangerousChars(errors, prefix + "." + k, v);
                });
    }
}
