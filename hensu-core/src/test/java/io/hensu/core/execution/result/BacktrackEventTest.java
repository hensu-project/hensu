package io.hensu.core.execution.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BacktrackEventTest {

    @Nested
    class BuilderTest {

        @Test
        void shouldBuildBacktrackEventWithRequiredFields() {
            // When
            BacktrackEvent event = BacktrackEvent.builder().from("node-2").to("node-1").build();

            // Then
            assertThat(event.getFrom()).isEqualTo("node-2");
            assertThat(event.getTo()).isEqualTo("node-1");
        }

        @Test
        void shouldBuildWithAllFields() {
            // Given
            Instant timestamp = Instant.now();

            // When
            BacktrackEvent event =
                    BacktrackEvent.builder()
                            .from("node-3")
                            .to("node-1")
                            .reason("Quality score below threshold")
                            .type(BacktrackType.AUTOMATIC)
                            .rubricScore(45.0)
                            .timestamp(timestamp)
                            .build();

            // Then
            assertThat(event.getFrom()).isEqualTo("node-3");
            assertThat(event.getTo()).isEqualTo("node-1");
            assertThat(event.getReason()).isEqualTo("Quality score below threshold");
            assertThat(event.getType()).isEqualTo(BacktrackType.AUTOMATIC);
            assertThat(event.getRubricScore()).isEqualTo(45.0);
            assertThat(event.getTimestamp()).isEqualTo(timestamp);
        }

        @Test
        void shouldThrowWhenFromIsNull() {
            // When/Then
            assertThatThrownBy(() -> BacktrackEvent.builder().to("node-1").build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("From Node required");
        }

        @Test
        void shouldThrowWhenToIsNull() {
            // When/Then
            assertThatThrownBy(() -> BacktrackEvent.builder().from("node-2").build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("To Node required");
        }
    }

    @Nested
    class BacktrackTypeTest {

        @Test
        void shouldSupportAutomaticType() {
            // When
            BacktrackEvent event =
                    BacktrackEvent.builder()
                            .from("node-2")
                            .to("node-1")
                            .type(BacktrackType.AUTOMATIC)
                            .build();

            // Then
            assertThat(event.getType()).isEqualTo(BacktrackType.AUTOMATIC);
        }

        @Test
        void shouldSupportManualType() {
            // When
            BacktrackEvent event =
                    BacktrackEvent.builder()
                            .from("node-2")
                            .to("node-1")
                            .type(BacktrackType.MANUAL)
                            .build();

            // Then
            assertThat(event.getType()).isEqualTo(BacktrackType.MANUAL);
        }

        @Test
        void shouldSupportJumpType() {
            // When
            BacktrackEvent event =
                    BacktrackEvent.builder()
                            .from("node-3")
                            .to("node-1")
                            .type(BacktrackType.JUMP)
                            .build();

            // Then
            assertThat(event.getType()).isEqualTo(BacktrackType.JUMP);
        }
    }

    @Nested
    class EqualsAndHashCodeTest {

        @Test
        void shouldBeEqualWhenFromToAndTimestampMatch() {
            // Given
            Instant timestamp = Instant.now();

            BacktrackEvent event1 =
                    BacktrackEvent.builder()
                            .from("node-2")
                            .to("node-1")
                            .timestamp(timestamp)
                            .reason("reason1")
                            .build();

            BacktrackEvent event2 =
                    BacktrackEvent.builder()
                            .from("node-2")
                            .to("node-1")
                            .timestamp(timestamp)
                            .reason("reason2")
                            .build();

            // Then
            assertThat(event1).isEqualTo(event2);
            assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
        }

        @Test
        void shouldNotBeEqualWhenFromDiffers() {
            // Given
            Instant timestamp = Instant.now();

            BacktrackEvent event1 =
                    BacktrackEvent.builder()
                            .from("node-2")
                            .to("node-1")
                            .timestamp(timestamp)
                            .build();

            BacktrackEvent event2 =
                    BacktrackEvent.builder()
                            .from("node-3")
                            .to("node-1")
                            .timestamp(timestamp)
                            .build();

            // Then
            assertThat(event1).isNotEqualTo(event2);
        }

        @Test
        void shouldNotBeEqualWhenToDiffers() {
            // Given
            Instant timestamp = Instant.now();

            BacktrackEvent event1 =
                    BacktrackEvent.builder()
                            .from("node-2")
                            .to("node-1")
                            .timestamp(timestamp)
                            .build();

            BacktrackEvent event2 =
                    BacktrackEvent.builder()
                            .from("node-2")
                            .to("node-0")
                            .timestamp(timestamp)
                            .build();

            // Then
            assertThat(event1).isNotEqualTo(event2);
        }

        @Test
        void shouldNotBeEqualWhenTimestampDiffers() {
            // Given
            BacktrackEvent event1 =
                    BacktrackEvent.builder()
                            .from("node-2")
                            .to("node-1")
                            .timestamp(Instant.now())
                            .build();

            BacktrackEvent event2 =
                    BacktrackEvent.builder()
                            .from("node-2")
                            .to("node-1")
                            .timestamp(Instant.now().plusSeconds(1))
                            .build();

            // Then
            assertThat(event1).isNotEqualTo(event2);
        }

        @Test
        void shouldNotBeEqualToNull() {
            // Given
            BacktrackEvent event = BacktrackEvent.builder().from("node-2").to("node-1").build();

            // Then
            assertThat(event).isNotEqualTo(null);
        }

        @Test
        void shouldNotBeEqualToDifferentType() {
            // Given
            BacktrackEvent event = BacktrackEvent.builder().from("node-2").to("node-1").build();

            // Then
            assertThat(event).isNotEqualTo("string");
        }
    }

    @Test
    void shouldReturnMeaningfulToString() {
        // Given
        Instant timestamp = Instant.now();
        BacktrackEvent event =
                BacktrackEvent.builder()
                        .from("writer")
                        .to("draft")
                        .rubricScore(65.0)
                        .timestamp(timestamp)
                        .type(BacktrackType.AUTOMATIC)
                        .build();

        // When
        String toString = event.toString();

        // Then
        assertThat(toString).contains("writer");
        assertThat(toString).contains("draft");
        assertThat(toString).contains("65.0");
        assertThat(toString).contains("AUTOMATIC");
    }
}
