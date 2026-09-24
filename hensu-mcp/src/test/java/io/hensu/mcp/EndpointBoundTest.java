package io.hensu.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EndpointBoundTest {

    private final EndpointBound bound =
            new EndpointBound(
                    "acme",
                    URI.create("https://mcp.acme.example/mcp"),
                    Set.of("MCP.ACME.example", "other.example"));

    @Test
    void shouldFollowARelativeRedirectOnTheSameEndpoint() {
        assertThat(bound.follow(redirect("/v2/mcp")))
                .isEqualTo(URI.create("https://mcp.acme.example/v2/mcp"));
    }

    @Test
    void shouldTreatTheDefaultPortAsTheSameEndpoint() {
        assertThat(bound.follow(redirect("https://MCP.acme.example:443/mcp")))
                .isEqualTo(URI.create("https://MCP.acme.example:443/mcp"));
    }

    @Test
    void shouldRefuseAnotherPortOnTheSameHost() {
        // Two servers on one host at different ports is the ordinary local case;
        // a token minted for one is not a token for the other.
        assertThatThrownBy(() -> bound.follow(redirect("https://mcp.acme.example:8443/mcp")))
                .isInstanceOf(McpEgressDeniedException.class)
                .hasMessageContaining("which is not https://mcp.acme.example:443");
    }

    @Test
    void shouldRefuseADowngradeToPlaintext() {
        assertThatThrownBy(() -> bound.follow(redirect("http://mcp.acme.example/mcp")))
                .isInstanceOf(McpEgressDeniedException.class);
    }

    @Test
    void shouldSayWhenTheTargetIsDeclaredForAnotherServer() {
        assertThatThrownBy(() -> bound.follow(redirect("https://other.example/mcp")))
                .isInstanceOf(McpEgressDeniedException.class)
                .hasMessageContaining("declared in mcp.yaml for a different server");
    }

    @Test
    void shouldFailARedirectWithNoLocation() {
        HttpReply reply = new HttpReply(302, HttpHeaders.of(Map.of(), (_, _) -> true), "");

        assertThatThrownBy(() -> bound.follow(reply))
                .isInstanceOf(McpException.class)
                .isNotInstanceOf(McpEgressDeniedException.class)
                .hasMessageContaining("with no Location header");
    }

    private static HttpReply redirect(String location) {
        return new HttpReply(
                302, HttpHeaders.of(Map.of("location", List.of(location)), (_, _) -> true), "");
    }
}
