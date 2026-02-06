package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.hensu.core.workflow.Workflow;

/// Utility class for serializing and deserializing Hensu workflows to/from JSON.
///
/// Provides a pre-configured `ObjectMapper` with all necessary modules for
/// handling the Hensu type hierarchy, including `java.time` types (`Duration`,
/// `Instant`) and builder-based deserialization.
///
/// ### Usage
/// {@snippet :
/// // Serialize
/// String json = WorkflowSerializer.toJson(workflow);
///
/// // Deserialize
/// Workflow restored = WorkflowSerializer.fromJson(json);
///
/// // Custom ObjectMapper
/// ObjectMapper mapper = WorkflowSerializer.createMapper();
/// }
///
/// @implNote Thread-safe. The internal ObjectMapper is created per call via
/// `createMapper()`. For high-throughput scenarios, cache the mapper.
///
/// @see HensuJacksonModule for the registered type handlers
public final class WorkflowSerializer {

    private WorkflowSerializer() {}

    /// Serializes a workflow to pretty-printed JSON.
    ///
    /// @param workflow the workflow to serialize, not null
    /// @return JSON string representation, never null
    /// @throws IllegalArgumentException if serialization fails
    public static String toJson(Workflow workflow) {
        try {
            return createMapper().writeValueAsString(workflow);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialize workflow: " + e.getMessage(), e);
        }
    }

    /// Deserializes a workflow from JSON.
    ///
    /// @param json JSON string, not null
    /// @return deserialized workflow, never null
    /// @throws IllegalArgumentException if deserialization fails
    public static Workflow fromJson(String json) {
        try {
            return createMapper().readValue(json, Workflow.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to deserialize workflow: " + e.getMessage(), e);
        }
    }

    /// Creates an ObjectMapper configured for Hensu workflow serialization.
    ///
    /// Registers:
    /// - `HensuJacksonModule` for workflow type hierarchy
    /// - `JavaTimeModule` for `Duration` and `Instant` fields
    /// - `FAIL_ON_UNKNOWN_PROPERTIES` disabled for forward compatibility
    /// - Timestamps written as ISO-8601 strings (not numeric)
    ///
    /// @return configured ObjectMapper, never null
    public static ObjectMapper createMapper() {
        return new ObjectMapper()
                .registerModule(new HensuJacksonModule())
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }
}
