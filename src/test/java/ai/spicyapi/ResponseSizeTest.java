package ai.spicyapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The byte ceiling on response bodies.
 *
 * <p>Why it is needed: {@code BodyHandlers.ofString} takes whatever it is given. A broken
 * intermediary, a captive-portal interstitial or a tampered response can emit bytes indefinitely,
 * and the client will keep allocating for them until the caller's heap is gone. Nothing signals
 * that beforehand — it merely looks like a slow download. And {@code listModels(includeSchema=true)}
 * is a large response to begin with, so "this one is unusually big" would not raise anyone's
 * suspicion either.
 */
class ResponseSizeTest {

    /** How much is written at a time. 64 KiB blocks are fast, yet fine-grained enough to pin the
     * ceiling between two of them. */
    private static final int CHUNK = 64 * 1024;

    @Test
    @DisplayName("a response that keeps growing is cut off, and the client says so in those words")
    void oversizedResponseIsCutOff() throws Exception {
        AtomicLong written = new AtomicLong();
        AtomicInteger requests = new AtomicInteger();
        // Three times the ceiling. If it all gets read, the ceiling did not hold; three times over
        // loopback costs only a few hundred milliseconds.
        long willing = SpicyClient.MAX_RESPONSE_BYTES * 3;

        try (StubServer server = new StubServer().on("/api/v1/models", (request, exchange) -> {
            requests.incrementAndGet();
            byte[] block = new byte[CHUNK];
            java.util.Arrays.fill(block, (byte) 'x');
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // 0 means chunked transfer with an unknown length: exactly the case that cannot be
            // rejected up front from Content-Length, and the only one that needs counting as it
            // arrives.
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                while (written.get() < willing) {
                    out.write(block);
                    written.addAndGet(block.length);
                }
            } catch (IOException cutOff) {
                // The client hung up, so this side sees a broken pipe — which is precisely what
                // this test set out to prove.
                return;
            } finally {
                exchange.close();
            }
        })) {
            SpicyTransportException refused =
                    assertThrows(SpicyTransportException.class, () -> server.client().listModels());

            assertTrue(refused.getMessage().contains("ceiling"),
                    "the error must point at the local ceiling rather than vaguely blaming the "
                            + "network: " + refused.getMessage());
            assertTrue(refused.getMessage().contains(String.valueOf(SpicyClient.MAX_RESPONSE_BYTES)),
                    "naming the number is what tells whoever reads the log where to look");
            assertTrue(written.get() < willing,
                    "the server wrote everything it wanted to, which means the client only checked "
                            + "after reading it all - a ceiling applied then is no ceiling at all, and "
                            + "endless output is the very thing it guards against");
            assertEquals(1, requests.get(),
                    "no retry: the same broken intermediary emits the same thing again, so four "
                            + "attempts would just hit the ceiling four times");
        }
    }

    @Test
    @DisplayName("a large but legal response is read whole, multi-byte characters included")
    void largeResponseStillMapsCorrectly() throws Exception {
        // A large response below the ceiling must come back intact. This also pins the decoding:
        // the unit below mixes two-, three- and four-byte UTF-8 sequences, so across 1 MiB some
        // character is certain to straddle two network blocks. Decoding block by block would split
        // it into replacement characters - damage that only ever shows up in non-ASCII content,
        // which is to say only in error messages written in other languages.
        String unit = "Upstream returned a long failure reason - oshibka: \u043e\u0448\u0438\u0431\u043a\u0430, "
                + "sfalma: \u03c3\u03c6\u03ac\u03bb\u03bc\u03b1, \u2705 \uD83D\uDEA7. ";
        int unitBytes = unit.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder message = new StringBuilder();
        for (int written = 0; written < 1024 * 1024; written += unitBytes) {
            message.append(unit);
        }
        String expected = message.toString();
        assertTrue(expected.getBytes(StandardCharsets.UTF_8).length > CHUNK * 4,
                "the payload must genuinely span several blocks, or this test verifies nothing");

        try (StubServer server = new StubServer().onData("/api/v1/jobs/recordInfo", """
                {"taskId":"task_123","model":"m","state":"failed","cost":"0","settled":true,
                 "createdAt":"2026-09-20T08:30:00Z","errorMessage":"%s"}""".formatted(expected))) {
            TaskRecord task = server.client().getTask("task_123");

            assertEquals(TaskState.FAILED, task.state());
            assertEquals(expected, task.errorMessage(),
                    "a multi-byte character straddling a network block boundary was decoded wrong");
        }
    }
}
