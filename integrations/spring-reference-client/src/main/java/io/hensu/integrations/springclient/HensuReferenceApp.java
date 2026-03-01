package io.hensu.integrations.springclient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/// Spring Boot reference client demonstrating integration with hensu-server.
///
/// ### What this app demonstrates
/// - Triggering workflow executions via `POST /api/v1/executions`
/// - Consuming real-time execution events via SSE (`GET /api/v1/executions/{id}/events`)
/// - Serving MCP tools via the split-pipe transport (`GET /mcp/connect` + `POST /mcp/message`)
/// - Human-in-the-loop review via `POST /demo/review/{executionId}`
///
/// ### Prerequisites
/// 1. hensu-server running on `http://localhost:8080` (dev or inmem profile)
/// 2. The `risk-assessment` workflow pushed via the Hensu CLI:
///    ```
///    ./hensu build risk-assessment -d integrations/spring-reference-client/working-dir
///    ./hensu push risk-assessment --server http://localhost:8080
///    ```
/// 3. Configure `hensu.token` in `application.yml` if targeting a JWT-protected server
///
/// @see io.hensu.integrations.springclient.demo.DemoRunner for the demo orchestration
/// @see io.hensu.integrations.springclient.mcp.HensuMcpTransport for the split-pipe engine
/// @see io.hensu.integrations.springclient.review.ReviewController for human-in-the-loop
@SpringBootApplication
public class HensuReferenceApp {

    public static void main(String[] args) {
        SpringApplication.run(HensuReferenceApp.class, args);
    }
}
