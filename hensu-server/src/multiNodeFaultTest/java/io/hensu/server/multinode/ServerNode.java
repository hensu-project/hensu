package io.hensu.server.multinode;

import com.github.dockerjava.api.DockerClient;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/// One server process: the built `quarkus-app` running in a JRE container.
///
/// A node occupies a *slot* – the Toxiproxy route to Postgres it uses and the place it
/// holds behind the load balancer. Crashes and deploys replace the process in a slot
/// with a new one under a new node id; the slot's skew stays with it.
final class ServerNode {

    enum State {
        RUNNING,
        PAUSED,
        STOPPED
    }

    /// Where the image links libfaketime, whatever the image's architecture.
    private static final String FAKETIME_LIB = "/usr/local/lib/libfaketime.so.1";

    /// JRE plus libfaketime, so a node's wall clock can run ahead of the host's without
    /// touching the code under test. Monotonic clocks stay real.
    ///
    /// The library sits under the multiarch directory of the image's own architecture
    /// (`x86_64-linux-gnu`, `aarch64-linux-gnu`), which only the image knows: the Docker
    /// daemon may run a platform other than the test JVM's. The build therefore asks the
    /// package database for the path and links it to [#FAKETIME_LIB]; a package without
    /// the library fails the build instead of a skewed node.
    private static final ImageFromDockerfile IMAGE =
            new ImageFromDockerfile("hensu-fault-test-server", false)
                    .withDockerfileFromBuilder(
                            builder ->
                                    builder.from("eclipse-temurin:25-jre")
                                            .run(
                                                    "apt-get update && apt-get install -y"
                                                            + " --no-install-recommends faketime"
                                                            + " && rm -rf /var/lib/apt/lists/*"
                                                            + " && lib=$(dpkg -L libfaketime"
                                                            + " | grep '/libfaketime\\.so\\.1$')"
                                                            + " && ln -s \"$lib\" "
                                                            + FAKETIME_LIB)
                                            .build());

    /// Testcontainers' shared client, used for the commands it does not wrap. Never
    /// closed here: every container of the run talks through it.
    private static final DockerClient DOCKER = DockerClientFactory.lazyClient();

    final String slot;
    final String id;
    final int skewSeconds;
    private final GenericContainer<?> container;
    private volatile State state = State.STOPPED;

    ServerNode(
            String slot,
            String id,
            int skewSeconds,
            Network network,
            Path serverDir,
            Path stubsDir,
            Path keysDir,
            String jdbcUrl,
            Map<String, String> settings) {
        this.slot = slot;
        this.id = id;
        this.skewSeconds = skewSeconds;
        this.container = new GenericContainer<>(IMAGE);
        container
                .withNetwork(network)
                .withExposedPorts(8080)
                .withFileSystemBind(serverDir.toString(), "/app", BindMode.READ_ONLY)
                .withFileSystemBind(stubsDir.toString(), "/stubs", BindMode.READ_ONLY)
                .withFileSystemBind(keysDir.toString(), "/keys", BindMode.READ_ONLY)
                .withEnv("QUARKUS_PROFILE", "faulttest")
                .withEnv("QUARKUS_DATASOURCE_JDBC_URL", jdbcUrl)
                .withEnv("QUARKUS_DATASOURCE_USERNAME", Cluster.DB_USER)
                .withEnv("QUARKUS_DATASOURCE_PASSWORD", Cluster.DB_PASSWORD)
                .withEnv("QUARKUS_SHUTDOWN_TIMEOUT", "10s")
                .withEnv("HENSU_JWT_PUBLIC_KEY", "/keys/" + Jwt.PUBLIC_KEY_FILE)
                .withEnv("HENSU_STUB_ENABLED", "true")
                .withEnv("HENSU_STUB_DIRECTORY", "/stubs")
                .withEnv("HENSU_NODE_ID", id)
                .withEnv(settings)
                .withCommand(
                        "java",
                        "--enable-preview",
                        "--enable-native-access=ALL-UNNAMED",
                        "--add-opens",
                        "java.base/java.lang=ALL-UNNAMED",
                        "-Xmx400m",
                        "-jar",
                        "/app/quarkus-run.jar")
                .waitingFor(
                        Wait.forLogMessage(".*Installed features.*", 1)
                                .withStartupTimeout(Duration.ofSeconds(120)));
        if (skewSeconds != 0) {
            container
                    .withEnv("LD_PRELOAD", FAKETIME_LIB)
                    .withEnv("FAKETIME", (skewSeconds > 0 ? "+" : "") + skewSeconds + "s")
                    .withEnv("FAKETIME_DONT_FAKE_MONOTONIC", "1");
        }
    }

    void start() {
        container.start();
        state = State.RUNNING;
    }

    State state() {
        return state;
    }

    URI base() {
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8080));
    }

    void pause() {
        DOCKER.pauseContainerCmd(container.getContainerId()).exec();
        state = State.PAUSED;
    }

    void unpause() {
        DOCKER.unpauseContainerCmd(container.getContainerId()).exec();
        state = State.RUNNING;
    }

    /// SIGTERM, then SIGKILL after the drain window – a rolling deploy's stop.
    void terminate() {
        if (state == State.PAUSED) {
            unpause();
        }
        state = State.STOPPED;
        DOCKER.stopContainerCmd(container.getContainerId()).withTimeout(15).exec();
    }

    /// SIGKILL – a crash.
    void kill() {
        if (state == State.PAUSED) {
            unpause();
        }
        state = State.STOPPED;
        DOCKER.killContainerCmd(container.getContainerId()).exec();
    }

    String logs() {
        try {
            return container.getLogs();
        } catch (RuntimeException e) {
            return "(logs unavailable: " + e.getMessage() + ")";
        }
    }

    void remove() {
        try {
            container.stop();
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    @Override
    public String toString() {
        return id + "(" + slot + (skewSeconds != 0 ? ", skew " + skewSeconds + "s" : "") + ")";
    }
}
