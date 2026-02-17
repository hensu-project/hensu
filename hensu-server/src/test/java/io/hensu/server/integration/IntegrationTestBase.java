package io.hensu.server.integration;

import io.hensu.core.HensuEnvironment;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.stub.StubResponseRegistry;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.serialization.WorkflowSerializer;
import io.hensu.server.service.WorkflowService;
import io.hensu.server.service.WorkflowService.ExecutionStartResult;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;

/// Shared base class for full-stack integration tests.
///
/// Provides CDI-injected beans, test helpers, and per-test state cleanup.
/// All subclasses run within a bootstrapped Quarkus context with
/// `hensu.stub.enabled=true` (configured in `test/resources/application.properties`),
/// so every agent call is intercepted by {@link io.hensu.core.agent.stub.StubAgentProvider}.
///
/// ### Stub Mode
/// When `hensu.stub.enabled=true`, {@link io.hensu.core.HensuFactory} activates
/// {@link io.hensu.core.agent.stub.StubAgentProvider} (priority 1000), which
/// intercepts **all** model requests regardless of provider. Each agent call
/// is routed to {@link io.hensu.core.agent.stub.StubAgent}, which resolves
/// responses via {@link StubResponseRegistry} in this order:
///
/// 1. Programmatic responses registered via {@link #registerStub}
/// 2. Resource files at `/stubs/{scenario}/{nodeId|agentId}.txt`
/// 3. Default scenario fallback at `/stubs/default/{nodeId|agentId}.txt`
/// 4. Auto-generated fallback (echoes the prompt)
///
/// Tests configure stub responses either programmatically or via classpath
/// resource files placed in `test/resources/stubs/`.
///
/// ### Contracts
/// - **Precondition**: Quarkus test context must be active (`@QuarkusTest`)
/// - **Postcondition**: Each test starts with empty repositories and stub registry
/// - **Invariant**: All executions run under {@link #TEST_TENANT} unless overridden
/// - **Cleanup**: Per-test `@BeforeEach` deletes tenant data via repository interfaces
/// - **Profile**: Uses {@link InMemoryTestProfile} (`inmem`) — no Docker or PostgreSQL required
///
/// @implNote Package-private. Not part of the public API.
///
/// @see WorkflowService for the service layer under test
/// @see StubResponseRegistry for stub response configuration
/// @see io.hensu.core.agent.stub.StubAgentProvider for the stub agent mechanism
@QuarkusTest
@TestProfile(InMemoryTestProfile.class)
abstract class IntegrationTestBase {

    /// Default tenant ID used across all integration tests.
    static final String TEST_TENANT = "test-tenant";

    @Inject WorkflowRepository workflowRepository;
    @Inject WorkflowStateRepository workflowStateRepository;
    @Inject WorkflowService workflowService;
    @Inject AgentRegistry agentRegistry;
    @Inject HensuEnvironment hensuEnvironment;

    /// Resets all mutable test state before each test method.
    ///
    /// @apiNote **Side effects**: clears stub responses, workflow repository,
    /// and workflow state repository
    @BeforeEach
    void resetState() {
        StubResponseRegistry.getInstance().clearResponses();

        // FK: execution_states references workflows — delete states first
        workflowStateRepository.deleteAllForTenant(TEST_TENANT);
        workflowRepository.deleteAllForTenant(TEST_TENANT);
    }

    /// Loads a workflow JSON fixture from the classpath.
    ///
    /// Reads `/workflows/{resourceName}` and deserializes via
    /// {@link WorkflowSerializer#fromJson(String)}.
    ///
    /// @param resourceName file name relative to `/workflows/`, not null
    /// @return deserialized workflow, never null
    /// @throws IllegalArgumentException if the resource is not found
    Workflow loadWorkflow(String resourceName) {
        String json = loadClasspathResource("/workflows/" + resourceName);
        return WorkflowSerializer.fromJson(json);
    }

    /// Saves a workflow to the repository and executes it via the service layer.
    ///
    /// Uses {@link #TEST_TENANT} for both repository storage and execution.
    ///
    /// @param workflow the workflow to persist and execute, not null
    /// @param context initial execution context variables, not null
    /// @return execution result containing the execution ID, never null
    /// @throws io.hensu.server.service.WorkflowService.WorkflowExecutionException if execution
    ///     fails
    ExecutionStartResult pushAndExecute(Workflow workflow, Map<String, Object> context) {
        workflowRepository.save(TEST_TENANT, workflow);
        return workflowService.startExecution(TEST_TENANT, workflow.getId(), context);
    }

    /// Saves a workflow and executes with an MCP-enabled tenant context.
    ///
    /// Wraps execution in {@link TenantContext#runAs} with
    /// {@link TenantInfo#withMcp} so that {@link io.hensu.server.mcp.McpSidecar}
    /// can resolve the tenant's MCP endpoint.
    ///
    /// @param workflow the workflow to persist and execute, not null
    /// @param context initial execution context variables, not null
    /// @param mcpEndpoint the MCP server endpoint (e.g. `"sse://clientId"`), not null
    /// @return execution result, never null
    /// @throws RuntimeException wrapping the underlying exception if execution fails
    ExecutionStartResult pushAndExecuteWithMcp(
            Workflow workflow, Map<String, Object> context, String mcpEndpoint) {
        workflowRepository.save(TEST_TENANT, workflow);

        TenantInfo tenant = TenantInfo.withMcp(TEST_TENANT, mcpEndpoint);
        try {
            return TenantContext.runAs(
                    tenant,
                    () -> workflowService.startExecution(TEST_TENANT, workflow.getId(), context));
        } catch (Exception e) {
            throw new RuntimeException("Execution failed", e);
        }
    }

    /// Registers a programmatic stub response for the default scenario.
    ///
    /// @apiNote **Side effects**: modifies the global {@link StubResponseRegistry}
    ///
    /// @param key node ID or agent ID to match, not null
    /// @param response the response content, not null
    void registerStub(String key, String response) {
        StubResponseRegistry.getInstance().registerResponse(key, response);
    }

    /// Registers a programmatic stub response for a specific scenario.
    ///
    /// @apiNote **Side effects**: modifies the global {@link StubResponseRegistry}
    ///
    /// @param scenario the scenario name (e.g. `"high_score"`), not null
    /// @param key node ID or agent ID to match, not null
    /// @param response the response content, not null
    void registerStub(String scenario, String key, String response) {
        StubResponseRegistry.getInstance().registerResponse(scenario, key, response);
    }

    /// Resolves a classpath rubric resource to a filesystem path.
    ///
    /// {@link io.hensu.core.rubric.RubricParser#parse} requires real filesystem
    /// paths, so this method copies `/rubrics/{resourceName}` to a temporary file
    /// and returns its absolute path.
    ///
    /// @param resourceName rubric file name (e.g. `"quality-high.md"`), not null
    /// @return absolute filesystem path to the temporary copy, never null
    /// @throws RuntimeException if the resource cannot be found or copied
    String resolveRubricPath(String resourceName) {
        try {
            String content = loadClasspathResource("/rubrics/" + resourceName);
            Path tempFile = Files.createTempFile("rubric-", "-" + resourceName);
            Files.writeString(tempFile, content);
            tempFile.toFile().deleteOnExit();
            return tempFile.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to resolve rubric: " + resourceName, e);
        }
    }

    /// Loads a classpath resource as a UTF-8 string.
    ///
    /// @param path absolute classpath path (e.g. `"/workflows/basic.json"`), not null
    /// @return resource content, never null
    /// @throws IllegalArgumentException if the resource is not found
    /// @throws RuntimeException if an I/O error occurs
    private String loadClasspathResource(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load resource: " + path, e);
        }
    }
}
