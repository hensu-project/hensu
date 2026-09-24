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

    private static McpServerSpec.Http http(McpServerSpec spec) {
        assertThat(spec).isInstanceOf(McpServerSpec.Http.class);
        return (McpServerSpec.Http) spec;
    }

    private static McpServerSpec.Stdio stdio(McpServerSpec spec) {
        assertThat(spec).isInstanceOf(McpServerSpec.Stdio.class);
        return (McpServerSpec.Stdio) spec;
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
            McpServerSpec.Stdio filesystem = stdio(servers.getFirst());
            assertThat(filesystem.command())
                    .containsExactly(
                            "/usr/local/bin/mcp-server-filesystem",
                            workingDirectory.toAbsolutePath().normalize().toString());
            assertThat(filesystem.startupTimeoutMs()).isEqualTo(90_000L);
            assertThat(filesystem.requestTimeoutMs()).isEqualTo(45_000L);
            assertThat(filesystem.unattended()).isTrue();
            assertThat(filesystem.approvalRequired()).isFalse();
            assertThat(filesystem.env()).containsExactly(java.util.Map.entry("LOG_LEVEL", "warn"));
            assertThat(filesystem.sandbox().network()).isFalse();
            assertThat(filesystem.sandbox().writePaths()).containsExactly(".");

            McpServerSpec.Stdio fetch = stdio(servers.get(1));
            assertThat(fetch.approvalRequired()).isTrue();
            assertThat(fetch.sandbox().network()).isTrue();
        }

        @Test
        void shouldApplyClosedDefaultsToAMinimalDeclaration() {
            McpServerSpec.Stdio server = stdio(parse("valid-minimal").getFirst());

            assertThat(server.startupTimeoutMs())
                    .isEqualTo(McpServerSpec.DEFAULT_STARTUP_TIMEOUT_MS);
            assertThat(server.requestTimeoutMs())
                    .isEqualTo(McpServerSpec.DEFAULT_REQUEST_TIMEOUT_MS);
            assertThat(server.unattended()).isFalse();
            assertThat(server.approvalRequired()).isFalse();
            assertThat(server.sandbox().network()).isFalse();
            assertThat(server.sandbox().writePaths()).isEmpty();
        }

        @Test
        void shouldLeaveTheLaunchItsOwnPatienceWhenOnlyTheCallBudgetIsDeclared() {
            // timeout: is about a server that has stopped answering, not about one
            // that has not started yet. A deployment asking for 500ms calls would
            // otherwise be asking for a 500ms launch, which no real server survives.
            McpServerSpec.Stdio server = stdio(parse("valid-request-timeout-only").getFirst());

            assertThat(server.requestTimeoutMs()).isEqualTo(500L);
            assertThat(server.startupTimeoutMs())
                    .isEqualTo(McpServerSpec.DEFAULT_STARTUP_TIMEOUT_MS);
        }

        @Test
        void shouldAcceptAnOfflinePackageRunnerBecauseAWarmCacheMakesItLegal() {
            // A warning, not an error: the offline form is the reproducible one
            // when a cache is mounted, so the grammar must not outlaw it.
            assertThat(parse("valid-offline-package-runner")).hasSize(1);
        }

        @Test
        void shouldReadEveryKeyARemoteDeclarationAccepts() {
            McpServerSpec.Http acme = http(parse("valid-http-full").getFirst());

            assertThat(acme.url()).hasToString("https://mcp.acme.example/mcp");
            assertThat(acme.bearerKey()).isEqualTo("HENSU_MCP_ACME_TOKEN");
            assertThat(acme.headers())
                    .containsExactly(
                            java.util.Map.entry("X-Acme-Region", "eu-west-1"),
                            java.util.Map.entry("X-Acme-Tier", "gold"));
            assertThat(acme.prefix()).isEqualTo("acme_");
            assertThat(acme.startupTimeoutMs()).isEqualTo(15_000L);
            assertThat(acme.requestTimeoutMs()).isEqualTo(20_000L);
            assertThat(acme.unattended()).isTrue();
            assertThat(acme.approvalRequired()).isTrue();
            assertThat(acme.host()).isEqualTo("mcp.acme.example");
        }

        @Test
        void shouldApplyClosedDefaultsToAMinimalRemoteDeclaration() {
            McpServerSpec.Http remote = http(parse("valid-http-minimal").getFirst());

            assertThat(remote.bearerKey()).isNull();
            assertThat(remote.headers()).isEmpty();
            assertThat(remote.prefix()).isEmpty();
            assertThat(remote.unattended()).isFalse();
            assertThat(remote.approvalRequired()).isFalse();
        }

        @Test
        void shouldAcceptPlaintextOnLoopbackBecauseNothingCrossesTheNetwork() {
            assertThat(http(parse("valid-http-loopback").getFirst()).host()).isEqualTo("127.0.0.1");
        }

        @Test
        void shouldCarryAPrefixOnALaunchedServerToo() {
            // A collision is a property of the catalog, not of the transport.
            List<McpServerSpec> servers = parse("valid-prefix");

            assertThat(servers).extracting(McpServerSpec::prefix).containsExactly("", "alt_");
        }

        @Test
        void shouldTreatAMissingFileAsNoServersRatherThanAnError() {
            assertThat(McpConfig.load(workingDirectory)).isEmpty();
        }
    }

    @Nested
    class RejectedDocuments {

        @Test
        void shouldRejectADeclarationThatIsBothLaunchedAndDialled() {
            assertRejects("bad-url-with-command", 4, "never both");
        }

        @Test
        void shouldRejectASandboxBlockOnARemoteServer() {
            // Refused rather than ignored: accepting it would tell an operator
            // that containment applies to a call that leaves the machine.
            assertRejects("bad-url-with-sandbox", 6, "no local process to contain");
        }

        @Test
        void shouldRejectAnEnvBlockOnARemoteServer() {
            assertRejects("bad-url-with-env", 6, "no local process to give an environment to");
        }

        @Test
        void shouldRejectARelativeUrl() {
            assertRejects("bad-url-relative", 4, "not an absolute http(s) URL");
        }

        @Test
        void shouldRejectPlaintextToAHostThatIsNotLoopback() {
            assertRejects("bad-url-plaintext-remote", 4, "accepted only on loopback");
        }

        @Test
        void shouldRejectALiteralTokenWhereACredentialKeyBelongs() {
            assertRejects("bad-auth-literal-token", 5, "not a credential key");
        }

        /// The value is most likely a real token pasted where its key belongs, and an
        /// error message is printed to the console and captured by CI logs.
        @Test
        void shouldNotRepeatTheRejectedValueInTheError() {
            assertThatThrownBy(() -> parse("bad-auth-literal-token"))
                    .isInstanceOf(MiniYamlException.class)
                    .message()
                    .doesNotContain("sk-ant-not-a-key");
        }

        @Test
        void shouldRejectAnAuthorizationHeader() {
            assertRejects("bad-header-authorization", 6, "use auth:");
        }

        @Test
        void shouldRejectAHeaderInTheTransportsOwnNamespace() {
            assertRejects("bad-header-reserved", 6, "reserved header");
        }

        @Test
        void shouldRejectAPrefixThatWouldNotLeaveALegalToolName() {
            assertRejects("bad-prefix", 5, "prefix: 'acme/'");
        }

        @Test
        void shouldRejectAuthOnALaunchedServer() {
            assertRejects("bad-auth-without-url", 5, "auth: without url:");
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
