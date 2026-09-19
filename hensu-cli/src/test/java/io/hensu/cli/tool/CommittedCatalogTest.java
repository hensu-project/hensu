package io.hensu.cli.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.action.CommandDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// The repository ships working-dir/ as the sample an operator copies and as the fixture directory
// the manual test plans run against, yet nothing loaded its commands.yaml outside a human running
// those plans. That matters more than it looks: CommandCatalog answers a malformed file with an
// empty registry rather than an exception, by design, so a typo in the committed sample does not
// fail anything – it just makes every catalog command quietly disappear. These tests are the check
// that was missing, and they exercise the production path, swallow included.
class CommittedCatalogTest {

    private static Path workingDirectory;

    @BeforeAll
    static void locateWorkingDirectory() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null
                && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        assertThat(candidate)
                .describedAs("repository root, found by walking up to settings.gradle.kts")
                .isNotNull();
        workingDirectory = candidate.resolve("working-dir");
        assertThat(workingDirectory.resolve(CommandCatalog.FILE_NAME)).isRegularFile();
    }

    private static CommandCatalog committedCatalog() {
        CommandCatalog catalog = new CommandCatalog();
        catalog.setWorkingDirectory(workingDirectory);
        return catalog;
    }

    // An empty registry here is what a parse error looks like from the outside: no exception, no
    // failing run, just an allowlist that grants nothing. Naming the whole expected set rather than
    // asserting non-emptiness also catches an entry that loses its `tool:` block, which is the
    // difference between a command an agent can call and one only a workflow action can.
    @Test
    void committedCatalogExposesItsAgentVisibleCommands() {
        assertThat(committedCatalog().registry().agentVisibleCommands())
                .containsOnlyKeys(
                        "echo-result",
                        "count-lines",
                        "send-notification",
                        "gated-echo",
                        "supervised-echo");
    }

    // regression-capability-gap and Track P both rest on these two entries carrying one gate each:
    // it is what makes the two refusal sentences distinguishable, and what makes an unattended run
    // of the fixture refuse supervised-echo on a stated reason rather than on either of two. Adding
    // the second gate to either entry would leave both plans passing for the wrong reason.
    @Test
    void theTwoGatedEntriesCarryExactlyOneGateEach() {
        var registry = committedCatalog().registry();

        CommandDefinition gated = registry.getCommand("gated-echo");
        assertThat(gated.approvalRequired()).isTrue();
        assertThat(gated.unattended()).isTrue();

        CommandDefinition supervised = registry.getCommand("supervised-echo");
        assertThat(supervised.unattended()).isFalse();
        assertThat(supervised.approvalRequired()).isFalse();
    }

    // deploy-staging carries both gates and no `tool:` block. It is the reason the two entries
    // above exist, so a change that makes it agent-visible would silently make them redundant and
    // reintroduce the ambiguity they were added to remove.
    @Test
    void theDoubleGatedEntryStaysInvisibleToAgents() {
        var registry = committedCatalog().registry();

        assertThat(registry.hasCommand("deploy-staging")).isTrue();
        assertThat(registry.agentVisibleCommands()).doesNotContainKey("deploy-staging");
    }

    // Every executable is resolved to an absolute path when the file loads, and a missing binary
    // fails the whole catalog rather than one entry. Asserting the resolved head keeps that
    // property visible: if it ever degrades to the literal template text, the failure moves from
    // load time to an agent's first call.
    @Test
    void executablesResolveToAbsolutePaths() {
        var registry = committedCatalog().registry();

        for (String id : Set.copyOf(registry.getCommandIds())) {
            CommandDefinition definition = registry.getCommand(id);
            if (definition.shellMode() || definition.rung()) {
                continue;
            }
            assertThat(definition.execTemplate().getFirst())
                    .describedAs("resolved executable for '%s'", id)
                    .startsWith("/");
        }
    }
}
