package io.hensu.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// Guards the structural claim that the server reaches no local filesystem.
///
/// The server executes nothing locally: every side effect leaves as an MCP
/// request to a tenant-owned client. `hensu-core` nevertheless ships
/// `FileToolProvider`, because reading the input a run was told to act on is an
/// engine capability rather than a deployment's, and the CLI wires it. That
/// leaves the class on this module's compile classpath, one `@Produces` method
/// away from a multi-tenant deployment whose agents could write through a root
/// every tenant shares.
///
/// Nothing at runtime prevents that today: the provider carries no CDI
/// annotations, so `Instance<ToolProvider>` cannot pick it up by itself, but a
/// producer someone adds would be collected like any other bean and would look
/// entirely ordinary in review. The property worth keeping is therefore
/// structural – the server's sources do not name the package at all – and a test
/// that reads them is what makes it a build failure rather than a convention.
///
/// @see io.hensu.core.tool.file.FileToolProvider for the provider this module declines
class NoLocalFileToolsOnTheServerTest {

    private static final String SOURCE_ROOT = "src/main/java";

    /// Package and type names that would put local file tools in a tenant's reach.
    private static final List<String> FORBIDDEN =
            List.of("io.hensu.core.tool.file", "FileToolProvider", "PathGuard");

    @Test
    void shouldNameNoLocalFileToolAnywhereInItsSources() throws IOException {
        Path root = Path.of(SOURCE_ROOT);
        assumeTrue(
                Files.isDirectory(root),
                "test must run with the module directory as its working directory");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(root)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                FORBIDDEN.stream()
                        .filter(text::contains)
                        .forEach(name -> offenders.add(source + " names " + name));
            }
        }

        assertThat(offenders)
                .as(
                        "the server routes every side effect through MCP to a tenant's own client;"
                                + " a file tool rooted on the server would write where tenants"
                                + " cannot see it and cannot be isolated from each other")
                .isEmpty();
    }
}
