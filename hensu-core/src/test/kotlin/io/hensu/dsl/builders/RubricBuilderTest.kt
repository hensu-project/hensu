package io.hensu.dsl.builders

import io.hensu.dsl.WorkingDirectory
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RubricBuilderTest {
    @TempDir lateinit var tempDir: Path

    private lateinit var workingDir: WorkingDirectory

    @BeforeEach
    fun setUp() {
        Files.createDirectories(tempDir.resolve("workflows"))
        Files.createDirectories(tempDir.resolve("prompts"))
        Files.createDirectories(tempDir.resolve("rubrics"))
        Files.createDirectories(tempDir.resolve("rubrics/templates"))
        workingDir = WorkingDirectory.of(tempDir)
    }

    @Nested
    inner class RubricRegistryBuilderTest {
        @Test
        fun `should register rubric with simple name`() {
            // Given
            val rubricFile = tempDir.resolve("rubrics/code-quality.md")
            Files.writeString(rubricFile, "# Code Quality Rubric")

            val rubrics = mutableMapOf<String, String>()
            val registry = RubricRegistryBuilder(rubrics, workingDir)

            // When
            registry.rubric("quality", "code-quality.md")

            // Then
            assertThat(rubrics).hasSize(1)
            assertThat(rubrics["quality"]).isEqualTo(rubricFile.toString())
        }

        @Test
        fun `should register rubric without extension`() {
            // Given
            val rubricFile = tempDir.resolve("rubrics/quality.md")
            Files.writeString(rubricFile, "# Quality Rubric")

            val rubrics = mutableMapOf<String, String>()
            val registry = RubricRegistryBuilder(rubrics, workingDir)

            // When
            registry.rubric("quality", "quality")

            // Then
            assertThat(rubrics["quality"]).isEqualTo(rubricFile.toString())
        }

        @Test
        fun `should register rubric in subdirectory`() {
            // Given
            val rubricFile = tempDir.resolve("rubrics/templates/pr-quality.md")
            Files.writeString(rubricFile, "# PR Quality Rubric")

            val rubrics = mutableMapOf<String, String>()
            val registry = RubricRegistryBuilder(rubrics, workingDir)

            // When
            registry.rubric("pr", "templates/pr-quality.md")

            // Then
            assertThat(rubrics["pr"]).isEqualTo(rubricFile.toString())
        }

        @Test
        fun `should register multiple rubrics`() {
            // Given
            Files.writeString(tempDir.resolve("rubrics/quality.md"), "# Quality")
            Files.writeString(tempDir.resolve("rubrics/security.md"), "# Security")
            Files.writeString(tempDir.resolve("rubrics/performance.md"), "# Performance")

            val rubrics = mutableMapOf<String, String>()
            val registry = RubricRegistryBuilder(rubrics, workingDir)

            // When
            registry.rubric("quality", "quality.md")
            registry.rubric("security", "security.md")
            registry.rubric("performance", "performance.md")

            // Then
            assertThat(rubrics).hasSize(3)
            assertThat(rubrics).containsKeys("quality", "security", "performance")
        }

        @Test
        fun `should throw when rubric file does not exist`() {
            // Given
            val rubrics = mutableMapOf<String, String>()
            val registry = RubricRegistryBuilder(rubrics, workingDir)

            // When/Then
            assertThatThrownBy { registry.rubric("missing", "non-existent.md") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Rubric not found")
        }

        @Test
        fun `should register rubric using DSL block`() {
            // Given
            val rubricFile = tempDir.resolve("rubrics/code-quality.md")
            Files.writeString(rubricFile, "# Code Quality")

            val rubrics = mutableMapOf<String, String>()
            val registry = RubricRegistryBuilder(rubrics, workingDir)

            // When
            registry.rubric("quality") { file = "code-quality.md" }

            // Then
            assertThat(rubrics["quality"]).isEqualTo(rubricFile.toString())
        }

        @Test
        fun `should register rubric with subdirectory path using DSL block`() {
            // Given
            val rubricFile = tempDir.resolve("rubrics/templates/api-design.md")
            Files.writeString(rubricFile, "# API Design Rubric")

            val rubrics = mutableMapOf<String, String>()
            val registry = RubricRegistryBuilder(rubrics, workingDir)

            // When
            registry.rubric("api") { file = "templates/api-design.md" }

            // Then
            assertThat(rubrics["api"]).isEqualTo(rubricFile.toString())
        }

        @Test
        fun `should overwrite rubric with same id`() {
            // Given
            Files.writeString(tempDir.resolve("rubrics/first.md"), "# First")
            Files.writeString(tempDir.resolve("rubrics/second.md"), "# Second")

            val rubrics = mutableMapOf<String, String>()
            val registry = RubricRegistryBuilder(rubrics, workingDir)

            // When
            registry.rubric("quality", "first.md")
            registry.rubric("quality", "second.md")

            // Then
            assertThat(rubrics).hasSize(1)
            assertThat(rubrics["quality"]).contains("second.md")
        }
    }

    @Nested
    inner class RubricRefBuilderTest {
        @Test
        fun `should have empty file by default`() {
            // Given
            val builder = RubricRefBuilder()

            // Then
            assertThat(builder.file).isEmpty()
        }

        @Test
        fun `should set file property`() {
            // Given
            val builder = RubricRefBuilder()

            // When
            builder.file = "my-rubric.md"

            // Then
            assertThat(builder.file).isEqualTo("my-rubric.md")
        }

        @Test
        fun `should work with DSL apply block`() {
            // Given
            val builder = RubricRefBuilder()

            // When
            builder.apply { file = "templates/code-review.md" }

            // Then
            assertThat(builder.file).isEqualTo("templates/code-review.md")
        }
    }
}
