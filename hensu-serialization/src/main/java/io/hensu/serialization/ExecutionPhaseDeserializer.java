package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.ExecutionPhase;
import java.io.IOException;
import java.io.Serial;
import java.time.Instant;

/// Deserializes the {@link ExecutionPhase} sealed hierarchy using a {@code "type"} discriminator.
///
/// Handles three subtypes:
/// - **{@code "initial"}** -- returns {@link ExecutionPhase#INITIAL}
/// - **{@code "awaiting_post_processor"}** -- reconstructs
///   {@link ExecutionPhase.Awaiting} from JSON fields, delegating
///   {@code cachedResult} to the mapper's existing {@link NodeResult} configuration
/// - **{@code "terminal"}** -- returns {@link ExecutionPhase#TERMINAL}
///
/// @implNote Package-private. Registered by {@link HensuJacksonModule}.
/// @see ExecutionPhaseSerializer for the inverse operation
class ExecutionPhaseDeserializer extends StdDeserializer<ExecutionPhase> {

    @Serial private static final long serialVersionUID = 1L;

    ExecutionPhaseDeserializer() {
        super(ExecutionPhase.class);
    }

    @Override
    public ExecutionPhase deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode root = mapper.readTree(p);

        String type = root.get("type").asText();

        return switch (type) {
            case "initial" -> ExecutionPhase.INITIAL;
            case "awaiting_post_processor" -> {
                String nodeId = root.get("nodeId").asText();
                String processorId = root.get("processorId").asText();
                NodeResult cachedResult =
                        mapper.treeToValue(root.get("cachedResult"), NodeResult.class);
                String correlationId = root.get("correlationId").asText();
                Instant requestedAt = mapper.treeToValue(root.get("requestedAt"), Instant.class);
                yield new ExecutionPhase.Awaiting(
                        nodeId, processorId, cachedResult, correlationId, requestedAt);
            }
            case "terminal" -> ExecutionPhase.TERMINAL;
            default -> throw new IOException("Unknown ExecutionPhase type: " + type);
        };
    }
}
