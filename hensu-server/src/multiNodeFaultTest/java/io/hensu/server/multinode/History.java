package io.hensu.server.multinode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/// Everything the test observed during one seed, stamped with the host clock.
///
/// Server containers share the host kernel's clock with Postgres and the test JVM
/// – a skewed node fakes only its own process clock – so these timestamps and the DB
/// audit's `clock_timestamp()` order against each other within milliseconds.
final class History {

    /// One observation.
    ///
    /// @param at host wall clock, epoch milliseconds
    /// @param kind what happened: `received`, `acked`, `late-ack`, `executed`, `answered`,
    ///     `api`, `fault`, `connect`
    /// @param tenant the tenant involved, may be null
    /// @param node the server node involved, may be null
    /// @param execution the execution id, when known
    /// @param request the offer token, when relevant
    /// @param key the execution-qualified intent key, when relevant
    /// @param tool the tool name, when relevant
    /// @param args the call arguments, when relevant
    /// @param status the HTTP status of a request, or 0
    /// @param detail free text
    record Event(
            long at,
            String kind,
            String tenant,
            String node,
            String execution,
            String request,
            String key,
            String tool,
            Map<String, Object> args,
            int status,
            String detail) {}

    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();

    void add(Event event) {
        events.add(event);
    }

    void fault(String kind, String node, String detail) {
        add(
                new Event(
                        now(),
                        "fault",
                        null,
                        node,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        kind + " " + detail));
    }

    void api(String tenant, String node, int status, String execution, String detail) {
        add(
                new Event(
                        now(), "api", tenant, node, execution, null, null, "start", null, status,
                        detail));
    }

    void connect(String tenant, String node, int status, String detail) {
        add(
                new Event(
                        now(), "connect", tenant, node, null, null, null, null, null, status,
                        detail));
    }

    List<Event> events() {
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(Event::at));
        return sorted;
    }

    void write(Path file) throws IOException {
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(file.toFile(), events());
    }

    static long now() {
        return System.currentTimeMillis();
    }
}
