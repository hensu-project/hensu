package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowMetadata;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InMemoryWorkflowRepositoryTest {

    private InMemoryWorkflowRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryWorkflowRepository();
    }

    private Workflow createWorkflow(String id) {
        Map<String, AgentConfig> agents =
                Map.of(
                        "agent-1",
                        AgentConfig.builder()
                                .id("agent-1")
                                .role("Test")
                                .model("test-model")
                                .build());
        Map<String, Node> nodes =
                Map.of("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());
        return Workflow.builder()
                .id(id)
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                id, "Test workflow", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("end")
                .build();
    }

    @Nested
    class Save {

        @Test
        void shouldSaveAndRetrieve() {
            Workflow workflow = createWorkflow("wf-1");

            repository.save("tenant-1", workflow);

            assertThat(repository.findById("tenant-1", "wf-1")).isPresent();
        }

        @Test
        void shouldOverwriteExisting() {
            Workflow v1 = createWorkflow("wf-1");
            repository.save("tenant-1", v1);

            Workflow v2 =
                    Workflow.builder()
                            .id("wf-1")
                            .version("2.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "wf-1", "Updated", "tester", Instant.now(), List.of()))
                            .agents(v1.getAgents())
                            .nodes(v1.getNodes())
                            .startNode("end")
                            .build();
            repository.save("tenant-1", v2);

            assertThat(repository.findById("tenant-1", "wf-1"))
                    .isPresent()
                    .hasValueSatisfying(wf -> assertThat(wf.getVersion()).isEqualTo("2.0.0"));
        }
    }

    @Nested
    class FindById {

        @Test
        void shouldReturnEmptyForUnknownWorkflow() {
            assertThat(repository.findById("tenant-1", "nonexistent")).isEmpty();
        }

        @Test
        void shouldReturnEmptyForUnknownTenant() {
            repository.save("tenant-1", createWorkflow("wf-1"));
            assertThat(repository.findById("tenant-2", "wf-1")).isEmpty();
        }
    }

    @Nested
    class FindAll {

        @Test
        void shouldReturnAllForTenant() {
            repository.save("tenant-1", createWorkflow("wf-1"));
            repository.save("tenant-1", createWorkflow("wf-2"));

            List<Workflow> results = repository.findAll("tenant-1");
            assertThat(results).hasSize(2);
        }

        @Test
        void shouldReturnEmptyForUnknownTenant() {
            assertThat(repository.findAll("unknown")).isEmpty();
        }
    }

    @Nested
    class Exists {

        @Test
        void shouldReturnTrueWhenPresent() {
            repository.save("tenant-1", createWorkflow("wf-1"));
            assertThat(repository.exists("tenant-1", "wf-1")).isTrue();
        }

        @Test
        void shouldReturnFalseWhenAbsent() {
            assertThat(repository.exists("tenant-1", "wf-1")).isFalse();
        }
    }

    @Nested
    class Delete {

        @Test
        void shouldDeleteExisting() {
            repository.save("tenant-1", createWorkflow("wf-1"));

            assertThat(repository.delete("tenant-1", "wf-1")).isTrue();
            assertThat(repository.findById("tenant-1", "wf-1")).isEmpty();
        }

        @Test
        void shouldReturnFalseForMissing() {
            assertThat(repository.delete("tenant-1", "wf-1")).isFalse();
        }
    }

    @Nested
    class DeleteAllForTenant {

        @Test
        void shouldDeleteAllAndReturnCount() {
            repository.save("tenant-1", createWorkflow("wf-1"));
            repository.save("tenant-1", createWorkflow("wf-2"));

            assertThat(repository.deleteAllForTenant("tenant-1")).isEqualTo(2);
            assertThat(repository.findAll("tenant-1")).isEmpty();
        }

        @Test
        void shouldReturnZeroForUnknownTenant() {
            assertThat(repository.deleteAllForTenant("unknown")).isEqualTo(0);
        }
    }

    @Nested
    class Count {

        @Test
        void shouldReturnCorrectCount() {
            repository.save("tenant-1", createWorkflow("wf-1"));
            repository.save("tenant-1", createWorkflow("wf-2"));

            assertThat(repository.count("tenant-1")).isEqualTo(2);
        }

        @Test
        void shouldReturnZeroForUnknownTenant() {
            assertThat(repository.count("unknown")).isEqualTo(0);
        }
    }

    @Nested
    class Clear {

        @Test
        void shouldClearAllTenants() {
            repository.save("tenant-1", createWorkflow("wf-1"));
            repository.save("tenant-2", createWorkflow("wf-2"));

            repository.clear();

            assertThat(repository.count("tenant-1")).isEqualTo(0);
            assertThat(repository.count("tenant-2")).isEqualTo(0);
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void shouldIsolateWorkflowsBetweenTenants() {
            repository.save("tenant-1", createWorkflow("wf-1"));
            repository.save("tenant-2", createWorkflow("wf-2"));

            assertThat(repository.findAll("tenant-1")).hasSize(1);
            assertThat(repository.findAll("tenant-2")).hasSize(1);
            assertThat(repository.findById("tenant-1", "wf-2")).isEmpty();
            assertThat(repository.findById("tenant-2", "wf-1")).isEmpty();
        }
    }

    @Nested
    class NullSafety {

        @Test
        void shouldRejectNullTenantId() {
            assertThatThrownBy(() -> repository.save(null, createWorkflow("wf-1")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullWorkflow() {
            assertThatThrownBy(() -> repository.save("tenant-1", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
