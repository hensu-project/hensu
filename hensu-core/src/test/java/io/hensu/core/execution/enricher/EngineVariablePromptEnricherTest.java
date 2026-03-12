package io.hensu.core.execution.enricher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EngineVariablePromptEnricher")
class EngineVariablePromptEnricherTest extends EnricherTestBase {

    @Test
    @DisplayName("chains injectors — each injector receives the output of the previous one")
    void shouldChainInjectors() {
        // Two injectors that append a marker. If chaining breaks (result reset to original prompt),
        // the second marker would still appear but the first would be lost from the second call.
        var enricher =
                new EngineVariablePromptEnricher(
                        List.of(
                                (prompt, _, _) -> prompt + "[A]",
                                (prompt, _, _) -> prompt + "[B]"));

        String result = enricher.enrich("base", minimalNode(), ctx(Map.of(), null, null));

        assertThat(result).isEqualTo("base[A][B]");
    }
}
