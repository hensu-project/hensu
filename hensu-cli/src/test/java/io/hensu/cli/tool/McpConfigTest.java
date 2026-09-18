package io.hensu.cli.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.util.MiniYamlException;
import io.hensu.mcp.McpServerSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigTest {

    @TempDir Path workingDirectory;

    private static String golden(String name) {
        try (InputStream stream =
                McpConfigTest.class.getResourceAsStream("/mcp/" + name + ".yaml")) {
            if (stream == null) {
                throw new IllegalStateException("missing golden file: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<McpServerSpec> parse(String name) {
        return McpConfig.parse(golden(name), workingDirectory);
    }

    private void assertRejects(String name, int line, String fragment) {
        assertThatThrownBy(() -> parse(name))
                .isInstanceOf(MiniYamlException.class)
                .hasMessageContaining(fragment)
                .extracting(failure -> ((MiniYamlException) failure).line())
                .isEqualTo(line);
    }

    @Nested
    class ValidDocuments {

        @Test
        void shouldReadEveryKeyTheGrammarAccepts() {
            List<McpServerSpec> servers = parse("valid-full");

            assertThat(servers)
                    .extracting(McpServerSpec::name)
                    .containsExactly("filesystem", "fetch");
            McpServerSpec filesystem = servers.getFirst();
            assertThat(filesystem.command())
                    .containsExactly(
                            "/usr/local/bin/mcp-server-filesystem",
                            workingDirectory.toAbsolutePath().normalize().toString());
            assertThat(filesystem.requestTimeoutMs()).isEqualTo(45_000L);
            assertThat(filesystem.unattended()).isTrue();
            assertThat(filesystem.approvalRequired()).isFalse();
            assertThat(filesystem.env()).containsExactly(java.util.Map.entry("LOG_LEVEL", "warn"));
            assertThat(filesystem.sandbox().network()).isFalse();
            assertThat(filesystem.sandbox().writePaths()).containsExactly(".");

            McpServerSpec fetch = servers.get(1);
            assertThat(fetch.approvalRequired()).isTrue();
            assertThat(fetch.sandbox().network()).isTrue();
        }

        @Test
        void shouldApplyClosedDefaultsToAMinimalDeclaration() {
            McpServerSpec server = parse("valid-minimal").getFirst();

            assertThat(server.requestTimeoutMs())
                    .isEqualTo(McpServerSpec.DEFAULT_REQUEST_TIMEOUT_MS);
            assertThat(server.unattended()).isFalse();
            assertThat(server.approvalRequired()).isFalse();
            assertThat(server.sandbox().network()).isFalse();
            assertThat(server.sandbox().writePaths()).isEmpty();
        }

        @Test
        void shouldAcceptAnOfflinePackageRunnerBecauseAWarmCacheMakesItLegal() {
            // A warning, not an error: the offline form is the reproducible one
            // when a cache is mounted, so the grammar must not outlaw it.
            assertThat(parse("valid-offline-package-runner")).hasSize(1);
        }

        @Test
        void shouldTreatAMissingFileAsNoServersRatherThanAnError() {
            assertThat(McpConfig.load(workingDirectory)).isEmpty();
        }
    }

    @Nested
    class RejectedDocuments {

        @Test
        void shouldRejectAnHttpEndpointAsAnUnsupportedTransport() {
            assertRejects("bad-url", 4, "HTTP MCP endpoints are not yet supported");
        }

        @Test
        void shouldRejectAnUnknownServerKey() {
            assertRejects("bad-unknown-key", 4, "unknown key 'retries'");
        }

        @Test
        void shouldRejectAPlaceholderThatIsNotWorkdir() {
            assertRejects("bad-unknown-placeholder", 3, "{workdir} is the only placeholder");
        }

        @Test
        void shouldRejectWorkdirEmbeddedInALargerArgument() {
            // One whole token or nothing: half-expanded argv elements are how a
            // path with a space becomes two arguments.
            assertRejects("bad-embedded-workdir", 3, "whole argv element");
        }

        @Test
        void shouldRejectAnApprovalValueOutsideTheVocabulary() {
            assertRejects("bad-approval-value", 4, "expected required or none");
        }

        @Test
        void shouldRejectAWritePathOutsideTheWorkingDirectory() {
            assertRejects("bad-write-escape", 5, "resolves outside the working directory");
        }

        @Test
        void shouldRejectAnUnknownSandboxKey() {
            assertRejects("bad-sandbox-key", 5, "unknown key 'readonly'");
        }

        @Test
        void shouldRejectTheReservedParameterNamespaceInEnv() {
            assertRejects("bad-reserved-env", 5, "HENSU_PARAM_");
        }

        @Test
        void shouldRejectAnEmptyCommand() {
            assertRejects("bad-empty-command", 3, "empty command");
        }
    }
}
