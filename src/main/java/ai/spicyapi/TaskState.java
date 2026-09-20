package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle state of a task.
 *
 * <p>The contract closes this set, which is why it is an enum rather than a string. An
 * unrecognised value still deserializes, as {@link #UNKNOWN}, so that a newer service cannot
 * break an older client at the parsing layer; {@link SpicyClient#waitForTerminal} refuses to
 * keep polling on it instead.
 */
public enum TaskState {

    /** Accepted, funds held, not yet started. */
    QUEUED("queued"),
    /** Being generated. */
    RUNNING("running"),
    /** Finished with a result. */
    SUCCEEDED("succeeded"),
    /** Finished without a result; the hold was released in full. */
    FAILED("failed"),
    /** Stopped before completion; the hold was released in full. */
    CANCELED("canceled"),
    /** Passed its execution deadline; the hold was released in full. */
    EXPIRED("expired"),
    /** A value this client release does not know. Never sent by a client. */
    UNKNOWN("");

    private final String wireValue;

    TaskState(String wireValue) {
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
     * Maps a wire value, tolerating values added after this release.
     *
     * @param value the wire value, may be {@code null}
     * @return the matching constant, {@link #UNKNOWN} for anything unrecognised, or
     *         {@code null} for {@code null}
     */
    @JsonCreator
    public static TaskState fromWireValue(String value) {
        if (value == null) {
            return null;
        }
        for (TaskState state : values()) {
            if (state.wireValue.equals(value)) {
                return state;
            }
        }
        return UNKNOWN;
    }

    /**
     * Whether the task has finished, whatever the outcome.
     *
     * @return true when the task has finished
     */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED || this == EXPIRED;
    }

    /**
     * Whether the task is still expected to change state.
     *
     * @return true when the task may still change state
     */
    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }
}
