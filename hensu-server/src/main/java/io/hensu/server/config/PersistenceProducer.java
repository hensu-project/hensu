package io.hensu.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.state.InMemoryWorkflowStateRepository;
import io.hensu.server.execution.ExecutionStore;
import io.hensu.server.mcp.ClusterSignals;
import io.hensu.server.mcp.McpCatalogs;
import io.hensu.server.mcp.ToolIntents;
import io.hensu.server.persistence.InMemoryExecutionStore;
import io.hensu.server.persistence.InMemoryMcpCatalogs;
import io.hensu.server.persistence.InMemoryToolIntents;
import io.hensu.server.persistence.JdbcExecutionStore;
import io.hensu.server.persistence.JdbcMcpCatalogs;
import io.hensu.server.persistence.JdbcToolIntents;
import io.hensu.server.persistence.PgEventBus;
import io.hensu.server.persistence.PgNotificationListener;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/// Produces the durable side of execution: the claim store runs write through and the
/// API's queries read from, the tool intents relays pull, the relays' catalogs, and – with
/// a database – the listener that turns PostgreSQL notifications into wake-ups and carries
/// execution events between nodes.
///
/// A deployment with an active datasource gets the PostgreSQL implementations; one
/// without (the `inmem` profile) gets single-process ones with the same state machine.
/// Every producer instantiates a concrete type, so the native image needs no dynamic
/// class loading.
///
/// ### Configuration
/// | Property                       | Default  | Description                                 |
/// |--------------------------------|----------|---------------------------------------------|
/// | `hensu.node.id`                | random   | Label recorded on the claims this node takes |
/// | `hensu.claim.ttl`              | `30s`    | How long a claim lasts without renewal      |
/// | `hensu.mcp.ack-deadline`       | `10s`    | How long an offer waits for acknowledgement |
/// | `hensu.mcp.call-deadline`      | `60s`    | How long an acknowledged call may take      |
/// | `hensu.mcp.dispatch-deadline`  | `5m`     | How long an intent waits for any relay      |
@ApplicationScoped
public class PersistenceProducer {

    private static final Logger LOG = Logger.getLogger(PersistenceProducer.class);

    @Inject Config config;

    @Inject Instance<DataSource> dataSourceInstance;

    @Inject ObjectMapper objectMapper;

    @Inject ClusterSignals signals;

    @Inject ExecutionEventBroadcaster eventBroadcaster;

    @ConfigProperty(name = "hensu.node.id")
    Optional<String> nodeId;

    @ConfigProperty(name = "hensu.claim.ttl", defaultValue = "30s")
    Duration claimTtl;

    @ConfigProperty(name = "hensu.mcp.ack-deadline", defaultValue = "10s")
    Duration ackDeadline;

    @ConfigProperty(name = "hensu.mcp.call-deadline", defaultValue = "60s")
    Duration callDeadline;

    @ConfigProperty(name = "hensu.mcp.dispatch-deadline", defaultValue = "5m")
    Duration dispatchDeadline;

    private InMemoryExecutionStore inMemoryStore;
    private InMemoryToolIntents inMemoryIntents;
    private PgNotificationListener listener;

    @Produces
    @Singleton
    public ExecutionStore executionStore() {
        String owner =
                nodeId.filter(id -> !id.isBlank()).orElseGet(() -> UUID.randomUUID().toString());
        LOG.infov("Claims taken by this node are labelled {0}", owner);
        return database()
                .<ExecutionStore>map(
                        ds -> new JdbcExecutionStore(ds, objectMapper, owner, claimTtl))
                .orElseGet(this::inMemoryStore);
    }

    @Produces
    @Singleton
    public ToolIntents toolIntents() {
        return database()
                .<ToolIntents>map(
                        ds ->
                                new JdbcToolIntents(
                                        ds,
                                        objectMapper,
                                        ackDeadline,
                                        callDeadline,
                                        dispatchDeadline))
                .orElseGet(this::inMemoryIntents);
    }

    @Produces
    @Singleton
    public McpCatalogs mcpCatalogs() {
        return database()
                .<McpCatalogs>map(ds -> new JdbcMcpCatalogs(ds, objectMapper))
                .orElseGet(() -> new InMemoryMcpCatalogs(objectMapper));
    }

    void startListening(@Observes StartupEvent event) {
        database()
                .ifPresent(
                        ds -> {
                            PgEventBus bus = new PgEventBus(ds, objectMapper, eventBroadcaster);
                            eventBroadcaster.attach(bus);
                            listener = new PgNotificationListener(ds, signals, bus::receive);
                            listener.start();
                        });
    }

    void stopListening(@Observes @Priority(10) ShutdownEvent event) {
        if (listener != null) {
            listener.stop();
        }
    }

    private Optional<DataSource> database() {
        boolean active =
                config.getOptionalValue("quarkus.datasource.active", Boolean.class).orElse(true);
        return active && dataSourceInstance.isResolvable()
                ? Optional.of(dataSourceInstance.get())
                : Optional.empty();
    }

    private synchronized InMemoryExecutionStore inMemoryStore() {
        if (inMemoryStore == null) {
            inMemoryStore =
                    new InMemoryExecutionStore(
                            new InMemoryWorkflowStateRepository(),
                            (claim, finished) -> inMemoryIntents().settled(claim, finished));
        }
        return inMemoryStore;
    }

    private synchronized InMemoryToolIntents inMemoryIntents() {
        if (inMemoryIntents == null) {
            inMemoryIntents =
                    new InMemoryToolIntents(
                            claim -> inMemoryStore().holdsClaim(claim),
                            signals,
                            ackDeadline,
                            callDeadline,
                            dispatchDeadline);
        }
        return inMemoryIntents;
    }
}
