package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable failure identifier on a failed task.
 *
 * <p>The nine values are the entire public vocabulary. Per the contract an unrecognised value
 * is to be handled as {@link #UPSTREAM_FAILED}, and that is exactly what deserialization does,
 * so client code can switch on this enum without a defensive default that never fires.
 */
public enum TaskErrorCode {

    /** The input did not satisfy the model's schema. Fix the request. */
    INVALID_REQUEST("invalid_request"),
    /** Valid input, but no deployment serves this combination of parameters. Change one. */
    UNSUPPORTED_COMBINATION("unsupported_combination"),
    /** The model refused the content. Retrying the same prompt reproduces it. */
    CONTENT_REJECTED("content_rejected"),
    /** Throttled somewhere along the path. Back off and try again. */
    RATE_LIMITED("rate_limited"),
    /** The model service was unreachable. Usually worth retrying. */
    UPSTREAM_UNAVAILABLE("upstream_unavailable"),
    /** Generation ran and did not produce a usable result. */
    GENERATION_FAILED("generation_failed"),
    /** The task passed its execution deadline. */
    TIMEOUT("timeout"),
    /** A referenced image, video or audio input could not be used. */
    INVALID_ASSET("invalid_asset"),
    /** The model service failed for a reason with no more specific identifier. */
    UPSTREAM_FAILED("upstream_failed");

    private final String wireValue;

    TaskErrorCode(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * The value as it appears on the wire.
     *
     * @return the wire value
     */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * Maps a wire value, folding anything unrecognised into {@link #UPSTREAM_FAILED} as the
     * contract requires.
     *
     * @param value the wire value, may be {@code null}
     * @return the matching constant, or {@code null} for {@code null}
     */
    @JsonCreator
    public static TaskErrorCode fromWireValue(String value) {
        if (value == null) {
            return null;
        }
        for (TaskErrorCode code : values()) {
            if (code.wireValue.equals(value)) {
                return code;
            }
        }
        return UPSTREAM_FAILED;
    }
}
