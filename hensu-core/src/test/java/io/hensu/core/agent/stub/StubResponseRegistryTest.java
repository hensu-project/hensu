package io.hensu.core.agent.stub;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StubResponseRegistryTest {

    private StubResponseRegistry registry;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        registry = StubResponseRegistry.getInstance();
        registry.clearResponses();
        registry.clearStubsDirectory();
    }

    @AfterEach
    void tearDown() {
        registry.clearResponses();
        registry.clearStubsDirectory();
    }

    @Test
    void shouldResolveFromFilesystemWhenClasspathMisses() throws IOException {
        // GIVEN
        Path defaultDir = tempDir.resolve("default");
        Files.createDirectories(defaultDir);
        Files.writeString(defaultDir.resolve("fs-node.txt"), "filesystem response");
        registry.setStubsDirectory(tempDir);

        // WHEN
        String response = registry.getResponse("fs-node", null, new HashMap<>(), "prompt");

        // THEN
        assertThat(response).isEqualTo("filesystem response");
    }

    @Test
    void shouldPreferClasspathOverFilesystem() throws IOException {
        // GIVEN — register programmatically (simulates classpath priority)
        registry.registerResponse("classpath-node", "classpath wins");

        Path defaultDir = tempDir.resolve("default");
        Files.createDirectories(defaultDir);
        Files.writeString(defaultDir.resolve("classpath-node.txt"), "filesystem loses");
        registry.setStubsDirectory(tempDir);

        // WHEN
        String response = registry.getResponse("classpath-node", null, new HashMap<>(), "prompt");

        // THEN
        assertThat(response).isEqualTo("classpath wins");
    }

    @Test
    void shouldFallbackToDefaultScenarioOnFilesystem() throws IOException {
        // GIVEN — no "custom" scenario on filesystem, only "default"
        Path defaultDir = tempDir.resolve("default");
        Files.createDirectories(defaultDir);
        Files.writeString(defaultDir.resolve("fallback-node.txt"), "default filesystem");
        registry.setStubsDirectory(tempDir);

        Map<String, Object> context = new HashMap<>();
        context.put("stub_scenario", "custom");

        // WHEN
        String response = registry.getResponse("fallback-node", null, context, "prompt");

        // THEN
        assertThat(response).isEqualTo("default filesystem");
    }

    @Test
    void shouldReturnNullWhenStubsDirectoryNotSet() {
        // GIVEN — no stubs directory, no programmatic registration

        // WHEN
        String response = registry.getResponse("missing-node", null, new HashMap<>(), "prompt");

        // THEN
        assertThat(response).isNull();
    }

    @Test
    void shouldSubstituteTemplateVariablesInFilesystemStubs() throws IOException {
        // GIVEN
        Path defaultDir = tempDir.resolve("default");
        Files.createDirectories(defaultDir);
        Files.writeString(
                defaultDir.resolve("template-node.txt"), "Hello {{name}}, score is {{score}}");
        registry.setStubsDirectory(tempDir);

        Map<String, Object> context = new HashMap<>();
        context.put("name", "Alice");
        context.put("score", "95");

        // WHEN
        String response = registry.getResponse("template-node", null, context, "prompt");

        // THEN
        assertThat(response).isEqualTo("Hello Alice, score is 95");
    }

    @Test
    void shouldResolveFilesystemByAgentIdWhenNodeIdMisses() throws IOException {
        // GIVEN
        Path defaultDir = tempDir.resolve("default");
        Files.createDirectories(defaultDir);
        Files.writeString(defaultDir.resolve("writer.txt"), "agent-level stub");
        registry.setStubsDirectory(tempDir);

        // WHEN — nodeId "unknown" has no stub, agentId "writer" does
        String response = registry.getResponse("unknown", "writer", new HashMap<>(), "prompt");

        // THEN
        assertThat(response).isEqualTo("agent-level stub");
    }

    @Test
    void shouldResolveScenarioSpecificFilesystemStub() throws IOException {
        // GIVEN
        Path scenarioDir = tempDir.resolve("low_score");
        Files.createDirectories(scenarioDir);
        Files.writeString(scenarioDir.resolve("evaluate.txt"), "low score response");
        registry.setStubsDirectory(tempDir);

        Map<String, Object> context = new HashMap<>();
        context.put("stub_scenario", "low_score");

        // WHEN
        String response = registry.getResponse("evaluate", null, context, "prompt");

        // THEN
        assertThat(response).isEqualTo("low score response");
    }

    @Test
    void shouldClearCacheWhenStubsDirectoryChanges() throws IOException {
        // GIVEN — first directory
        Path firstDir = tempDir.resolve("first");
        Path firstDefault = firstDir.resolve("default");
        Files.createDirectories(firstDefault);
        Files.writeString(firstDefault.resolve("node.txt"), "first directory");
        registry.setStubsDirectory(firstDir);

        String first = registry.getResponse("node", null, new HashMap<>(), "prompt");
        assertThat(first).isEqualTo("first directory");

        // WHEN — switch to second directory
        Path secondDir = tempDir.resolve("second");
        Path secondDefault = secondDir.resolve("default");
        Files.createDirectories(secondDefault);
        Files.writeString(secondDefault.resolve("node.txt"), "second directory");
        registry.setStubsDirectory(secondDir);

        // THEN
        String second = registry.getResponse("node", null, new HashMap<>(), "prompt");
        assertThat(second).isEqualTo("second directory");
    }
}
