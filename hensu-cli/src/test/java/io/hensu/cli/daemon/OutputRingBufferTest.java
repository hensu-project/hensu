package io.hensu.cli.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutputRingBufferTest {

    @Test
    void writeThenDrain_returnsBytes_inWriteOrder() {
        var buf = new OutputRingBuffer(16);
        buf.write("ABCDE".getBytes());

        var result = buf.drain();

        assertThat(result.truncated()).isFalse();
        assertThat(result.bytes()).isEqualTo("ABCDE".getBytes());
    }

    @Test
    void write_exceedingCapacityInOneSingleCall_keepsLastNBytes() {
        // 8 bytes written to a 5-byte buffer in one call — only last 5 survive
        var buf = new OutputRingBuffer(5);
        buf.write("ABCDEFGH".getBytes());

        var result = buf.drain();

        assertThat(result.truncated()).isTrue();
        assertThat(new String(result.bytes())).isEqualTo("DEFGH");
    }

    @Test
    void write_wrapsAcrossBoundary_drainsInChronologicalOrder() {
        // capacity=5
        // write "ABC"  → buf=[A,B,C,_,_], writePos=3
        // write "DE"   → buf=[A,B,C,D,E], writePos=0, wrapped
        // write "FG"   → buf=[F,G,C,D,E], writePos=2
        // drain order: tail [writePos..end] + head [0..writePos) → [C,D,E,F,G]
        var buf = new OutputRingBuffer(5);
        buf.write("ABC".getBytes());
        buf.write("DE".getBytes());
        buf.write("FG".getBytes());

        var result = buf.drain();

        assertThat(result.truncated()).isTrue();
        assertThat(new String(result.bytes())).isEqualTo("CDEFG");
    }

    @Test
    void write_exactlyAtCapacity_setsWrappedAndDrainsCorrectly() {
        // Writing exactly capacity bytes triggers the wrap path
        var buf = new OutputRingBuffer(4);
        buf.write("ABCD".getBytes());

        var result = buf.drain();

        assertThat(result.truncated()).isTrue();
        assertThat(new String(result.bytes())).isEqualTo("ABCD");
    }

    @Test
    void totalWritten_tracksAllBytes_includingOverwritten() {
        var buf = new OutputRingBuffer(3);
        buf.write("ABC".getBytes());
        buf.write("DEF".getBytes());

        assertThat(buf.getTotalWritten()).isEqualTo(6);
        assertThat(new String(buf.drain().bytes())).isEqualTo("DEF");
    }
}
