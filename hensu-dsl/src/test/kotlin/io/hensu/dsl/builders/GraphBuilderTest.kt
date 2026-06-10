package io.hensu.dsl.builders

import io.hensu.dsl.WorkingDirectory
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GraphBuilderTest {

    @TempDir lateinit var tempDir: Path

    private lateinit var workingDir: WorkingDirectory

    @BeforeEach
    fun setUp() {
        Files.createDirectories(tempDir.resolve("workflows"))
        Files.createDirectories(tempDir.resolve("prompts"))
        Files.createDirectories(tempDir.resolve("rubrics"))
        workingDir = WorkingDirectory.of(tempDir)
    }

    @Test
    fun `should validate start node exists in graph`() {
        val builder = GraphBuilder(workingDir)

        builder.apply {
            node("step1") { onSuccess goto "end" }

            end("end")
        }

        assertThatThrownBy { builder.build() }.isInstanceOf(IllegalStateException::class.java)
    }
}
