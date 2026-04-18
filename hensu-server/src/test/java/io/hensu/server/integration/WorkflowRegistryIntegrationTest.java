package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.SubWorkflowNode;
import io.hensu.server.workflow.WorkflowRegistryService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Full-stack verification that {@link WorkflowRegistryService} rejects cyclic pushes
/// atomically with the live CDI-wired repository.
///
/// The validator logic is covered by {@code SubWorkflowGraphValidatorTest} and REST
/// exception mapping by {@code WorkflowResourceTest}. The gap only real wiring can
/// expose is: a rejected push must leave the existing workflow byte-identical to its
/// pre-push state — validate-then-save must not silently swap order in production.
@QuarkusTest
class WorkflowRegistryIntegrationTest extends IntegrationTestBase {

    @Inject WorkflowRegistryService registryService;

    @Test
    void shouldRejectClosingCycleAndPreserveExistingWorkflow() {
        Workflow cleanA = cleanWorkflow();
        Workflow bReferencingA = withSubWorkflowNode("b", "a");
        registryService.pushWorkflow(TEST_TENANT, cleanA);
        registryService.pushWorkflow(TEST_TENANT, bReferencingA);

        Workflow aClosingCycle = withSubWorkflowNode("a", "b");
        assertThatThrownBy(() -> registryService.pushWorkflow(TEST_TENANT, aClosingCycle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");

        Workflow storedA = workflowRepository.findById(TEST_TENANT, "a").orElseThrow();
        assertThat(storedA.getNodes().values())
                .as("stored A must not have been overwritten by the rejected cyclic push")
                .noneMatch(SubWorkflowNode.class::isInstance);
    }

    private static Workflow cleanWorkflow() {
        Map<String, Node> nodes = new LinkedHashMap<>();
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());
        return Workflow.builder().id("a").startNode("end").nodes(nodes).build();
    }

    private static Workflow withSubWorkflowNode(String id, String targetWorkflowId) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        nodes.put("sub", SubWorkflowNode.builder().id("sub").workflowId(targetWorkflowId).build());
        return Workflow.builder().id(id).startNode("sub").nodes(nodes).build();
    }
}
