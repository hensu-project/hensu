package io.hensu.cli.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// Guards the structural claim that no shell sits between an agent and a process.
///
/// Argv-only execution is a property of the code's shape, not of any single
/// assertion: the moment a second place builds a `/bin/sh -c` command line, the
/// guarantee is gone and every injection test elsewhere keeps passing. This test
/// therefore reads the sources rather than running them, and fails when a new
/// shell invocation appears outside the one declared, human-authored branch.
class NoShellOnTheAgentPathTest {

    private static final List<String> SOURCE_ROOTS =
            List.of("src/main/java", "../hensu-core/src/main/java");

    /// The only place permitted to build a shell command line, because a
    /// shell-mode command's text is fixed configuration and its parameters arrive
    /// as environment variables.
    private static final String PERMITTED = "CommandRunner.java";

    @Test
    void shouldBuildAShellCommandLineInExactlyOnePlace() throws IOException {
        List<Path> roots = SOURCE_ROOTS.stream().map(Path::of).filter(Files::isDirectory).toList();
        assumeTrue(
                roots.size() == SOURCE_ROOTS.size(),
                "test must run with the module directory as its working directory");

        List<String> offenders = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> sources = Files.walk(root)) {
                for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(source);
                    if (text.contains("\"/bin/sh\"") && !source.endsWith(PERMITTED)) {
                        offenders.add(source.toString());
                    }
                }
            }
        }

        assertThat(offenders)
                .as(
                        "only %s may construct a shell command line; agent arguments must reach"
                                + " execve as argv elements",
                        PERMITTED)
                .isEmpty();
    }
}
