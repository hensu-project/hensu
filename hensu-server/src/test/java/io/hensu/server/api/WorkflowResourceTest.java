package io.hensu.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.core.workflow.node.EndNode;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WorkflowResourceTest {

    private WorkflowRepository workflowRepository;
    private WorkflowResource resource;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        resource = new WorkflowResource(workflowRepository);
    }

    private Workflow createTestWorkflow(String id, String version) {
        return Workflow.builder()
                .id(id)
                .version(version)
                .startNode("start")
                .nodes(
                        Map.of(
                                "start",
                                EndNode.builder().id("start").status(ExitStatus.SUCCESS).build()))
                .build();
    }

    @Nested
    class PushWorkflow {

        @Test
        void shouldReturn201WhenCreatingNewWorkflow() {
            Workflow workflow = createTestWorkflow("wf-1", "1.0.0");
            when(workflowRepository.exists("tenant-1", "wf-1")).thenReturn(false);

            Map<String, Object> entity;
            try (Response response = resource.pushWorkflow("tenant-1", workflow)) {

                assertThat(response.getStatus()).isEqualTo(201);
                verify(workflowRepository).save("tenant-1", workflow);

                entity = (Map<String, Object>) response.getEntity();
            }
            assertThat(entity.get("id")).isEqualTo("wf-1");
            assertThat(entity.get("version")).isEqualTo("1.0.0");
            assertThat(entity.get("created")).isEqualTo(true);
        }

        @Test
        void shouldReturn200WhenUpdatingExistingWorkflow() {
            Workflow workflow = createTestWorkflow("wf-1", "2.0.0");
            when(workflowRepository.exists("tenant-1", "wf-1")).thenReturn(true);

            Map<String, Object> entity;
            try (Response response = resource.pushWorkflow("tenant-1", workflow)) {

                assertThat(response.getStatus()).isEqualTo(200);
                verify(workflowRepository).save("tenant-1", workflow);

                entity = (Map<String, Object>) response.getEntity();
            }
            assertThat(entity.get("created")).isEqualTo(false);
        }

        @Test
        void shouldReturn400WhenTenantIdMissing() {
            Workflow workflow = createTestWorkflow("wf-1", "1.0.0");

            assertThatThrownBy(
                            () -> {
                                try (var _ = resource.pushWorkflow(null, workflow)) {
                                    // No-op
                                }
                            })
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("X-Tenant-ID");
        }

        @Test
        void shouldReturn400WhenWorkflowNull() {
            assertThatThrownBy(
                            () -> {
                                try (var _ = resource.pushWorkflow("tenant-1", null)) {
                                    // No-op
                                }
                            })
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Workflow definition");
        }
    }

    @Nested
    class PullWorkflow {

        @Test
        void shouldReturnWorkflowWhenFound() {
            Workflow workflow = createTestWorkflow("wf-1", "1.0.0");
            when(workflowRepository.findById("tenant-1", "wf-1")).thenReturn(Optional.of(workflow));

            try (Response response = resource.pullWorkflow("wf-1", "tenant-1")) {

                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(response.getEntity()).isEqualTo(workflow);
            }
        }

        @Test
        void shouldReturn404WhenWorkflowNotFound() {
            when(workflowRepository.findById("tenant-1", "wf-1")).thenReturn(Optional.empty());

            assertThatThrownBy(
                            () -> {
                                try (var _ = resource.pullWorkflow("wf-1", "tenant-1")) {
                                    // No-op
                                }
                            })
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("wf-1");
        }

        @Test
        void shouldReturn400WhenTenantIdMissing() {
            assertThatThrownBy(
                            () -> {
                                try (var _ = resource.pullWorkflow("wf-1", null)) {
                                    // No-op
                                }
                            })
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("X-Tenant-ID");
        }
    }

    @Nested
    class ListWorkflows {

        @Test
        void shouldReturnAllWorkflowsForTenant() {
            List<Workflow> workflows =
                    List.of(
                            createTestWorkflow("wf-1", "1.0.0"),
                            createTestWorkflow("wf-2", "2.0.0"));
            when(workflowRepository.findAll("tenant-1")).thenReturn(workflows);

            List<Map<String, String>> entity;
            try (Response response = resource.listWorkflows("tenant-1")) {

                assertThat(response.getStatus()).isEqualTo(200);
                entity = (List<Map<String, String>>) response.getEntity();
            }
            assertThat(entity).hasSize(2);
            assertThat(entity.get(0).get("id")).isEqualTo("wf-1");
            assertThat(entity.get(1).get("id")).isEqualTo("wf-2");
        }

        @Test
        void shouldReturnEmptyListWhenNoWorkflows() {
            when(workflowRepository.findAll("tenant-1")).thenReturn(List.of());

            List<Map<String, String>> entity;
            try (Response response = resource.listWorkflows("tenant-1")) {

                assertThat(response.getStatus()).isEqualTo(200);
                entity = (List<Map<String, String>>) response.getEntity();
            }
            assertThat(entity).isEmpty();
        }

        @Test
        void shouldReturn400WhenTenantIdMissing() {
            assertThatThrownBy(
                            () -> {
                                try (var _ = resource.listWorkflows(null)) {
                                    // No-op
                                }
                            })
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("X-Tenant-ID");
        }
    }

    @Nested
    class DeleteWorkflow {

        @Test
        void shouldReturn204WhenWorkflowDeleted() {
            when(workflowRepository.delete("tenant-1", "wf-1")).thenReturn(true);

            try (Response response = resource.deleteWorkflow("wf-1", "tenant-1")) {

                assertThat(response.getStatus()).isEqualTo(204);
            }
            verify(workflowRepository).delete("tenant-1", "wf-1");
        }

        @Test
        void shouldReturn404WhenWorkflowNotFound() {
            when(workflowRepository.delete("tenant-1", "wf-1")).thenReturn(false);

            assertThatThrownBy(
                            () -> {
                                try (var _ = resource.deleteWorkflow("wf-1", "tenant-1")) {
                                    // No-op
                                }
                            })
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("wf-1");
        }
    }
}
