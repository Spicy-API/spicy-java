package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * A short-lived download URL for one output of one task.
 *
 * @param key       the asset this URL points at
 * @param url       fetch it with no credentials attached; the URL is itself the grant
 * @param expiresAt when the URL stops working
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DownloadTicket(String key, String url, Instant expiresAt) {
}
