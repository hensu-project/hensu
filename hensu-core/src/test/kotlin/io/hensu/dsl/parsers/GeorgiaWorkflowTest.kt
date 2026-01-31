package io.hensu.dsl.parsers

import io.hensu.dsl.WorkingDirectory
import java.nio.file.Path
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test

class GeorgiaWorkflowTest {

    private val parser = KotlinScriptParser()

    @Test
    fun `test Georgia workflow parsing`() {
        // Parse the Georgia workflow from working-dir (relative to project root)
        val projectRoot = Path.of(System.getProperty("user.dir")).parent
        val workingDir = WorkingDirectory.of(projectRoot.resolve("working-dir"))

        val workflow = parser.parse(workingDir, "georgia-discovery")

        // Verify workflow metadata
        assertThat(workflow.metadata.name).isEqualTo("GeorgiaDiscovery")
        assertThat(workflow.metadata.description)
            .isEqualTo("Discover the beautiful country of Georgia")

        // Verify agents
        assertThat(workflow.agents).hasSize(1)
        assertThat(workflow.agents).containsKey("explorer")
        val explorer = workflow.agents["explorer"] ?: return
        assertThat(explorer.model).isEqualTo("gemini-2.5-flash")

        // Verify nodes
        assertThat(workflow.nodes).hasSize(3) // introduction, mountain, end
        assertThat(workflow.nodes).containsKeys("introduction", "mountain", "end")

        // Verify start node
        assertThat(workflow.startNode).isEqualTo("introduction")

        // Print workflow info for debugging
        println("Workflow: ${workflow.metadata.name}")
        println("Agents: ${workflow.agents.keys}")
        println("Nodes: ${workflow.nodes.keys}")
        println("Start: ${workflow.startNode}")
    }
}
