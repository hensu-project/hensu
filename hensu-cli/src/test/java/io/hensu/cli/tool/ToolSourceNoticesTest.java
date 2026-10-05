package io.hensu.cli.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolSourceNoticesTest {

    private final ToolSourceNotices notices = new ToolSourceNotices();

    @Test
    void shouldKeepEachRunToTheNoticesRaisedWhileItWasInFlight() {
        // The daemon serves overlapping runs: one starting must not erase another's notices,
        // and neither may report what was raised before it began.
        notices.beginRun("earlier");
        notices.record("raised before the later run began");
        notices.beginRun("later");
        notices.record("raised while both were in flight");

        assertThat(notices.endRun("earlier"))
                .containsExactly(
                        "raised before the later run began", "raised while both were in flight");
        assertThat(notices.endRun("later")).containsExactly("raised while both were in flight");
    }
}
