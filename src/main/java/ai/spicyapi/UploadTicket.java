package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * A presigned slot for exactly the declared number of bytes.
 *
 * @param fileId    the identifier {@link SpicyClient#commitUploadedFile} takes
 * @param key       the eventual {@code spicy://f/fil_...} URI, not usable until the commit
 *                  succeeds
 * @param uploadUrl where to PUT the bytes; a signed URL at object storage, not at the API
 * @param method    always {@code PUT}
 * @param headers   headers the PUT must carry <em>verbatim</em>; they are part of what the URL
 *                  signed
 * @param expiresAt when the slot stops accepting the upload
 * @param maxBytes  the authoritative size ceiling for this ticket
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UploadTicket(String fileId, String key, String uploadUrl, String method,
                           Map<String, String> headers, Instant expiresAt, long maxBytes) {

    // The headers in a ticket are part of what was signed; copy and freeze them so they cannot be
    // edited before the upload.
    public UploadTicket {
        headers = Internal.unmodifiableCopy(headers == null ? Map.of() : headers);
    }
}
