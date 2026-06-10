package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.workflow.Workflow;
import io.hensu.server.workflow.WorkflowNotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Integration tests for workflow lifecycle error boundaries.
///
/// Covers multi-tenant isolation and missing-workflow error handling.
/// Push-then-execute and status retrieval are covered by
/// {@link StandardNodeIntegrationTest#shouldExecuteBasicStandardNode()}.
///
/// ### Contracts
/// - **Precondition**: Stub mode enabled (`hensu.stub.enabled=true`)
/// - **Invariant**: All executions use {@link #TEST_TENANT} unless explicitly overridden
///
/// @see IntegrationTestBase for shared test infrastructure
/// @see io.hensu.server.workflow.WorkflowService for the service layer under test
@QuarkusTest
class WorkflowLifecycleIntegrationTest extends IntegrationTestBase {

    @Test
    void shouldIsolateTenants() {
        Workflow workflow = loadWorkflow("standard-basic.json");
        workflowRepository.save(TEST_TENANT, workflow);

        assertThatThrownBy(
                        () ->
                                workflowService.startExecution(
                                        "other-tenant", workflow.getId(), Map.of()))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    @Test
    void shouldReturn404ForMissingWorkflow() {
        assertThatThrownBy(
                        () ->
                                workflowService.startExecution(
                                        TEST_TENANT, "non-existent-id", Map.of()))
                .isInstanceOf(WorkflowNotFoundException.class);
    }
}
