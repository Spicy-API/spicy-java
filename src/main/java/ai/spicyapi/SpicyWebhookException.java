package ai.spicyapi;

/**
 * An inbound webhook delivery could not be authenticated.
 *
 * <p>Every path out of {@link CallbackSignatureVerifier#verify} that is not a valid, fresh,
 * correctly signed delivery throws this. Treat it as "discard and respond 400": a delivery that
 * fails here was either forged, replayed, or mangled in transit, and none of those should reach
 * the business logic.
 *
 * <p>The type is deliberately not tied to one signature scheme, so that a verifier for a second
 * scheme can reject deliveries through the same taxonomy.
 */
public final class SpicyWebhookException extends SpicyException {

    private static final long serialVersionUID = 1L;

    /** Why a delivery was rejected. */
    public enum Reason {
        /** A required header was absent or blank. */
        MISSING_HEADER,
        /** The timestamp header was not a Unix timestamp. */
        MALFORMED_TIMESTAMP,
        /** The body was not the JSON shape the declared payload version promises. */
        MALFORMED_BODY,
        /** The payload version header named a version this release does not implement. */
        UNSUPPORTED_PAYLOAD_VERSION,
        /** The signature did not match. The delivery did not come from SpicyAPI. */
        SIGNATURE_MISMATCH,
        /** The signature matched but the timestamp is outside the replay window. */
        TIMESTAMP_OUTSIDE_TOLERANCE
    }

    private final transient Reason reason;

    /**
     * @param reason  the category of rejection
     * @param message what was wrong, with no secret material in it
     */
    public SpicyWebhookException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Why the delivery was rejected.
     *
     * @return the rejection category, never null
     */
    public Reason reason() {
        return reason;
    }
}
