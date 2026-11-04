package io.hensu.server.multinode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/// A seeded, randomized list of faults for one run.
///
/// Each fault starts 3 to 8 s after the previous one has ended, so every scheduled fault
/// applies to a cluster with one node impaired at most; faults may hit the same node twice.
/// Durations of pauses and partitions always exceed the lease period, since a shorter one
/// exercises nothing.
/// Relay faults (F4, F5) need no entry: the relay holds no connection, and every one of
/// its requests is routed on its own.
final class FaultSchedule {

    enum Kind {
        /// `docker pause` for longer than the lease (F2).
        PAUSE,
        /// Node–DB route disabled in Toxiproxy (F3).
        PARTITION,
        /// SIGKILL, replaced a few seconds later under a new node id (F1).
        CRASH,
        /// SIGTERM with a drain window, replaced under a new node id (F6).
        DEPLOY
    }

    /// @param atMillis offset from the start of the active period
    /// @param kind the fault
    /// @param target the node slot
    /// @param durationMillis how long a pause or partition lasts, or the replacement delay
    record Fault(long atMillis, Kind kind, String target, long durationMillis) {}

    /// How long a replaced node takes to drain or die and boot again, on top of the fault's
    /// own duration.
    private static final long REPLACEMENT_MILLIS = 15_000;

    private FaultSchedule() {}

    static List<Fault> generate(long seed, int activeSeconds) {
        Random random = new Random(seed * 7919L);
        List<Fault> faults = new ArrayList<>();
        long end = (activeSeconds - 6) * 1000L;
        long at = 4000;
        while (true) {
            at += 3000 + random.nextInt(5000);
            if (at > end) {
                break;
            }
            int roll = random.nextInt(7);
            Kind kind =
                    roll < 3
                            ? Kind.PAUSE
                            : roll < 5 ? Kind.PARTITION : roll < 6 ? Kind.CRASH : Kind.DEPLOY;
            String target = Cluster.SLOTS.get(random.nextInt(Cluster.SLOTS.size()));
            long duration =
                    switch (kind) {
                        case PAUSE, PARTITION ->
                                FaultTestConfig.CLAIM_TTL.toMillis() + 2000 + random.nextInt(5000);
                        case CRASH, DEPLOY -> 2000 + random.nextInt(3000);
                    };
            faults.add(new Fault(at, kind, target, duration));
            at += duration + (kind == Kind.CRASH || kind == Kind.DEPLOY ? REPLACEMENT_MILLIS : 0);
        }
        faults.sort(Comparator.comparingLong(Fault::atMillis));
        return faults;
    }
}
