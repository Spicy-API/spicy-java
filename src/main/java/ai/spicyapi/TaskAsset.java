package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Optional;

/**
 * One generated artifact.
 *
 * @param key             compatibility identifier, accepted by
 *                        {@link SpicyClient#createDownloadUrl}
 * @param signedUrl       a ready-to-use signed URL, normally valid for about twenty minutes and
 *                        never beyond the result retention period. Absent while pending or
 *                        unavailable. Fetch it with no credentials: the URL is itself the grant,
 *                        and attaching an API key only sends the key somewhere it was never
 *                        needed
 * @param expiresAt       expiry of {@code url}, which is not the retention deadline of the result
 * @param mime            the artifact's media type
 * @param width           pixel width, for visual artifacts
 * @param height          pixel height, for visual artifacts
 * @param durationSeconds measured duration, for audio and video
 * @param bytes           stored size
 * @param role            which output this is, when an endpoint produces more than one kind
 * @param nsfw            the service's own classification of the artifact, when it made one
 * @param pending         true while the artifact is still being stored; keep polling rather than
 *                        treating the task as finished
 * @param unavailable     true when this artifact will never arrive
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskAsset(String key, @JsonProperty("url") String signedUrl, Instant expiresAt, String mime,
                        Integer width, Integer height, Double durationSeconds, Long bytes, String role,
                        Boolean nsfw, boolean pending, boolean unavailable) {

    /**
     * The signed URL, when one is available.
     *
     * @return the URL, or empty while the artifact is pending or unavailable
     */
    public Optional<String> url() {
        return Optional.ofNullable(signedUrl);
    }

    /**
     * Whether this artifact can be downloaded right now.
     *
     * @return true when the artifact can be downloaded right now
     */
    public boolean readable() {
        return signedUrl != null && !pending && !unavailable;
    }
}
