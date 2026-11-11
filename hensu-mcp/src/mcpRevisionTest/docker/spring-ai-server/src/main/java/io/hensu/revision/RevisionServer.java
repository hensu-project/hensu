package io.hensu.revision;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/// A Spring AI MCP server with one quick tool and one slow one.
@SpringBootApplication
public class RevisionServer {

    public static void main(String[] args) {
        SpringApplication.run(RevisionServer.class, args);
    }

    @Bean
    ToolCallbackProvider tools() {
        return MethodToolCallbackProvider.builder().toolObjects(new Tools()).build();
    }

    /// The tools the suite lists, calls and cancels.
    public static class Tools {

        @Tool(description = "Echoes a message back")
        public String echo(@ToolParam(description = "Text to echo") String message) {
            return "Echo: " + message;
        }

        @Tool(description = "Sleeps for the given number of seconds, then answers")
        public String slow(@ToolParam(description = "Seconds to sleep") int seconds)
                throws InterruptedException {
            Thread.sleep(seconds * 1_000L);
            return "slept " + seconds;
        }
    }
}
