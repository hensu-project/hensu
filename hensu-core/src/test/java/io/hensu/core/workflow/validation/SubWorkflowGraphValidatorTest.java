package io.hensu.core.workflow.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.SubWorkflowNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/// Contract tests for {@link SubWorkflowGraphValidator}.
///
/// The validator runs at load time over the full set of workflows the CLI loader (or any
/// future bundle loader) has compiled — root plus every `--with` declared sub. Its job is
/// to reject parent→child reference cycles before execution starts so the runtime executor
/// never blows the stack on infinite recursion.
///
/// Contract:
/// - passes silently when the reference graph is acyclic
/// - throws {@link IllegalStateException} listing every distinct cycle when any are found
/// - `validate(Collection)` silently skips targets not present in the input — the CLI loader
///   reports missing `--with` declarations separately
/// - `validate(Workflow, Function)` reports unresolved references alongside cycles (server
///   push path)
class SubWorkflowGraphValidatorTest {

    @Test
    void diamondPasses() {
        Workflow a = wf("a", List.of("b", "c"));
        Workflow b = wf("b", List.of("d"));
        Workflow c = wf("c", List.of("d"));
        Workflow d = wf("d", List.of());
        assertThatCode(() -> SubWorkflowGraphValidator.validate(List.of(a, b, c, d)))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownTargetIsIgnored() {
        Workflow a = wf("a", List.of("ghost"));
        assertThatCode(() -> SubWorkflowGraphValidator.validate(List.of(a)))
                .doesNotThrowAnyException();
    }

    @Test
    void selfCycleIsRejected() {
        Workflow a = wf("a", List.of("a"));
        assertThatThrownBy(() -> SubWorkflowGraphValidator.validate(List.of(a)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("a");
    }

    @Test
    void twoNodeCycleIsRejected() {
        Workflow a = wf("a", List.of("b"));
        Workflow b = wf("b", List.of("a"));
        assertThatThrownBy(() -> SubWorkflowGraphValidator.validate(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("a")
                .hasMessageContaining("b");
    }

    @Test
    void disconnectedCycleIsRejected() {
        // Root 'main' is clean; an isolated 'x' ↔ 'y' cycle still blocks the load.
        Workflow main = wf("main", List.of());
        Workflow x = wf("x", List.of("y"));
        Workflow y = wf("y", List.of("x"));
        assertThatThrownBy(() -> SubWorkflowGraphValidator.validate(List.of(main, x, y)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x")
                .hasMessageContaining("y");
    }

    @Test
    void multipleDistinctCyclesAreAllReported() {
        // a ↔ b and c ↔ d — both must show up in the error message.
        Workflow a = wf("a", List.of("b"));
        Workflow b = wf("b", List.of("a"));
        Workflow c = wf("c", List.of("d"));
        Workflow d = wf("d", List.of("c"));
        assertThatThrownBy(() -> SubWorkflowGraphValidator.validate(List.of(a, b, c, d)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a")
                .hasMessageContaining("b")
                .hasMessageContaining("c")
                .hasMessageContaining("d");
    }

    // — Resolver overload — simulates the server push path ——————————————————

    @Test
    void pushingWorkflowWithUnknownReferenceIsRejected() {
        // Pushing A→ghost-b when the repo has no ghost-b must fail at push time, not at
        // runtime. Otherwise, the server stores a workflow with a dangling reference that
        // only blows up when an execution reaches the SubWorkflowNode.
        Workflow incoming = wf("a", List.of("ghost-b"));
        assertThatThrownBy(() -> SubWorkflowGraphValidator.validate(incoming, _ -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown sub-workflows")
                .hasMessageContaining("ghost-b");
    }

    @Test
    void pushingWorkflowThatClosesCycleIsRejected() {
        // Repo already has A→B. User pushes B→A — the new push is what closes the loop.
        Workflow incoming = wf("b", List.of("a"));
        Map<String, Workflow> repo = new HashMap<>();
        repo.put("a", wf("a", List.of("b")));
        assertThatThrownBy(() -> SubWorkflowGraphValidator.validate(incoming, repo::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a")
                .hasMessageContaining("b");
    }

    @Test
    void rePushShadowsStoredVersion() {
        // Repo has a stale B with no outgoing refs. Repo also has A→B. User re-pushes B
        // with B→A. The validator must see the INCOMING B, not the stored one — otherwise
        // the cycle would be missed.
        Workflow staleB = wf("b", List.of());
        Workflow newB = wf("b", List.of("a"));
        Map<String, Workflow> repo = new HashMap<>();
        repo.put("a", wf("a", List.of("b")));
        repo.put("b", staleB);
        assertThatThrownBy(() -> SubWorkflowGraphValidator.validate(newB, repo::get))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolverIsOnlyCalledForReachableIds() {
        // Repo has unrelated noise (n1, n2, n3). Push A→B→C. Walk must fetch only B and C,
        // never the unrelated ids and never 'a' itself (shadowed by the incoming).
        Workflow incoming = wf("a", List.of("b"));
        Map<String, Workflow> repo = new HashMap<>();
        repo.put("b", wf("b", List.of("c")));
        repo.put("c", wf("c", List.of()));
        repo.put("n1", wf("n1", List.of("n2")));
        repo.put("n2", wf("n2", List.of("n3")));
        repo.put("n3", wf("n3", List.of()));
        Set<String> touched = new HashSet<>();
        Function<String, Workflow> tracking =
                id -> {
                    touched.add(id);
                    return repo.get(id);
                };
        SubWorkflowGraphValidator.validate(incoming, tracking);
        // 'a' is shadowed and never queried. 'n1/n2/n3' are unrelated and must not be
        // touched.
        assertThat(touched).doesNotContain("a", "n1", "n2", "n3");
        assertThat(touched).contains("b", "c");
    }

    // — helpers ——————————————————————————————————————————————————————————————

    /// Builds a minimal Workflow with one SubWorkflowNode per declared target.
    /// If `targets` is empty, a single {@link EndNode} is added instead so the workflow has
    /// no outgoing sub-workflow references (a true leaf).
    private static Workflow wf(String id, List<String> targets) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        if (targets.isEmpty()) {
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());
            return Workflow.builder().id(id).nodes(nodes).startNode("end").build();
        }
        for (int i = 0; i < targets.size(); i++) {
            String nodeId = "sub_" + i;
            nodes.put(
                    nodeId,
                    SubWorkflowNode.builder().id(nodeId).workflowId(targets.get(i)).build());
        }
        return Workflow.builder().id(id).nodes(nodes).startNode("sub_0").build();
    }
}
