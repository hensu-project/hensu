package io.hensu.server.persistence;

import io.hensu.server.mcp.ClusterSignals;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jboss.logging.Logger;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/// Turns PostgreSQL notifications into {@link ClusterSignals} on this node.
///
/// Holds one dedicated connection that listens on `hensu_intent` (a tenant has an intent
/// to offer), `hensu_outcome` (an intent settled), `hensu_runnable` (an execution was
/// released) and `hensu_events` (an execution event for this node's subscribers).
/// Notifications are only a wake-up – every waiter re-reads the store and bounds its wait – so
/// the listener follows the rule the `LISTEN` documentation gives: listen first, then re-read.
/// After every (re)connect it signals a resync, which wakes every waiter to re-read whatever it
/// may have missed while the connection was down.
///
/// @implNote One virtual thread; {@link #stop()} ends it.
public final class PgNotificationListener {

    private static final Logger LOG = Logger.getLogger(PgNotificationListener.class);
    private static final int POLL_MILLIS = 500;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    private final DataSource dataSource;
    private final ClusterSignals signals;
    private final Consumer<String> events;
    private volatile boolean running = true;
    private Thread thread;

    /// @param dataSource the pool to take the listening connection from, not null
    /// @param signals where wake-ups are delivered, not null
    /// @param events where `hensu_events` payloads are delivered, not null
    public PgNotificationListener(
            DataSource dataSource, ClusterSignals signals, Consumer<String> events) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.signals = Objects.requireNonNull(signals, "signals must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    /// Starts listening in the background.
    public void start() {
        thread = Thread.ofVirtual().name("pg-notifications").start(this::loop);
    }

    /// Stops listening and waits for the loop to end.
    ///
    /// The loop sees the stop at its next poll. The thread is interrupted only if it has not
    /// ended by then: interrupting a virtual thread blocked in a socket read closes the
    /// socket, and the pool would get back a dead connection.
    public void stop() {
        running = false;
        if (thread == null) {
            return;
        }
        try {
            if (!thread.join(Duration.ofMillis(POLL_MILLIS * 2L))) {
                thread.interrupt();
            }
        } catch (InterruptedException e) {
            thread.interrupt();
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        long backoffMillis = 100;
        while (running) {
            try (Connection connection = dataSource.getConnection()) {
                PGConnection pg = connection.unwrap(PGConnection.class);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN hensu_intent");
                    statement.execute("LISTEN hensu_outcome");
                    statement.execute("LISTEN hensu_runnable");
                    statement.execute("LISTEN hensu_events");
                }
                backoffMillis = 100;
                signals.resync();
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(POLL_MILLIS);
                    if (notifications != null) {
                        for (PGNotification notification : notifications) {
                            dispatch(notification.getName(), notification.getParameter());
                        }
                    }
                }
            } catch (SQLException e) {
                if (!running) {
                    return;
                }
                LOG.warnv("Notification listener lost its connection: {0}", e.getMessage());
                sleep(backoffMillis);
                backoffMillis = Math.min(backoffMillis * 2, MAX_BACKOFF.toMillis());
            }
        }
    }

    private void dispatch(String channel, String payload) {
        switch (channel) {
            case "hensu_intent" -> signals.intent(payload);
            case "hensu_outcome" -> {
                try {
                    signals.outcome(Long.parseLong(payload));
                } catch (NumberFormatException e) {
                    LOG.debugv("Ignoring malformed outcome notification: {0}", payload);
                }
            }
            case "hensu_runnable" -> signals.runnable();
            case "hensu_events" -> events.accept(payload);
            default -> LOG.debugv("Ignoring notification on {0}", channel);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
