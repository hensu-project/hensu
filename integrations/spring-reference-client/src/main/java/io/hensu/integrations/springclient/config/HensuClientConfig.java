package io.hensu.integrations.springclient.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/// Spring configuration for hensu-server HTTP clients.
///
/// Produces two clients sharing the same base URL and auth header:
/// - `RestClient` for blocking REST calls (workflow trigger, resume, result polling)
/// - `WebClient` for reactive SSE streams (execution events, MCP split-pipe)
@Configuration
@EnableConfigurationProperties(HensuProperties.class)
public class HensuClientConfig {

    @Bean
    RestClient hensuRestClient(HensuProperties props) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.serverUrl());

        if (props.token() != null && !props.token().isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + props.token());
        }

        return builder.build();
    }

    @Bean
    WebClient hensuWebClient(HensuProperties props) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(props.serverUrl());

        if (props.token() != null && !props.token().isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + props.token());
        }

        return builder.build();
    }
}
