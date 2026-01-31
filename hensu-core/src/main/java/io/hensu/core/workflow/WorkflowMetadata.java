package io.hensu.core.workflow;

import java.time.Instant;
import java.util.List;

public record WorkflowMetadata(
        String name, String description, String author, Instant created, List<String> tags) {
    public String getName() {
        return name;
    }

    public List<String> tags() {
        return List.copyOf(tags);
    }
}
