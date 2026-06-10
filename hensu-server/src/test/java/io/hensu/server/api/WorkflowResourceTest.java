package io.hensu.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.server.security.RequestTenantResolver;
import io.hensu.server.workflow.WorkflowNotFoundException;
import io.hensu.server.workflow.WorkflowRegistryService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WorkflowResourceTest {

    private WorkflowRegistryService registryService;
    private WorkflowResource resource;

    @BeforeEach
    void setUp() {
        registryService = mock(WorkflowRegistryService.class);
        RequestTenantResolver tenantResolver = mock(RequestTenantResolver.class);
        when(tenantResolver.tenantId()).thenReturn("tenant-1");
        resource = new WorkflowResource(registryService, tenantResolver);
    }

    private Workflow workflow(String id, String version) {
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
            Workflow wf = workflow("wf-1", "1.0.0");
            when(registryService.pushWorkflow("tenant-1", wf)).thenReturn(true);

            try (Response response = resource.pushWorkflow(wf)) {
                assertThat(response.getStatus()).isEqualTo(201);
                var entity = (PushWorkflowResponse) response.getEntity();
                assertThat(entity.id()).isEqualTo("wf-1");
                assertThat(entity.version()).isEqualTo("1.0.0");
                assertThat(entity.created()).isTrue();
            }
            verify(registryService).pushWorkflow("tenant-1", wf);
        }

        @Test
        void shouldReturn200WhenUpdatingExistingWorkflow() {
            Workflow wf = workflow("wf-1", "2.0.0");
            when(registryService.pushWorkflow("tenant-1", wf)).thenReturn(false);

            try (Response response = resource.pushWorkflow(wf)) {
                assertThat(response.getStatus()).isEqualTo(200);
                var entity = (PushWorkflowResponse) response.getEntity();
                assertThat(entity.created()).isFalse();
            }
        }

        @Test
        void shouldReturn400WhenRegistryRejectsCycle() {
            Workflow wf = workflow("wf-1", "1.0.0");
            when(registryService.pushWorkflow("tenant-1", wf))
                    .thenThrow(
                            new IllegalStateException(
                                    "Sub-workflow reference cycle detected: wf-1 -> wf-2 -> wf-1"));

            assertThatThrownBy(
                            () -> {
                                try (var _ = resource.pushWorkflow(wf)) {
                                    // closed by try
                                }
                            })
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("cycle");
        }
    }

    @Nested
    class PullWorkflow {

        @Test
        void shouldReturnWorkflowWhenFound() {
            Workflow wf = workflow("wf-1", "1.0.0");
            when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(wf);

            try (Response response = resource.pullWorkflow("wf-1")) {
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(response.getEntity()).isEqualTo(wf);
            }
        }

        @Test
        void shouldReturn404WhenRegistryThrowsWorkflowNotFound() {
            when(registryService.getWorkflow("tenant-1", "wf-1"))
                    .thenThrow(new WorkflowNotFoundException("Workflow not found: wf-1"));

            assertThatThrownBy(
                            () -> {
                                try (var _ = resource.pullWorkflow("wf-1")) {
                                    // closed by try
                                }
                            })
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("wf-1");
        }
    }

    @Nested
    class ListWorkflows {

        @Test
        @SuppressWarnings("unchecked")
        void shouldMapWorkflowsToIdVersionSummaries() {
            when(registryService.listWorkflows("tenant-1"))
                    .thenReturn(List.of(workflow("wf-1", "1.0.0"), workflow("wf-2", "2.0.0")));

            try (Response response = resource.listWorkflows()) {
                assertThat(response.getStatus()).isEqualTo(200);
                var entity = (List<WorkflowSummary>) response.getEntity();
                assertThat(entity).hasSize(2);
                assertThat(entity.get(0)).isEqualTo(new WorkflowSummary("wf-1", "1.0.0"));
                assertThat(entity.get(1)).isEqualTo(new WorkflowSummary("wf-2", "2.0.0"));
            }
        }
    }

    @Nested
    class DeleteWorkflow {

        @Test
        void shouldReturn204WhenRegistryDeletes() {
            when(registryService.deleteWorkflow("tenant-1", "wf-1")).thenReturn(true);

            try (Response response = resource.deleteWorkflow("wf-1")) {
                assertThat(response.getStatus()).isEqualTo(204);
            }
            verify(registryService).deleteWorkflow("tenant-1", "wf-1");
        }

        @Test
        void shouldReturn404WhenRegistryReportsNothingDeleted() {
            when(registryService.deleteWorkflow("tenant-1", "wf-1")).thenReturn(false);

            assertThatThrownBy(
                            () -> {
                                try (var _ = resource.deleteWorkflow("wf-1")) {
                                    // closed by try
                                }
                            })
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("wf-1");
        }
    }
}
