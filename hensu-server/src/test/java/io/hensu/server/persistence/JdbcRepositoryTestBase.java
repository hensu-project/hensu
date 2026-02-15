package io.hensu.server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.serialization.WorkflowSerializer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/// Shared Testcontainers setup for JDBC repository tests.
///
/// Starts a PostgreSQL container per test class, runs Flyway migrations,
/// and provides a shared {@link DataSource} and {@link ObjectMapper} to subclasses.
///
/// @implNote Package-private. Not part of the public API.
///
/// @see JdbcWorkflowRepositoryTest
/// @see JdbcWorkflowStateRepositoryTest
@Testcontainers
abstract class JdbcRepositoryTestBase {

    static final String TENANT = "test-tenant";
    static final String OTHER_TENANT = "other-tenant";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static DataSource dataSource;
    static ObjectMapper objectMapper;

    /// Initializes the PostgreSQL DataSource and runs Flyway migrations.
    ///
    /// @apiNote **Side effects**: creates `hensu` schema and all persistence tables
    @BeforeAll
    static void initPostgres() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .schemas("hensu")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();

        objectMapper = WorkflowSerializer.createMapper();
    }

    /// Builds a minimal two-node workflow for persistence round-trip testing.
    ///
    /// @param id the workflow identifier, not null
    /// @return a valid workflow with `process` and `done` nodes, never null
    static Workflow buildWorkflow(String id) {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "writer", AgentConfig.builder().id("writer").role("writer").model("stub").build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "process",
                StandardNode.builder()
                        .id("process")
                        .agentId("writer")
                        .prompt("Write about {topic}")
                        .transitionRules(List.of(new SuccessTransition("done")))
                        .build());
        nodes.put("done", EndNode.builder().id("done").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id(id)
                .version("1.0.0")
                .agents(agents)
                .nodes(nodes)
                .startNode("process")
                .build();
    }
}
