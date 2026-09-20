package ai.spicyapi;

/**
 * The presigned upload leg failed.
 *
 * <p>Separate from {@link SpicyApiException} because object storage answers for itself: there
 * is no platform envelope, no business code and no {@code request_id} on that hop.
 */
public final class SpicyUploadException extends SpicyException {

    private static final long serialVersionUID = 1L;

    /**
     * @serial the status object storage returned, or 0 when the check failed locally
     */
    private final int httpStatus;

    /**
     * @param message    what failed
     * @param httpStatus status returned by object storage, or 0 when the failure was local
     */
    public SpicyUploadException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    /**
     * Status object storage returned, or 0 when the check failed locally.
     *
     * @return the status object storage returned, or 0 when the check failed locally
     */
    public int httpStatus() {
        return httpStatus;
    }
}
