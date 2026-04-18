package io.hensu.cli.workflow;

import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.core.workflow.node.JoinNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.node.SubWorkflowNode;
import io.hensu.core.workflow.state.WorkflowStateSchema;
import io.hensu.core.workflow.validation.SubWorkflowGraphValidator;
import io.hensu.dsl.WorkingDirectory;
import io.hensu.dsl.parsers.KotlinScriptParser;
import io.hensu.serialization.WorkflowSerializer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Loads sub-workflows supplied via the `--with` CLI option and wires them into the
/// shared {@link WorkflowRepository} so {@code SubWorkflowNodeExecutor} can resolve
/// children at runtime.
///
/// ### Declaration model
/// Every `--with <name>` value is a workflow name resolved through the same
/// {@link WorkingDirectory} as the root, via {@link WorkingDirectory#resolveWorkflow}.
/// The `.kt` suffix is optional. Prompts and rubrics are shared with the root.
///
/// ```bash
/// hensu run main-research --with sub-summarizer --with sub-translator
/// ```
///
/// ### Validation
/// After every declared sub is compiled and saved, the loader walks every
/// {@link SubWorkflowNode} reference in the root and in each loaded sub and verifies:
/// - the `target` id resolves to a workflow the user supplied via `--with`
/// - any `targetVersion` pin matches the child's declared version
/// - same-name `imports` and `writes` are declared in the child's state schema
/// - the child actually writes every variable the parent expects
///
/// Duplicate ids (root id clashing with a sub, or two subs with the same id) and hash
/// divergence for the same `(id, version)` are hard errors. All errors are aggregated.
///
/// ### Tenancy
/// The CLI uses a single tenant across its runtime. The same constant is injected into
/// the execution context by `WorkflowRunCommand` so the executor's repository lookups
/// resolve to the workflows saved here.
///
/// ### Hashing
/// SHA-256 over `WorkflowSerializer.toJson(child)` with all whitespace stripped. Used
/// now to detect divergent duplicate loads; forward compatible with the
/// workflow-versioning ticket's `targetHash` enforcement.
///
/// @implNote Stateless `@ApplicationScoped` bean. All traversal state is local.
@ApplicationScoped
public class SubWorkflowLoader {

    /// Tenant id used by the CLI runtime for workflow repository storage and lookups.
    /// `WorkflowRunCommand` seeds the execution context `_tenant_id` with the same value
    /// so sub-workflow executor lookups hit the workflows saved here.
    public static final String CLI_TENANT = "test-tenant";

    @Inject KotlinScriptParser kotlinParser;

    @Inject WorkflowRepository workflowRepository;

    /// Compiles every `--with` workflow, saves the root and each sub to the repository,
    /// and validates every {@link SubWorkflowNode} binding reachable from the loaded set.
    ///
    /// @param workingDir the shared working directory for root and every declared sub
    /// @param root the already-compiled root workflow, not null
    /// @param withNames workflow names supplied via `--with`; may be empty if the root
    ///     has no sub-workflow references
    /// @return the unique sub-workflows loaded (root excluded), in declaration order;
    ///     empty when {@code withNames} is empty or only declares duplicates of root
    /// @throws IllegalStateException if any sub fails to compile, duplicate ids are
    ///     declared, the same `(id, version)` loads with divergent content, a pinned
    ///     `targetVersion` mismatches, or a parent/child binding is invalid
    public List<Workflow> resolveDeclared(
            WorkingDirectory workingDir, Workflow root, List<String> withNames) {
        List<String> errors = new ArrayList<>();
        Map<String, byte[]> hashesByCoord = new HashMap<>();
        Map<String, Workflow> loadedById = new LinkedHashMap<>();

        saveWorkflow(root, hashesByCoord, loadedById, errors, "(root)");

        if (withNames != null) {
            for (String name : withNames) {
                compileDeclaredSub(workingDir, name, hashesByCoord, loadedById, errors);
            }
        }

        // Reject parent→child cycles before runtime can blow the stack.
        try {
            SubWorkflowGraphValidator.validate(loadedById.values());
        } catch (IllegalStateException e) {
            errors.add(e.getMessage());
        }

        // Validate every SubWorkflowNode reference reachable from root + loaded subs.
        for (Workflow wf : new ArrayList<>(loadedById.values())) {
            for (Node node : wf.getNodes().values()) {
                if (!(node instanceof SubWorkflowNode sub)) continue;
                validateReference(wf, sub, loadedById, errors);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Sub-workflow resolution failed for '"
                            + root.getId()
                            + "':\n  - "
                            + String.join("\n  - ", errors));
        }

        List<Workflow> subs = new ArrayList<>(loadedById.values());
        subs.removeIf(wf -> wf.getId().equals(root.getId()));
        return List.copyOf(subs);
    }

    private void compileDeclaredSub(
            WorkingDirectory workingDir,
            String name,
            Map<String, byte[]> hashesByCoord,
            Map<String, Workflow> loadedById,
            List<String> errors) {
        Workflow compiled;
        try {
            compiled = kotlinParser.parse(workingDir, name);
        } catch (RuntimeException e) {
            errors.add("--with '" + name + "' failed to compile: " + e.getMessage());
            return;
        }
        saveWorkflow(compiled, hashesByCoord, loadedById, errors, "--with " + name);
    }

    private void saveWorkflow(
            Workflow workflow,
            Map<String, byte[]> hashesByCoord,
            Map<String, Workflow> loadedById,
            List<String> errors,
            String source) {
        String id = workflow.getId();
        String coord = coord(id, workflow.getVersion());
        byte[] newHash = trimmedHash(workflow);

        Workflow priorById = loadedById.get(id);
        if (priorById != null) {
            byte[] previous = hashesByCoord.get(coord);
            if (previous == null || !Arrays.equals(previous, newHash)) {
                errors.add(
                        "Workflow id '"
                                + id
                                + "' declared twice with conflicting definitions"
                                + (priorById.getVersion().equals(workflow.getVersion())
                                        ? ""
                                        : " (versions '"
                                                + priorById.getVersion()
                                                + "' and '"
                                                + workflow.getVersion()
                                                + "')")
                                + " (source: "
                                + source
                                + ")");
            }
            return;
        }

        workflowRepository.save(CLI_TENANT, workflow);
        hashesByCoord.put(coord, newHash);
        loadedById.put(id, workflow);
    }

    private static void validateReference(
            Workflow parent,
            SubWorkflowNode sub,
            Map<String, Workflow> loadedById,
            List<String> errors) {
        String targetId = sub.getWorkflowId();
        Workflow child = loadedById.get(targetId);
        if (child == null) {
            errors.add(
                    "Parent '"
                            + parent.getId()
                            + "' references sub-workflow '"
                            + targetId
                            + "' which was not supplied via --with");
            return;
        }
        String pinned = sub.getTargetVersion();
        if (pinned != null && !pinned.equals(child.getVersion())) {
            errors.add(
                    "Parent '"
                            + parent.getId()
                            + "' pins sub-workflow '"
                            + targetId
                            + "' to version '"
                            + pinned
                            + "' but child declares '"
                            + child.getVersion()
                            + "'");
            return;
        }
        validateBinding(parent, sub, child, errors);
    }

    private static void validateBinding(
            Workflow parent, SubWorkflowNode sub, Workflow child, List<String> errors) {
        WorkflowStateSchema childSchema = child.getStateSchema();
        if (childSchema == null) {
            return;
        }
        for (String name : sub.getInputMapping().keySet()) {
            if (!childSchema.contains(name)) {
                errors.add(
                        "Parent '"
                                + parent.getId()
                                + "' imports '"
                                + name
                                + "' into sub-workflow '"
                                + child.getId()
                                + "' but child schema does not declare it");
            }
        }
        Set<String> childWrites = collectChildWrites(child);
        for (String name : sub.getOutputMapping().keySet()) {
            if (!childSchema.contains(name)) {
                errors.add(
                        "Parent '"
                                + parent.getId()
                                + "' expects sub-workflow '"
                                + child.getId()
                                + "' to write '"
                                + name
                                + "' but child schema does not declare it");
            } else if (!childWrites.contains(name)) {
                errors.add(
                        "Parent '"
                                + parent.getId()
                                + "' expects sub-workflow '"
                                + child.getId()
                                + "' to write '"
                                + name
                                + "' but no node in the child writes that variable");
            }
        }
    }

    private static Set<String> collectChildWrites(Workflow child) {
        Set<String> writes = new HashSet<>();
        for (Node n : child.getNodes().values()) {
            if (n instanceof StandardNode sn) {
                writes.addAll(sn.getWrites());
            } else if (n instanceof JoinNode jn) {
                writes.addAll(jn.getWrites());
            }
        }
        return writes;
    }

    private static byte[] trimmedHash(Workflow workflow) {
        String json = WorkflowSerializer.toJson(workflow).replaceAll("\\s", "");
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String coord(String id, String version) {
        return id + "@" + (version == null ? "" : version);
    }
}
