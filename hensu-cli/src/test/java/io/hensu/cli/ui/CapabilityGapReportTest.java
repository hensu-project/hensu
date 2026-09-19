package io.hensu.cli.ui;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.tool.CapabilityGaps;
import io.hensu.core.tool.ToolCallStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CapabilityGapReportTest {

    @Test
    void shouldPrintNothingWhenTheRunWasRefusedNothing() {
        // An empty section on every successful run is noise, and its absence is itself
        // the signal that nothing was refused.
        assertThat(render(new HashMap<>())).isEmpty();
    }

    @Test
    void shouldGroupRepeatedRefusalsIntoOneRowWithACount() {
        Map<String, Object> context = new HashMap<>();
        CapabilityGaps.record(context, "diagnose", "deploy", ToolCallStatus.DENIED, List.of());
        CapabilityGaps.record(context, "retry", "deploy", ToolCallStatus.DENIED, List.of());

        String output = render(context);

        assertThat(output).contains("deploy");
        assertThat(output).contains("DENIED");
        assertThat(output).contains("×2");
        // Both nodes, because "which node kept asking" is what tells an operator whether
        // to widen a per-node grant or the catalog itself.
        assertThat(output).contains("diagnose").contains("retry");
    }

    @Test
    void shouldKeepGatesApartForTheSameTool() {
        Map<String, Object> context = new HashMap<>();
        CapabilityGaps.record(context, "n", "deploy", ToolCallStatus.DENIED, List.of());
        CapabilityGaps.record(
                context, "n", "deploy", ToolCallStatus.SANDBOX_UNAVAILABLE, List.of());

        // "a reviewer said no" and "this host has no containment" lead an operator to two
        // completely different fixes.
        assertThat(CapabilityGapReport.aggregate(CapabilityGaps.of(context)))
                .extracting(CapabilityGapReport.Entry::gate)
                .containsExactly("DENIED", "SANDBOX_UNAVAILABLE");
    }

    @Test
    void shouldNotPrintArgumentValuesBecauseRecordsNeverCarryThem() {
        Map<String, Object> context = new HashMap<>();
        CapabilityGaps.record(
                context, "publish", "deploy", ToolCallStatus.DENIED, List.of("token"));

        String output = render(context);

        assertThat(output).doesNotContain("sk-");
    }

    private static String render(Map<String, Object> context) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            CapabilityGapReport.print(out, AnsiStyles.of(false), context);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
