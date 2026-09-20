package ai.spicyapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticates deliveries sent to the {@code callBackUrl} of a task.
 *
 * <p><b>This class implements one signature scheme, and names it.</b> The scheme is the one the
 * platform uses for task callbacks: three {@code X-Webhook-*} headers, and an HMAC-SHA256 over
 * {@code taskId.timestamp.sha256hex(body)} keyed with the account webhook secret as raw UTF-8
 * bytes. It is not a general "webhook verifier"; a delivery signed under any other scheme will
 * be rejected here, correctly and unhelpfully, as a signature mismatch. A second scheme, when
 * the platform ships one, gets a verifier of its own next to this one rather than a mode flag
 * inside it — the two differ in their headers, in what bytes are signed and in how the key is
 * derived, and a class that tried to be both would have to guess which one arrived.
 *
 * <p>Build one per account webhook secret and reuse it; instances are immutable and
 * thread-safe. The secret is held as bytes and never appears in an exception message.
 *
 * <p>A signature covers the task ID, the timestamp and a digest of the body. All three matter:
 * without the body digest, anyone who ever observed one legitimate delivery could pair that
 * signature with a payload of their own and have it verify, which would mean SpicyAPI appearing
 * to vouch for a fabricated result. Without the timestamp, a genuine old delivery could be
 * replayed forever.
 *
 * <pre>{@code
 * CallbackSignatureVerifier verifier =
 *         new CallbackSignatureVerifier(System.getenv("SPICY_WEBHOOK_SECRET"));
 *
 * byte[] body = request.getInputStream().readAllBytes();   // the raw bytes, never re-serialized
 * WebhookDelivery delivery = verifier.verify(body, request::getHeader);
 * }</pre>
 */
public final class CallbackSignatureVerifier {

    /** Header carrying the Base64 HMAC-SHA256 signature. */
    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";

    /** Header carrying the Unix timestamp the signature covers. */
    public static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";

    /** Header carrying the payload shape version, 1 or 2. */
    public static final String PAYLOAD_VERSION_HEADER = "X-Webhook-Payload-Version";

    /** Default accepted clock skew in either direction. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private final byte[] secret;
    private final Duration tolerance;
    private final Clock clock;
    private final ObjectMapper mapper;

    /**
     * A verifier with the default five-minute replay window and the system clock.
     *
     * @param secret the account webhook secret
     * @throws IllegalArgumentException when the secret is blank
     */
    public CallbackSignatureVerifier(String secret) {
        this(secret, DEFAULT_TOLERANCE, Clock.systemUTC(), SpicyJson.DEFAULT);
    }

    /**
     * A verifier with a custom replay window.
     *
     * @param secret    the account webhook secret
     * @param tolerance accepted clock skew in either direction; a wider window accepts more
     *                  replayed deliveries, a narrower one rejects honest ones on a skewed host
     * @throws IllegalArgumentException when the secret is blank or the tolerance is negative
     */
    public CallbackSignatureVerifier(String secret, Duration tolerance) {
        this(secret, tolerance, Clock.systemUTC(), SpicyJson.DEFAULT);
    }

    /**
     * A fully configured verifier.
     *
     * @param secret    the account webhook secret
     * @param tolerance accepted clock skew in either direction
     * @param clock     the clock to compare timestamps against; injectable for tests
     * @param mapper    the mapper used to read the body; must have
     *                  {@link SpicyClient#jacksonModule()} registered
     * @throws IllegalArgumentException when the secret is blank or the tolerance is negative
     */
    public CallbackSignatureVerifier(String secret, Duration tolerance, Clock clock, ObjectMapper mapper) {
        this.secret = Internal.requireText(secret, "secret").getBytes(StandardCharsets.UTF_8);
        Objects.requireNonNull(tolerance, "tolerance");
        if (tolerance.isNegative()) {
            throw new IllegalArgumentException("tolerance must not be negative");
        }
        this.tolerance = tolerance;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Verifies a callback delivery, taking the three headers from a lookup function.
     *
     * <p>Convenient for servlet-style receivers: pass {@code request::getHeader}. HTTP header
     * names are case-insensitive, so the lookup must be too; most frameworks already are.
     *
     * @param rawBody      the exact bytes received, never a re-serialized copy: re-encoding
     *                     changes whitespace or key order and the digest stops matching
     * @param headerLookup returns a header value by name, or {@code null} when absent
     * @return the verified delivery
     * @throws NullPointerException  when either argument is null
     * @throws SpicyWebhookException when the delivery is not authentic, is stale, or is
     *                               malformed; respond 400 and discard it
     */
    public WebhookDelivery verify(byte[] rawBody, Function<String, String> headerLookup) {
        Objects.requireNonNull(headerLookup, "headerLookup");
        return verify(rawBody,
                headerLookup.apply(SIGNATURE_HEADER),
                headerLookup.apply(TIMESTAMP_HEADER),
                headerLookup.apply(PAYLOAD_VERSION_HEADER));
    }

    /**
     * Verifies a callback delivery from explicit header values.
     *
     * <p>Order of checks is deliberate: authenticate first, then judge freshness. A stale but
     * genuine delivery and a forged one are different incidents, and reporting the forged one as
     * "stale" would hide it.
     *
     * @param rawBody        the exact bytes received
     * @param signature      value of {@value #SIGNATURE_HEADER}
     * @param timestamp      value of {@value #TIMESTAMP_HEADER}
     * @param payloadVersion value of {@value #PAYLOAD_VERSION_HEADER}
     * @return the verified delivery
     * @throws NullPointerException  when {@code rawBody} is null
     * @throws SpicyWebhookException when a header is missing or malformed, the body does not
     *                               match the declared version, the signature does not match, or
     *                               the timestamp is outside the replay window
     */
    public WebhookDelivery verify(byte[] rawBody, String signature, String timestamp, String payloadVersion) {
        Objects.requireNonNull(rawBody, "rawBody");
        String presentedSignature = requireHeader(signature, SIGNATURE_HEADER);
        String sentAtText = requireHeader(timestamp, TIMESTAMP_HEADER);
        long sentAt = parseTimestamp(sentAtText);
        int version = parseVersion(requireHeader(payloadVersion, PAYLOAD_VERSION_HEADER));

        JsonNode body = readBody(rawBody);
        String taskId = extractTaskId(body, version);
        // Sign the text as received, not the number it parses into.
        //
        // The difference only shows up for non-canonical spellings such as "0123", "+123" or
        // "00000000123": all of them parse to 123, so the signature would be computed over "123"
        // while the other side signed the characters it actually sent. The two consequences point
        // in opposite directions:
        //   - if the server ever did send a non-canonical spelling, we would treat a genuine
        //     delivery as a forgery;
        //   - conversely, a valid signature over "123" would also validate "0123" - the signature
        //     would no longer cover this header verbatim, and it is the second segment of the
        //     signing string.
        // Today's server formats an int64 with %d and never emits such a spelling (the contract's
        // pattern is ^[0-9]+$ as well), so this is not a live hole; but "it does not do that today"
        // is someone else's implementation detail, not a property to depend on. The TypeScript,
        // Python and PHP clients all sign the raw text too.
        String expected = sign(taskId, sentAtText, rawBody, secret);

        // Constant-time comparison. String.equals returns at the first differing byte, turning
        // "the first few characters matched" into an observable timing difference - enough for a
        // patient attacker to recover the signature one byte at a time.
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presentedSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new SpicyWebhookException(SpicyWebhookException.Reason.SIGNATURE_MISMATCH,
                    "the delivery signature does not match; the request did not come from SpicyAPI");
        }

        long skewSeconds = Math.abs(clock.instant().getEpochSecond() - sentAt);
        if (skewSeconds > tolerance.toSeconds()) {
            throw new SpicyWebhookException(SpicyWebhookException.Reason.TIMESTAMP_OUTSIDE_TOLERANCE,
                    "the delivery is signed correctly but its timestamp is " + skewSeconds
                            + "s away from now, outside the " + tolerance.toSeconds() + "s window");
        }
        return new WebhookDelivery(taskId, version, version == 2 ? readTask(body) : null, body);
    }

    /**
     * Computes the callback signature for a delivery.
     *
     * <p>Exposed so that tests and local receivers can produce a delivery this verifier accepts,
     * without reimplementing the scheme and drifting from it.
     *
     * @param taskId    the task the delivery concerns
     * @param timestamp Unix timestamp in seconds
     * @param rawBody   the exact bytes of the body
     * @param secret    the account webhook secret
     * @return the Base64-encoded HMAC-SHA256 signature
     * @throws IllegalArgumentException when {@code taskId} or {@code secret} is blank
     */
    public static String sign(String taskId, long timestamp, byte[] rawBody, String secret) {
        return sign(Internal.requireText(taskId, "taskId"), Long.toString(timestamp), rawBody,
                Internal.requireText(secret, "secret").getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String taskId, String timestamp, byte[] rawBody, byte[] secret) {
        String message = taskId + "." + timestamp + "." + HexFormat.of().formatHex(Internal.sha256(rawBody));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException(
                    "HmacSHA256 is required of every Java platform but is unavailable here", unavailable);
        }
    }

    private static String requireHeader(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new SpicyWebhookException(SpicyWebhookException.Reason.MISSING_HEADER,
                    name + " is missing");
        }
        return value.trim();
    }

    private static long parseTimestamp(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            throw new SpicyWebhookException(SpicyWebhookException.Reason.MALFORMED_TIMESTAMP,
                    TIMESTAMP_HEADER + " is not a Unix timestamp");
        }
    }

    private static int parseVersion(String value) {
        if ("1".equals(value) || "2".equals(value)) {
            return Integer.parseInt(value);
        }
        throw new SpicyWebhookException(SpicyWebhookException.Reason.UNSUPPORTED_PAYLOAD_VERSION,
                PAYLOAD_VERSION_HEADER + " declared payload version " + value
                        + ", which this client release does not implement");
    }

    private JsonNode readBody(byte[] rawBody) {
        try {
            JsonNode body = mapper.readTree(rawBody);
            if (body == null || !body.isObject()) {
                throw new SpicyWebhookException(SpicyWebhookException.Reason.MALFORMED_BODY,
                        "the delivery body was not a JSON object");
            }
            return body;
        } catch (IOException malformed) {
            throw new SpicyWebhookException(SpicyWebhookException.Reason.MALFORMED_BODY,
                    "the delivery body was not valid JSON");
        }
    }

    private static String extractTaskId(JsonNode body, int version) {
        // The two versions put the task id in different places, and it is the first segment of the
        // signing string: reading the wrong one produces a signature mismatch that looks like a
        // forgery when it is only a misread version.
        JsonNode node = version == 1 ? body.get("task_id") : body.path("data").get("taskId");
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new SpicyWebhookException(SpicyWebhookException.Reason.MALFORMED_BODY,
                    "the payload version " + version + " body carried no task identifier");
        }
        return node.asText();
    }

    private TaskRecord readTask(JsonNode body) {
        try {
            return mapper.treeToValue(body.get("data"), TaskRecord.class);
        } catch (JsonProcessingException malformed) {
            throw new SpicyWebhookException(SpicyWebhookException.Reason.MALFORMED_BODY,
                    "the delivery body did not contain a readable task record");
        }
    }
}
