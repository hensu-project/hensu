package io.hensu.server.multinode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/// Settings of one fault-injection test run, read from `multiNodeFaultTest.*` system properties.
///
/// @param serverDir the built `quarkus-app` directory of the server under test
/// @param reportDir where per-seed histories and reports are written
/// @param seeds one randomized fault schedule per seed
/// @param activeSeconds how long workload and faults run before the quiet period
/// @param expect what the run asserts about the invariants
/// @param skewSeconds wall-clock offset of the second node slot in skewed schedules
record FaultTestConfig(
        Path serverDir,
        Path reportDir,
        List<Long> seeds,
        int activeSeconds,
        Expectation expect,
        int skewSeconds) {

    /// What the run asserts once every seed has been checked.
    enum Expectation {
        /// Only write reports.
        REPORT,
        /// No safety violation may be found. The default: a run that reports a duplicated
        /// or stale effect must fail, whatever else it is asked to check.
        SAFE,
        /// No safety violation and no stranded execution may be found.
        CLEAN
    }

    static FaultTestConfig fromSystemProperties() {
        Path serverDir =
                Path.of(System.getProperty("multiNodeFaultTest.serverDir", "build/quarkus-app"));
        if (!Files.isRegularFile(serverDir.resolve("quarkus-run.jar"))) {
            throw new IllegalStateException(
                    "No server build at " + serverDir + "; run :hensu-server:quarkusBuild first");
        }
        Path reportDir =
                Path.of(
                        System.getProperty(
                                "multiNodeFaultTest.reportDir", "build/multi-node-fault-test"));
        List<Long> seeds =
                Arrays.stream(System.getProperty("multiNodeFaultTest.seeds", "1,2,3,4").split(","))
                        .map(String::trim)
                        .map(Long::parseLong)
                        .toList();
        int seconds = Integer.parseInt(System.getProperty("multiNodeFaultTest.seconds", "60"));
        Expectation expect =
                Expectation.valueOf(
                        System.getProperty("multiNodeFaultTest.expect", "safe").toUpperCase());
        int skew = Integer.parseInt(System.getProperty("multiNodeFaultTest.skew", "20"));
        return new FaultTestConfig(serverDir, reportDir, seeds, seconds, expect, skew);
    }

    /// Claim timing, scaled down so that faults bite within a run.
    static final Duration CLAIM_TTL = Duration.ofSeconds(6);
    static final Duration CLAIM_RENEW_INTERVAL = Duration.ofSeconds(2);
    static final Duration CALL_DEADLINE = Duration.ofSeconds(10);
}
