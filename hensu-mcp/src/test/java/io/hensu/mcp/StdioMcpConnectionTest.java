package io.hensu.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.action.SandboxPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class StdioMcpConnectionTest {

    @TempDir Path workingDirectory;

    private StdioMcpConnection connection;

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.close();
        }
    }

    /// Budget for the fixture's launch, which forks a second JVM.
    ///
    /// Generous on purpose: this wait pays for JVM startup and class loading on
    /// whatever machine the suite runs on, and must not be confused with the
    /// per-request budget a test is actually exercising.
    private static final long STARTUP_BUDGET_MS = 30_000L;

    private static McpServerSpec.Stdio spec(long requestTimeoutMs, String... extraArgs) {
        return spec(STARTUP_BUDGET_MS, requestTimeoutMs, extraArgs);
    }

    private static McpServerSpec.Stdio spec(
            long startupTimeoutMs, long requestTimeoutMs, String... extraArgs) {
        List<String> argv = new ArrayList<>();
        argv.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        argv.add("-cp");
        argv.add(System.getProperty("java.class.path"));
        argv.add(FakeMcpServer.class.getName());
        argv.addAll(List.of(extraArgs));
        return new McpServerSpec.Stdio(
                "fixture",
                "",
                argv,
                Map.of("FIXTURE_MARKER", "set"),
                SandboxPolicy.restrictive(),
                startupTimeoutMs,
                requestTimeoutMs,
                true,
                false);
    }

    private StdioMcpConnection open(McpServerSpec.Stdio spec) {
        connection =
                StdioMcpConnection.open(
                        spec, workingDirectory, UnaryOperator.identity(), System.getenv());
        return connection;
    }

    @Test
    void shouldHandshakeAndListTheServerCatalog() {
        List<McpConnection.McpToolDescriptor> tools = open(spec(10_000)).listTools();

        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst().name()).isEqualTo("echo");
        assertThat(McpSchemaConverter.convert(tools.getFirst()).parameters())
                .singleElement()
                .satisfies(
                        parameter -> {
                            assertThat(parameter.name()).isEqualTo("message");
                            assertThat(parameter.required()).isTrue();
                        });
    }

    @Test
    void shouldCallAToolAndReturnARenderableResult() {
        Map<String, Object> result =
                open(spec(10_000)).callTool("echo", Map.of("message", "hello"));

        assertThat(McpResultRenderer.render(result)).isEqualTo("echoed hello");
    }

    @Test
    void shouldSurfaceATimeoutAndForgetTheRequestRatherThanWaitForever() {
        // A server that stops answering must fail the call, not pin the thread
        // that made it – the engine runs no watchdog around a provider.
        StdioMcpConnection open = open(spec(300, "--swallow", "tools/call"));

        assertThatThrownBy(() -> open.callTool("echo", Map.of("message", "hello")))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("timed out after 300ms");

        // The correlation entry is gone, so the next call gets a fresh id and works.
        assertThat(open.listTools()).hasSize(1);
    }

    @Test
    void shouldNotChargeTheLaunchToThePerRequestBudget() {
        // A deployment that wants calls to fail in 50ms must still be able to
        // start a server whose runtime is expensive to boot. The fixture declares
        // its slowness rather than relying on real JVM startup, so the launch is
        // longer than the call budget on every machine rather than on slow ones.
        StdioMcpConnection open = open(spec(STARTUP_BUDGET_MS, 50, "--slow-start", "400"));

        assertThat(open.isConnected()).isTrue();
    }

    @Test
    void shouldSurfaceAServerThatNeverFinishesComingUpAsAStartupFailure() {
        // The other direction: a server that launches but never answers
        // 'initialize' has to fail the open rather than hand back a connection
        // nobody can use, and it is the startup budget that expires.
        assertThatThrownBy(() -> open(spec(300, 10_000, "--swallow", "initialize")))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("MCP request 'initialize'")
                .hasMessageContaining("timed out after 300ms");
    }

    @Test
    void shouldGiveUpOnAServerThatStoppedReadingItsStandardInput() {
        // The send blocks as readily as the reply: a live server that never reads
        // again fills the pipe, and the request timeout used to cover only the
        // answer, so the caller waited on the write with no deadline at all.
        StdioMcpConnection open = open(spec(300, "--deaf-after", "initialize"));
        String oversized = "x".repeat(512 * 1024);

        assertThatThrownBy(() -> open.callTool("echo", Map.of("message", oversized)))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("stopped reading its standard input");

        // The connection is spent, not merely slow: the bytes already in the pipe
        // make the next request unframeable.
        assertThat(open.isConnected()).isFalse();
    }

    @Test
    void shouldKeepAnsweringWhileTheServerWritesDiagnosticsToStandardError() {
        // Standard error is drained on its own thread; a server that logs would
        // otherwise block on a full pipe and look like a hang.
        StdioMcpConnection open = open(spec(10_000, "--noisy"));

        assertThat(open.listTools()).hasSize(1);
        assertThat(McpResultRenderer.render(open.callTool("echo", Map.of("message", "again"))))
                .isEqualTo("echoed again");
    }

    @Test
    void shouldKillTheServerProcessOnClose() {
        StdioMcpConnection open = open(spec(10_000));
        assertThat(open.isConnected()).isTrue();

        open.close();

        assertThat(open.isConnected()).isFalse();
        assertThatThrownBy(open::listTools)
                .isInstanceOf(McpException.class)
                .hasMessageContaining("is not running");
    }

    @Test
    void shouldReportALaunchFailureRatherThanReturnABrokenConnection() {
        McpServerSpec.Stdio missing =
                McpServerSpec.Stdio.of("absent", List.of("/nonexistent/mcp-server-does-not-exist"));

        assertThatThrownBy(
                        () ->
                                StdioMcpConnection.open(
                                        missing,
                                        workingDirectory,
                                        UnaryOperator.identity(),
                                        System.getenv()))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("Could not launch MCP server 'absent'");
    }

    @Test
    void shouldStartTheServerFromAHermeticEnvironment() {
        // A filesystem server has no business reading the operator's credentials,
        // so the child starts empty and receives the allowlist plus its own env:.
        Map<String, String> host =
                Map.of(
                        "PATH", System.getenv("PATH"),
                        "HOME", "/home/operator",
                        "SECRET_TOKEN", "sk-do-not-leak");
        connection =
                StdioMcpConnection.open(
                        spec(10_000), workingDirectory, UnaryOperator.identity(), host);

        assertThat(render(connection, "PATH")).startsWith("PATH=");
        assertThat(render(connection, "FIXTURE_MARKER")).isEqualTo("FIXTURE_MARKER=set");
        assertThat(render(connection, "SECRET_TOKEN")).isEqualTo("SECRET_TOKEN is absent");
        assertThat(render(connection, "HOME")).isEqualTo("HOME is absent");
    }

    private static String render(StdioMcpConnection open, String variable) {
        return McpResultRenderer.render(open.callTool("env", Map.of("message", variable)));
    }

    @Test
    void shouldApplyTheContainmentWrapperToTheLaunchArgv() {
        List<List<String>> wrapped = new ArrayList<>();
        McpServerSpec.Stdio spec = spec(10_000);

        connection =
                StdioMcpConnection.open(
                        spec,
                        workingDirectory,
                        argv -> {
                            wrapped.add(argv);
                            return argv;
                        },
                        System.getenv());

        assertThat(wrapped).singleElement().isEqualTo(spec.command());
    }

    @Test
    @Timeout(10)
    void shouldFailACallAtOnceWhenTheServerStoppedAnsweringButStillRuns() throws Exception {
        // A process that closed its output is alive but can never reply. The request
        // budget here is a minute; the call must not spend it waiting.
        StdioMcpConnection open = open(spec(60_000, "--mute-after", "initialize"));
        // The call must come after the reader has seen the output close. Made earlier, it
        // is pending when the reader's final sweep fails it, and passes without the fix.
        // The reader's exit raises no event, so poll the state, bounded by @Timeout.
        while (open.isConnected()) {
            //noinspection BusyWait
            Thread.sleep(10);
        }

        assertThatThrownBy(() -> open.callTool("echo", Map.of("message", "hello")))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("is not running");
    }
}
