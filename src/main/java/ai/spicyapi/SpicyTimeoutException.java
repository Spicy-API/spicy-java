package ai.spicyapi;

import java.time.Duration;

/**
 * A local deadline elapsed.
 *
 * <p>Nothing was cancelled remotely. For a request that may already have been accepted, treat
 * this as "outcome unknown" rather than "did not happen".
 */
public final class SpicyTimeoutException extends SpicyTransportException {

    private static final long serialVersionUID = 1L;

    private final transient Duration timeout;

    /** @param timeout the local budget that elapsed */
    public SpicyTimeoutException(Duration timeout) {
        super("request exceeded the local " + timeout.toMillis() + "ms timeout");
        this.timeout = timeout;
    }

    /**
     * The budget that elapsed.
     *
     * @return the local budget that elapsed
     */
    public Duration timeout() {
        return timeout;
    }
}
