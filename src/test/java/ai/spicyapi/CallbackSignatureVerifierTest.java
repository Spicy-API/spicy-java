package ai.spicyapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The six paths through callBackUrl signature verification: v1, v2, a tampered body, an expired
 * timestamp, an unknown version and a missing header.
 *
 * <p>Each corresponds to a real incident. Leave one unverified and that incident arrives in the
 * shape of "a result that looked perfectly normal".
 */
class CallbackSignatureVerifierTest {

    private static final String SECRET = "whsec_test_key";

    private static final String V2_BODY = StubServer.envelope("""
            {"taskId":"task_123","model":"m","state":"succeeded","cost":"0.09","settled":true,
             "createdAt":"2026-09-20T08:30:00Z","output":{"assets":[]}}""");

    private static final String V1_BODY =
            "{\"task_id\":\"task_123\",\"model\":\"m\",\"state\":\"succeeded\",\"cost\":\"0.09\","
                    + "\"created_at\":\"2026-09-20T08:30:00Z\"}";

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a payload version 2 delivery maps to the same record getTask returns")
    void version2() {
        long now = Instant.now().getEpochSecond();
        byte[] raw = bytes(V2_BODY);
        String signature = CallbackSignatureVerifier.sign("task_123", now, raw, SECRET);

        WebhookDelivery delivery =
                new CallbackSignatureVerifier(SECRET).verify(raw, signature, String.valueOf(now), "2");

        assertEquals("task_123", delivery.taskId());
        assertEquals(2, delivery.payloadVersion());
        assertEquals(TaskState.SUCCEEDED, delivery.task().orElseThrow().state());
        assertTrue(delivery.task().orElseThrow().settled());
        assertEquals("req_abc", delivery.deliveryId().orElseThrow(),
                "deliveries are retried, so a receiver needs something stable to deduplicate on");
    }

    @Test
    @DisplayName("the header-lookup overload reads the three headers by their documented names")
    void headerLookupOverload() {
        long now = Instant.now().getEpochSecond();
        byte[] raw = bytes(V2_BODY);
        Map<String, String> headers = Map.of(
                CallbackSignatureVerifier.SIGNATURE_HEADER,
                CallbackSignatureVerifier.sign("task_123", now, raw, SECRET),
                CallbackSignatureVerifier.TIMESTAMP_HEADER, String.valueOf(now),
                CallbackSignatureVerifier.PAYLOAD_VERSION_HEADER, "2");

        assertEquals("task_123", new CallbackSignatureVerifier(SECRET).verify(raw, headers::get).taskId());
    }

    @Test
    @DisplayName("a payload version 1 delivery reads task_id and maps no record")
    void version1() {
        long now = Instant.now().getEpochSecond();
        byte[] raw = bytes(V1_BODY);
        String signature = CallbackSignatureVerifier.sign("task_123", now, raw, SECRET);

        WebhookDelivery delivery =
                new CallbackSignatureVerifier(SECRET).verify(raw, signature, String.valueOf(now), "1");

        assertEquals("task_123", delivery.taskId());
        assertEquals(1, delivery.payloadVersion());
        assertFalse(delivery.task().isPresent());
        assertFalse(delivery.deliveryId().isPresent(), "version 1 carries no request_id");
    }

    @Test
    @DisplayName("a body edited after signing is a mismatch, not a stale delivery")
    void tamperedBody() {
        long now = Instant.now().getEpochSecond();
        byte[] raw = bytes(V2_BODY);
        String signature = CallbackSignatureVerifier.sign("task_123", now, raw, SECRET);
        byte[] tampered = bytes(V2_BODY.replace("0.09", "9.99"));

        SpicyWebhookException rejected = assertThrows(SpicyWebhookException.class,
                () -> new CallbackSignatureVerifier(SECRET).verify(tampered, signature, String.valueOf(now), "2"));

        assertEquals(SpicyWebhookException.Reason.SIGNATURE_MISMATCH, rejected.reason());
    }

    @Test
    @DisplayName("a correctly signed but old delivery is rejected as a replay, and named as one")
    void staleDelivery() {
        long stale = Instant.now().getEpochSecond() - 3600;
        byte[] raw = bytes(V2_BODY);
        String signature = CallbackSignatureVerifier.sign("task_123", stale, raw, SECRET);

        SpicyWebhookException rejected = assertThrows(SpicyWebhookException.class,
                () -> new CallbackSignatureVerifier(SECRET).verify(raw, signature, String.valueOf(stale), "2"));

        assertEquals(SpicyWebhookException.Reason.TIMESTAMP_OUTSIDE_TOLERANCE, rejected.reason());
    }

    @Test
    @DisplayName("a payload version this release does not implement is refused rather than guessed at")
    void unknownPayloadVersion() {
        long now = Instant.now().getEpochSecond();
        byte[] raw = bytes(V2_BODY);
        String signature = CallbackSignatureVerifier.sign("task_123", now, raw, SECRET);

        SpicyWebhookException rejected = assertThrows(SpicyWebhookException.class,
                () -> new CallbackSignatureVerifier(SECRET).verify(raw, signature, String.valueOf(now), "3"));

        assertEquals(SpicyWebhookException.Reason.UNSUPPORTED_PAYLOAD_VERSION, rejected.reason());
    }

    @Test
    @DisplayName("each missing or malformed header names itself")
    void missingHeaders() {
        long now = Instant.now().getEpochSecond();
        byte[] raw = bytes(V2_BODY);
        String signature = CallbackSignatureVerifier.sign("task_123", now, raw, SECRET);
        CallbackSignatureVerifier verifier = new CallbackSignatureVerifier(SECRET);

        assertEquals(SpicyWebhookException.Reason.MISSING_HEADER,
                assertThrows(SpicyWebhookException.class,
                        () -> verifier.verify(raw, null, String.valueOf(now), "2")).reason());
        assertEquals(SpicyWebhookException.Reason.MISSING_HEADER,
                assertThrows(SpicyWebhookException.class,
                        () -> verifier.verify(raw, signature, "  ", "2")).reason());
        assertEquals(SpicyWebhookException.Reason.MISSING_HEADER,
                assertThrows(SpicyWebhookException.class,
                        () -> verifier.verify(raw, signature, String.valueOf(now), null)).reason());
        assertEquals(SpicyWebhookException.Reason.MALFORMED_TIMESTAMP,
                assertThrows(SpicyWebhookException.class,
                        () -> verifier.verify(raw, signature, "yesterday", "2")).reason());
    }

    @Test
    @DisplayName("a body that is not the declared shape is malformed, not forged")
    void malformedBody() {
        long now = Instant.now().getEpochSecond();
        CallbackSignatureVerifier verifier = new CallbackSignatureVerifier(SECRET);
        byte[] notJson = bytes("not json at all");
        byte[] noTaskId = bytes("{\"data\":{}}");

        assertEquals(SpicyWebhookException.Reason.MALFORMED_BODY,
                assertThrows(SpicyWebhookException.class,
                        () -> verifier.verify(notJson, "sig", String.valueOf(now), "2")).reason());
        assertEquals(SpicyWebhookException.Reason.MALFORMED_BODY,
                assertThrows(SpicyWebhookException.class,
                        () -> verifier.verify(noTaskId, "sig", String.valueOf(now), "2")).reason());
    }

    @Test
    @DisplayName("the signature is deterministic and covers all three of its inputs")
    void signatureCoversItsInputs() {
        byte[] body = bytes("{\"a\":1}");
        String reference = CallbackSignatureVerifier.sign("t1", 1700000000L, body, "k");

        assertEquals(reference, CallbackSignatureVerifier.sign("t1", 1700000000L, body, "k"));
        assertFalse(reference.equals(CallbackSignatureVerifier.sign("t2", 1700000000L, body, "k")));
        assertFalse(reference.equals(CallbackSignatureVerifier.sign("t1", 1700000001L, body, "k")));
        assertFalse(reference.equals(CallbackSignatureVerifier.sign("t1", 1700000000L, bytes("{\"a\":2}"), "k")));
        assertFalse(reference.equals(CallbackSignatureVerifier.sign("t1", 1700000000L, body, "k2")));
        assertThrows(IllegalArgumentException.class,
                () -> CallbackSignatureVerifier.sign("t1", 1700000000L, body, ""));
    }

    @Test
    @DisplayName("the timestamp is signed as it arrived, not as it parses")
    void timestampIsSignedAsReceived() {
        long now = Instant.now().getEpochSecond();
        byte[] raw = bytes(V2_BODY);
        String canonical = String.valueOf(now);
        String padded = "0" + canonical;   // parses to the same long, but is not the same text
        CallbackSignatureVerifier verifier = new CallbackSignatureVerifier(SECRET);
        String signedOverCanonical = CallbackSignatureVerifier.sign("task_123", now, raw, SECRET);

        // The timestamp is the second segment of the signing string, and the signature has to
        // cover it verbatim. Running the header through parseLong before signing would sign the
        // normalised spelling instead, so a valid signature over "1700000000" would also validate
        // "01700000000" - the header would no longer be protected by the signature, and it is
        // precisely the replay defence. The opposite direction is just as bad: if the server ever
        // did send a non-canonical spelling, a genuine delivery would be judged a forgery. The
        // contract's pattern is ^[0-9]+$ and today's server does not emit such a value, but that is
        // someone else's implementation detail. The TypeScript, Python and PHP clients all sign the
        // raw text.
        assertEquals(SpicyWebhookException.Reason.SIGNATURE_MISMATCH,
                assertThrows(SpicyWebhookException.class,
                        () -> verifier.verify(raw, signedOverCanonical, padded, "2")).reason());

        // The control: the canonical spelling still verifies. The assertion above does not pass by
        // rejecting everything.
        assertEquals("task_123", verifier.verify(raw, signedOverCanonical, canonical, "2").taskId());
    }

    @Test
    @DisplayName("a widened window accepts what the default window rejects, and never a bad signature")
    void configurableTolerance() {
        long stale = Instant.now().getEpochSecond() - 3600;
        byte[] raw = bytes(V2_BODY);
        String signature = CallbackSignatureVerifier.sign("task_123", stale, raw, SECRET);

        WebhookDelivery accepted = new CallbackSignatureVerifier(SECRET, java.time.Duration.ofHours(2))
                .verify(raw, signature, String.valueOf(stale), "2");
        assertEquals("task_123", accepted.taskId());

        assertThrows(IllegalArgumentException.class,
                () -> new CallbackSignatureVerifier(SECRET, java.time.Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new CallbackSignatureVerifier(" "));
    }
}
