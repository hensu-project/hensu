package io.hensu.server.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.plan.PlanExecutor;
import io.hensu.server.executor.AgenticNodeExecutor;
import io.hensu.server.mcp.McpSidecar;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerBootstrapTest {

    private AgenticNodeExecutor agenticExecutor;
    private McpSidecar mcpSidecar;
    private NodeExecutorRegistry nodeExecutorRegistry;
    private ActionExecutor actionExecutor;
    private PlanExecutor planExecutor;
    private ExecutionEventBroadcaster eventBroadcaster;
    private ServerBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        agenticExecutor = mock(AgenticNodeExecutor.class);
        mcpSidecar = mock(McpSidecar.class);
        nodeExecutorRegistry = mock(NodeExecutorRegistry.class);
        actionExecutor = mock(ActionExecutor.class);
        planExecutor = mock(PlanExecutor.class);
        eventBroadcaster = mock(ExecutionEventBroadcaster.class);

        bootstrap =
                new ServerBootstrap(
                        agenticExecutor,
                        mcpSidecar,
                        nodeExecutorRegistry,
                        actionExecutor,
                        planExecutor,
                        eventBroadcaster);
    }

    @Test
    void onStartShouldRegisterAgenticExecutor() {
        bootstrap.onStart(new StartupEvent());

        verify(nodeExecutorRegistry).register(agenticExecutor);
    }

    @Test
    void onStartShouldRegisterMcpSidecar() {
        bootstrap.onStart(new StartupEvent());

        verify(actionExecutor).registerHandler(mcpSidecar);
    }

    @Test
    void onStartShouldRegisterEventBroadcaster() {
        bootstrap.onStart(new StartupEvent());

        verify(planExecutor).addObserver(eventBroadcaster);
    }
}
