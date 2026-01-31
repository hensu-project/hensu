package io.hensu.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowMetadataTest {

    @Test
    void shouldCreateWithAllFields() {
        // Given
        Instant created = Instant.now();
        List<String> tags = List.of("ai", "workflow", "automation");

        // When
        WorkflowMetadata metadata =
                new WorkflowMetadata(
                        "Content Review Workflow",
                        "Workflow for reviewing content quality",
                        "John Doe",
                        created,
                        tags);

        // Then
        assertThat(metadata.name()).isEqualTo("Content Review Workflow");
        assertThat(metadata.getName()).isEqualTo("Content Review Workflow");
        assertThat(metadata.description()).isEqualTo("Workflow for reviewing content quality");
        assertThat(metadata.author()).isEqualTo("John Doe");
        assertThat(metadata.created()).isEqualTo(created);
        assertThat(metadata.tags()).containsExactly("ai", "workflow", "automation");
    }

    @Test
    void shouldAllowNullValues() {
        // When
        WorkflowMetadata metadata = new WorkflowMetadata(null, null, null, null, List.of());

        // Then
        assertThat(metadata.name()).isNull();
        assertThat(metadata.description()).isNull();
        assertThat(metadata.author()).isNull();
        assertThat(metadata.created()).isNull();
    }

    @Test
    void shouldReturnCopyOfTags() {
        // Given
        List<String> originalTags = List.of("tag1", "tag2");
        WorkflowMetadata metadata =
                new WorkflowMetadata("name", "desc", "author", Instant.now(), originalTags);

        // When
        List<String> returnedTags = metadata.tags();

        // Then - should return a copy
        assertThat(returnedTags).isUnmodifiable();
        assertThat(returnedTags).containsExactly("tag1", "tag2");
    }

    @Test
    void shouldSupportEmptyTags() {
        // When
        WorkflowMetadata metadata =
                new WorkflowMetadata("name", "desc", "author", Instant.now(), List.of());

        // Then
        assertThat(metadata.tags()).isEmpty();
    }

    @Test
    void shouldBeRecordType() {
        // Given
        WorkflowMetadata metadata =
                new WorkflowMetadata("name", "desc", "author", Instant.now(), List.of());

        // Then - records have auto-generated equals, hashCode, toString
        assertThat(metadata).isInstanceOf(Record.class);
    }

    @Test
    void shouldImplementEquality() {
        // Given
        Instant timestamp = Instant.now();
        List<String> tags = List.of("tag");

        WorkflowMetadata metadata1 =
                new WorkflowMetadata("name", "desc", "author", timestamp, tags);
        WorkflowMetadata metadata2 =
                new WorkflowMetadata("name", "desc", "author", timestamp, tags);
        WorkflowMetadata metadata3 =
                new WorkflowMetadata("different", "desc", "author", timestamp, tags);

        // Then
        assertThat(metadata1).isEqualTo(metadata2);
        assertThat(metadata1).isNotEqualTo(metadata3);
        assertThat(metadata1.hashCode()).isEqualTo(metadata2.hashCode());
    }

    @Test
    void shouldReturnMeaningfulToString() {
        // Given
        WorkflowMetadata metadata =
                new WorkflowMetadata(
                        "Test Workflow",
                        "A test description",
                        "Test Author",
                        Instant.parse("2024-01-15T10:00:00Z"),
                        List.of("test"));

        // When
        String toString = metadata.toString();

        // Then
        assertThat(toString).contains("Test Workflow");
        assertThat(toString).contains("A test description");
        assertThat(toString).contains("Test Author");
    }
}
