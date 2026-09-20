package ai.spicyapi;

/**
 * Base of the client's error taxonomy.
 *
 * <p>Sealed, so {@code switch} over it is exhaustive and a new failure category added in a
 * future release is a compile error at the call site rather than a silent fall-through.
 *
 * <p>The {@code permits} clause is written out because the subclasses live in files of their own.
 * A sealed hierarchy declared across several files has to name its members explicitly; the
 * implicit form is only available when they are nested in the same source file.
 */
public abstract sealed class SpicyException extends RuntimeException
        permits SpicyApiException, SpicyProtocolException, SpicyTransportException,
                SpicyWaitTimeoutException, SpicyUploadException, SpicyWebhookException {

    private static final long serialVersionUID = 1L;

    SpicyException(String message) {
        super(message);
    }

    SpicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
