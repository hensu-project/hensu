package io.hensu.serialization.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.plan.PlanCreationException;
import io.hensu.core.plan.PlanResponseParser;
import io.hensu.core.plan.PlanStepAction;
import io.hensu.core.plan.PlannedStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Jackson-based implementation of {@link PlanResponseParser}.
///
/// Parses LLM-generated JSON responses into {@link PlannedStep} lists.
/// Supports both tool-call and synthesize step shapes in a single array:
///
/// **Tool call:**
/// ```json
/// {"tool": "fetch_order", "arguments": {"id": "{orderId}"}, "description": "Fetch order"}
/// ```
///
/// **Synthesize:**
/// ```json
/// {"synthesize": true, "description": "Summarise all results into a recommendation"}
/// ```
///
/// The parser strips markdown code fences before deserialisation. It maps
/// {@code synthesize: true} to {@link PlanStepAction.Synthesize} with
/// {@code agentId = null}; the executor enriches the agent id later.
///
/// @implNote Thread-safe if the supplied {@link ObjectMapper} is thread-safe
/// (Jackson's default singleton mapper is).
///
/// @see PlanResponseParser for the interface contract
/// @see io.hensu.core.plan.LlmPlanner for the primary caller
public class JacksonPlanResponseParser implements PlanResponseParser {

    private final ObjectMapper objectMapper;

    /// Creates a parser backed by the given Jackson mapper.
    ///
    /// @param objectMapper the mapper to use for JSON deserialisation, not null
    public JacksonPlanResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public List<PlannedStep> parse(String content) throws PlanCreationException {
        Objects.requireNonNull(content, "content must not be null");
        String json = extractJson(content);

        try {
            List<Map<String, Object>> dtos = objectMapper.readValue(json, new TypeReference<>() {});
            List<PlannedStep> steps = new ArrayList<>(dtos.size());

            for (int i = 0; i < dtos.size(); i++) {
                steps.add(parseStep(i, dtos.get(i)));
            }

            return steps;
        } catch (JsonProcessingException e) {
            throw new PlanCreationException("Failed to parse plan JSON: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private PlannedStep parseStep(int index, Map<String, Object> dto) throws PlanCreationException {
        String description = (String) dto.getOrDefault("description", "");

        // Synthesize step: {"synthesize": true, "description": "..."}
        Object synthesizeFlag = dto.get("synthesize");
        if (Boolean.TRUE.equals(synthesizeFlag)) {
            if (description == null || description.isBlank()) {
                throw new PlanCreationException(
                        "Synthesize step at index " + index + " is missing a description/prompt");
            }
            // agentId is null here — enriched by AgenticNodeExecutor after plan creation
            return PlannedStep.synthesize(index, null, description);
        }

        // Tool-call step: {"tool": "...", "arguments": {...}, "description": "..."}
        String toolName = (String) dto.get("tool");
        if (toolName == null || toolName.isBlank()) {
            throw new PlanCreationException(
                    "Step at index " + index + " has neither a 'tool' name nor 'synthesize: true'");
        }

        Map<String, Object> arguments =
                dto.get("arguments") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

        return PlannedStep.pending(index, toolName, arguments, description);
    }

    /// Extracts the JSON content from an LLM response, stripping markdown fences.
    ///
    /// @param content raw response string, not null
    /// @return cleaned JSON string ready for deserialisation
    private String extractJson(String content) {
        // Try ```json ... ``` first
        int start = content.indexOf("```json");
        if (start >= 0) {
            start = content.indexOf('\n', start) + 1;
            int end = content.indexOf("```", start);
            if (end > start) {
                return content.substring(start, end).trim();
            }
        }

        // Try generic ``` ... ```
        start = content.indexOf("```");
        if (start >= 0) {
            start = content.indexOf('\n', start) + 1;
            int end = content.indexOf("```", start);
            if (end > start) {
                return content.substring(start, end).trim();
            }
        }

        // Fall back to first [ ... ] array bounds
        int arrayStart = content.indexOf('[');
        int arrayEnd = content.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return content.substring(arrayStart, arrayEnd + 1);
        }

        return content.trim();
    }
}
