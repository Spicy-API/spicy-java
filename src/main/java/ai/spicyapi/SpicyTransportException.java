package ai.spicyapi;

/** No usable answer was obtained: DNS, TLS, a dropped connection, an interrupted thread. */
public sealed class SpicyTransportException extends SpicyException
        permits SpicyTimeoutException {

    private static final long serialVersionUID = 1L;

    /** @param message what failed */
    public SpicyTransportException(String message) {
        super(message);
    }

    /**
     * @param message what failed
     * @param cause   the underlying I/O or interruption failure
     */
    public SpicyTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
