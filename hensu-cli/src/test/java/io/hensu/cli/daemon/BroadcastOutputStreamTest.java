package io.hensu.cli.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import org.junit.jupiter.api.Test;

class BroadcastOutputStreamTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void write_withNonZeroOffset_slicesCorrectBytesIntoRingBuffer() throws Exception {
        var exec = new StoredExecution("e1", "wf");

        // Write bytes[3..6] from "0123456789" — should store "3456" only
        try (var out = new BroadcastOutputStream(exec, mapper)) {
            out.write("0123456789".getBytes(), 3, 4);
        }

        var replay = exec.getOutputBuffer().drain();
        assertThat(new String(replay.bytes())).isEqualTo("3456");
    }

    @Test
    void write_encodesPayloadAsBase64InBroadcastedOutFrame() throws Exception {
        var exec = new StoredExecution("e2", "wf");
        var queue = new ArrayBlockingQueue<String>(10);
        exec.addSubscriber(queue);

        try (var out = new BroadcastOutputStream(exec, mapper)) {
            out.write("hello".getBytes(), 0, 5);
        }

        String frame = queue.poll();
        assertThat(frame).isNotNull();
        var node = mapper.readTree(frame);
        assertThat(node.get("t").asText()).isEqualTo("out");
        assertThat(node.get("b").asText())
                .isEqualTo(Base64.getEncoder().encodeToString("hello".getBytes()));
    }
}
