package io.hensu.server.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.server.mcp.McpSidecar;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerBootstrapTest {

    private McpSidecar mcpSidecar;
    private ActionExecutor actionExecutor;
    private ServerBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        mcpSidecar = mock(McpSidecar.class);
        actionExecutor = mock(ActionExecutor.class);
        bootstrap = new ServerBootstrap(mcpSidecar, actionExecutor);
    }

    @Test
    void onStartShouldRegisterMcpSidecar() {
        bootstrap.onStart(new StartupEvent());

        verify(actionExecutor).registerHandler(mcpSidecar);
    }
}
