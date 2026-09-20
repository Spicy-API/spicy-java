package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * A committed file, ready to be referenced by a task.
 *
 * @param fileId          the identifier the ticket was issued under
 * @param status          {@code ready} once the service accepted the stored bytes
 * @param bytes           the stored size the service measured
 * @param contentType     the media type the service verified against the bytes
 * @param sha256          hash the service computed over the stored bytes
 * @param uri             the value to place in model input; the ticket's {@code key} is not
 *                        usable before the commit that produced this
 * @param expiresAt       when the uploaded material is removed
 * @param durationSeconds measured duration for audio and video, as a decimal string
 * @param width           measured pixel width, for images and video
 * @param height          measured pixel height, for images and video
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UploadedFile(String fileId, String status, long bytes, String contentType, String sha256,
                           String uri, Instant expiresAt, String durationSeconds, Integer width,
                           Integer height) {

    /**
     * Whether the service considers the file usable.
     *
     * @return true when the service considers the file usable
     */
    public boolean ready() {
        return "ready".equals(status);
    }
}
