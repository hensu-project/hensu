package io.hensu.cli.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.InMemoryWorkflowRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.node.SubWorkflowNode;
import io.hensu.core.workflow.state.StateVariableDeclaration;
import io.hensu.core.workflow.state.VarType;
import io.hensu.core.workflow.state.WorkflowStateSchema;
import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.dsl.WorkingDirectory;
import io.hensu.dsl.parsers.KotlinScriptParser;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Aggregator-level tests for {@link SubWorkflowLoader}. Happy path plus every
/// distinct error mode — duplicate-id divergence, missing declarations, pin
/// mismatches, and cross-workflow binding violations.
class SubWorkflowLoaderTest {

    private KotlinScriptParser kotlinParser;
    private WorkflowRepository workflowRepository;
    private WorkingDirectory workingDir;
    private SubWorkflowLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        kotlinParser = mock(KotlinScriptParser.class);
        workflowRepository = new InMemoryWorkflowRepository();
        workingDir = mock(WorkingDirectory.class);
        loader = new SubWorkflowLoader();
        inject(loader, "kotlinParser", kotlinParser);
        inject(loader, "workflowRepository", workflowRepository);
    }

    @Test
    void shouldSaveRootAndEveryDeclaredSubToRepository() {
        Workflow root = parentWithSubRef("root", "sub-a", Map.of("draft", "draft"), Map.of());
        Workflow subA = childProducing("sub-a", "1.0.0", "draft", "draft");
        when(kotlinParser.parse(eq(workingDir), eq("sub-a"))).thenReturn(subA);

        assertThatCode(() -> loader.resolveDeclared(workingDir, root, List.of("sub-a")))
                .doesNotThrowAnyException();

        assertThat(workflowRepository.findById(SubWorkflowLoader.CLI_TENANT, "root"))
                .contains(root);
        assertThat(workflowRepository.findById(SubWorkflowLoader.CLI_TENANT, "sub-a"))
                .contains(subA);
    }

    @Test
    void shouldFailWhenParentReferencesSubNotSuppliedViaWith() {
        Workflow root = parentWithSubRef("root", "sub-missing", Map.of(), Map.of());

        assertThatThrownBy(() -> loader.resolveDeclared(workingDir, root, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sub-missing")
                .hasMessageContaining("not supplied via --with");
    }

    @Test
    void shouldRejectDuplicateIdWithDivergentContent() {
        Workflow root = parentWithSubRef("root", "sub-a", Map.of(), Map.of());
        Workflow subA = childProducing("sub-a", "1.0.0", "draft", "draft");
        // Same id + version, different schema/writes → different hash.
        Workflow subADivergent = childProducing("sub-a", "1.0.0", "summary", "summary");
        when(kotlinParser.parse(eq(workingDir), eq("sub-a"))).thenReturn(subA);
        when(kotlinParser.parse(eq(workingDir), eq("sub-a-dup"))).thenReturn(subADivergent);

        assertThatThrownBy(
                        () ->
                                loader.resolveDeclared(
                                        workingDir, root, List.of("sub-a", "sub-a-dup")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declared twice")
                .hasMessageContaining("conflicting definitions");
    }

    @Test
    void shouldAllowDuplicateIdWithIdenticalContent() {
        Workflow root = parentWithSubRef("root", "sub-a", Map.of(), Map.of());
        Workflow subA = childProducing("sub-a", "1.0.0", "draft", "draft");
        // Same instance returned for both --with names → same serialized hash.
        when(kotlinParser.parse(eq(workingDir), eq("sub-a"))).thenReturn(subA);
        when(kotlinParser.parse(eq(workingDir), eq("sub-a-again"))).thenReturn(subA);

        assertThatCode(
                        () ->
                                loader.resolveDeclared(
                                        workingDir, root, List.of("sub-a", "sub-a-again")))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectTargetVersionPinMismatch() {
        Workflow root = parentWithPinnedSubRef("root", "sub-a", "1.0.0", Map.of(), Map.of());
        Workflow subA = childProducing("sub-a", "2.0.0", "draft", "draft");
        when(kotlinParser.parse(eq(workingDir), eq("sub-a"))).thenReturn(subA);

        assertThatThrownBy(() -> loader.resolveDeclared(workingDir, root, List.of("sub-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pins sub-workflow 'sub-a' to version '1.0.0'")
                .hasMessageContaining("child declares '2.0.0'");
    }

    @Test
    void shouldRejectImportsNotDeclaredInChildSchema() {
        // Parent imports 'draft' but child's schema declares only 'summary'.
        Workflow root = parentWithSubRef("root", "sub-a", Map.of("draft", "draft"), Map.of());
        Workflow subA = childProducing("sub-a", "1.0.0", "summary", "summary");
        when(kotlinParser.parse(eq(workingDir), eq("sub-a"))).thenReturn(subA);

        assertThatThrownBy(() -> loader.resolveDeclared(workingDir, root, List.of("sub-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("imports 'draft'")
                .hasMessageContaining("child schema does not declare it");
    }

    @Test
    void shouldRejectWritesDeclaredInChildSchemaButNotProducedByAnyChildNode() {
        // Child schema declares 'tl_dr' but no node writes it — parent's expectation
        // can never be satisfied at runtime.
        Workflow root = parentWithSubRef("root", "sub-a", Map.of(), Map.of("tl_dr", "tl_dr"));
        Workflow subA = childProducing("sub-a", "1.0.0", "tl_dr", /* writeVar */ null);
        when(kotlinParser.parse(eq(workingDir), eq("sub-a"))).thenReturn(subA);

        assertThatThrownBy(() -> loader.resolveDeclared(workingDir, root, List.of("sub-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expects sub-workflow 'sub-a' to write 'tl_dr'")
                .hasMessageContaining("no node in the child writes that variable");
    }

    // ——— helpers ———

    private static Workflow parentWithSubRef(
            String id,
            String targetWorkflowId,
            Map<String, String> inputMapping,
            Map<String, String> outputMapping) {
        return buildParent(id, targetWorkflowId, null, inputMapping, outputMapping);
    }

    private static Workflow parentWithPinnedSubRef(
            String id,
            String targetWorkflowId,
            String pinnedVersion,
            Map<String, String> inputMapping,
            Map<String, String> outputMapping) {
        return buildParent(id, targetWorkflowId, pinnedVersion, inputMapping, outputMapping);
    }

    private static Workflow buildParent(
            String id,
            String targetWorkflowId,
            String pinnedVersion,
            Map<String, String> inputMapping,
            Map<String, String> outputMapping) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        nodes.put(
                "delegate",
                SubWorkflowNode.builder()
                        .id("delegate")
                        .workflowId(targetWorkflowId)
                        .targetVersion(pinnedVersion)
                        .inputMapping(inputMapping)
                        .outputMapping(outputMapping)
                        .build());
        return Workflow.builder()
                .id(id)
                .version("1.0.0")
                .startNode("delegate")
                .nodes(nodes)
                .build();
    }

    /// Builds a child workflow with {@code schemaVar} in its state schema. If {@code
    /// writeVar} is non-null, a {@link StandardNode} that writes that variable is added
    /// before the exit node; otherwise only the schema declares the variable.
    private static Workflow childProducing(
            String id, String version, String schemaVar, String writeVar) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        String startNode;
        if (writeVar != null) {
            nodes.put(
                    "produce",
                    StandardNode.builder()
                            .id("produce")
                            .agentId("agent-stub")
                            .prompt("produce " + writeVar)
                            .writes(List.of(writeVar))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            startNode = "produce";
        } else {
            startNode = "end";
        }
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());
        return Workflow.builder()
                .id(id)
                .version(version)
                .startNode(startNode)
                .nodes(nodes)
                .stateSchema(
                        new WorkflowStateSchema(
                                List.of(
                                        new StateVariableDeclaration(
                                                schemaVar, VarType.STRING, false))))
                .build();
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
