package io.hensu.core.workflow.validation;

import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.SubWorkflowNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/// Load-time validator for cross-workflow sub-workflow reference graphs.
///
/// Builds a directed graph where each vertex is a workflow id and each edge is a
/// `SubWorkflowNode` reference, then rejects cycles via DFS with a recursion stack.
///
/// ### Modes
/// - {@link #validate(Collection)} — CLI batch path: cycles only; unknown targets are
///   silently skipped because the loader reports missing-declaration errors separately.
/// - {@link #validate(Workflow, Function)} — server push path: cycles **and** unknown
///   referenced ids, in a single DFS pass (single repository lookup per id).
///
/// ### Why load time
/// Without this check, a cycle like `a → b → a` would only surface at runtime inside
/// `SubWorkflowNodeExecutor`, potentially after the outer workflow has already executed
/// several nodes. Failing at load time gives the user a single, actionable error before
/// any side effects occur.
///
/// @implNote **Thread-safe.** Stateless utility class; all traversal state is local.
///
/// @see WorkflowValidator for single-workflow schema validation
public final class SubWorkflowGraphValidator {

    private SubWorkflowGraphValidator() {}

    /// Validates that the sub-workflow reference graph over the given workflows is acyclic.
    ///
    /// Used by the CLI loader which has the full set of compiled workflows in hand after
    /// every `--with` parse completes.
    ///
    /// @param workflows every workflow in the loaded set (root + every declared sub), not null
    /// @throws IllegalStateException if one or more cycles are found; the message lists every
    ///     distinct cycle in the form `a -> b -> a`
    public static void validate(Collection<Workflow> workflows) {
        Map<String, Workflow> byId = new HashMap<>();
        for (Workflow wf : workflows) {
            byId.put(wf.getId(), wf);
        }
        Function<String, Workflow> resolver = byId::get;

        List<List<String>> cycles = new ArrayList<>();
        Set<String> globallyVisited = new HashSet<>();

        for (String id : byId.keySet()) {
            if (globallyVisited.contains(id)) continue;
            LinkedHashSet<String> stack = new LinkedHashSet<>();
            dfs(id, resolver, globallyVisited, stack, cycles, null);
        }

        if (!cycles.isEmpty()) {
            throw new IllegalStateException(cyclesMessage(cycles));
        }
    }

    /// Validates that pushing {@code root} into an existing workflow set does not create a
    /// cycle and that every referenced sub-workflow id can be resolved.
    ///
    /// Walks forward from {@code root} only, calling {@code resolver} lazily for each
    /// referenced target. The invariant is that any cycle introduced by this push must pass
    /// through {@code root} — any cycle not touching it existed before and should have been
    /// caught earlier.
    ///
    /// The caller is expected to provide a resolver that shadows the stored version of
    /// {@code root} with the incoming one, so re-push/update flows see the post-push graph
    /// without touching the repository twice:
    ///
    /// ```java
    /// validate(newWf, id -> id.equals(newWf.getId())
    ///     ? newWf
    ///     : repo.findById(tenant, id).orElse(null));
    /// ```
    ///
    /// @param root the incoming workflow about to be saved, not null
    /// @param resolver lazy lookup of referenced workflow ids, not null; returns null for unknowns
    /// @throws IllegalStateException if any referenced sub-workflow is missing or a cycle is found
    public static void validate(Workflow root, Function<String, Workflow> resolver) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(resolver, "resolver");

        Function<String, Workflow> shadowed =
                id -> id.equals(root.getId()) ? root : resolver.apply(id);

        List<List<String>> cycles = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Set<String> globallyVisited = new HashSet<>();
        LinkedHashSet<String> stack = new LinkedHashSet<>();
        dfs(root.getId(), shadowed, globallyVisited, stack, cycles, missing);

        List<String> errors = new ArrayList<>();
        if (!missing.isEmpty()) {
            errors.add(
                    "Workflow '"
                            + root.getId()
                            + "' references unknown sub-workflows: "
                            + String.join(", ", missing));
        }
        if (!cycles.isEmpty()) {
            errors.add(cyclesMessage(cycles));
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(String.join("\n", errors));
        }
    }

    private static String cyclesMessage(List<List<String>> cycles) {
        StringBuilder msg = new StringBuilder("Sub-workflow reference cycle detected:");
        for (List<String> cycle : cycles) {
            msg.append("\n  - ").append(String.join(" -> ", cycle));
        }
        return msg.toString();
    }

    private static void dfs(
            String current,
            Function<String, Workflow> resolver,
            Set<String> globallyVisited,
            LinkedHashSet<String> stack,
            List<List<String>> cycles,
            List<String> missing) {
        if (stack.contains(current)) {
            List<String> cycle = new ArrayList<>();
            boolean collecting = false;
            for (String id : stack) {
                if (!collecting && id.equals(current)) collecting = true;
                if (collecting) cycle.add(id);
            }
            cycle.add(current);
            cycles.add(cycle);
            return;
        }
        if (globallyVisited.contains(current)) return;

        Workflow wf = resolver.apply(current);
        if (wf == null) {
            if (missing != null) missing.add(current);
            return;
        }

        stack.add(current);
        for (Node node : wf.getNodes().values()) {
            if (!(node instanceof SubWorkflowNode sub)) continue;
            String target = sub.getWorkflowId();
            if (target == null || target.isBlank()) continue;
            dfs(target, resolver, globallyVisited, stack, cycles, missing);
        }
        stack.remove(current);
        globallyVisited.add(current);
    }
}
