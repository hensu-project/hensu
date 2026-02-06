package io.hensu.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.server.persistence.WorkflowRepository;
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

            Response response = resource.pushWorkflow("tenant-1", workflow);

            assertThat(response.getStatus()).isEqualTo(201);
            verify(workflowRepository).save("tenant-1", workflow);

            @SuppressWarnings("unchecked")
            Map<String, Object> entity = (Map<String, Object>) response.getEntity();
            assertThat(entity.get("id")).isEqualTo("wf-1");
            assertThat(entity.get("version")).isEqualTo("1.0.0");
            assertThat(entity.get("created")).isEqualTo(true);
        }

        @Test
        void shouldReturn200WhenUpdatingExistingWorkflow() {
            Workflow workflow = createTestWorkflow("wf-1", "2.0.0");
            when(workflowRepository.exists("tenant-1", "wf-1")).thenReturn(true);

            Response response = resource.pushWorkflow("tenant-1", workflow);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(workflowRepository).save("tenant-1", workflow);

            @SuppressWarnings("unchecked")
            Map<String, Object> entity = (Map<String, Object>) response.getEntity();
            assertThat(entity.get("created")).isEqualTo(false);
        }

        @Test
        void shouldReturn400WhenTenantIdMissing() {
            Workflow workflow = createTestWorkflow("wf-1", "1.0.0");

            try {
                resource.pushWorkflow(null, workflow);
            } catch (BadRequestException e) {
                assertThat(e.getMessage()).contains("X-Tenant-ID");
            }
        }

        @Test
        void shouldReturn400WhenWorkflowNull() {
            try {
                resource.pushWorkflow("tenant-1", null);
            } catch (BadRequestException e) {
                assertThat(e.getMessage()).contains("Workflow definition");
            }
        }
    }

    @Nested
    class PullWorkflow {

        @Test
        void shouldReturnWorkflowWhenFound() {
            Workflow workflow = createTestWorkflow("wf-1", "1.0.0");
            when(workflowRepository.findById("tenant-1", "wf-1")).thenReturn(Optional.of(workflow));

            Response response = resource.pullWorkflow("wf-1", "tenant-1");

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getEntity()).isEqualTo(workflow);
        }

        @Test
        void shouldReturn404WhenWorkflowNotFound() {
            when(workflowRepository.findById("tenant-1", "wf-1")).thenReturn(Optional.empty());

            try {
                resource.pullWorkflow("wf-1", "tenant-1");
            } catch (NotFoundException e) {
                assertThat(e.getMessage()).contains("wf-1");
            }
        }

        @Test
        void shouldReturn400WhenTenantIdMissing() {
            try {
                resource.pullWorkflow("wf-1", null);
            } catch (BadRequestException e) {
                assertThat(e.getMessage()).contains("X-Tenant-ID");
            }
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

            Response response = resource.listWorkflows("tenant-1");

            assertThat(response.getStatus()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            List<Map<String, String>> entity = (List<Map<String, String>>) response.getEntity();
            assertThat(entity).hasSize(2);
            assertThat(entity.get(0).get("id")).isEqualTo("wf-1");
            assertThat(entity.get(1).get("id")).isEqualTo("wf-2");
        }

        @Test
        void shouldReturnEmptyListWhenNoWorkflows() {
            when(workflowRepository.findAll("tenant-1")).thenReturn(List.of());

            Response response = resource.listWorkflows("tenant-1");

            assertThat(response.getStatus()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            List<Map<String, String>> entity = (List<Map<String, String>>) response.getEntity();
            assertThat(entity).isEmpty();
        }

        @Test
        void shouldReturn400WhenTenantIdMissing() {
            try {
                resource.listWorkflows(null);
            } catch (BadRequestException e) {
                assertThat(e.getMessage()).contains("X-Tenant-ID");
            }
        }
    }

    @Nested
    class DeleteWorkflow {

        @Test
        void shouldReturn204WhenWorkflowDeleted() {
            when(workflowRepository.delete("tenant-1", "wf-1")).thenReturn(true);

            Response response = resource.deleteWorkflow("wf-1", "tenant-1");

            assertThat(response.getStatus()).isEqualTo(204);
            verify(workflowRepository).delete("tenant-1", "wf-1");
        }

        @Test
        void shouldReturn404WhenWorkflowNotFound() {
            when(workflowRepository.delete("tenant-1", "wf-1")).thenReturn(false);

            try {
                resource.deleteWorkflow("wf-1", "tenant-1");
            } catch (NotFoundException e) {
                assertThat(e.getMessage()).contains("wf-1");
            }
        }

        @Test
        void shouldReturn400WhenTenantIdMissing() {
            try {
                resource.deleteWorkflow("wf-1", null);
            } catch (BadRequestException e) {
                assertThat(e.getMessage()).contains("X-Tenant-ID");
            }
        }
    }
}
