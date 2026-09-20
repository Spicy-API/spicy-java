package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Media types accepted by direct upload, with the size ceiling that applies to each.
 *
 * <p>A publicly reachable HTTPS URL can be placed in model input directly and needs no upload
 * at all; these types matter only for bytes held locally.
 */
public enum UploadContentType {

    /** JPEG image, up to 10 MiB. */
    IMAGE_JPEG("image/jpeg", 10L * 1024 * 1024),
    /** PNG image, up to 10 MiB. */
    IMAGE_PNG("image/png", 10L * 1024 * 1024),
    /** WebP image, up to 10 MiB. */
    IMAGE_WEBP("image/webp", 10L * 1024 * 1024),
    /** GIF image, up to 10 MiB. */
    IMAGE_GIF("image/gif", 10L * 1024 * 1024),
    /** MP4 video, up to 90 MiB and 600 seconds. */
    VIDEO_MP4("video/mp4", 90L * 1024 * 1024),
    /** WebM video, up to 90 MiB and 600 seconds. */
    VIDEO_WEBM("video/webm", 90L * 1024 * 1024),
    /** MP3 audio, up to 90 MiB and 600 seconds. */
    AUDIO_MPEG("audio/mpeg", 90L * 1024 * 1024),
    /** WAV audio, up to 90 MiB and 600 seconds. */
    AUDIO_WAV("audio/wav", 90L * 1024 * 1024);

    private static final Map<String, UploadContentType> BY_EXTENSION = Map.ofEntries(
            Map.entry("jpg", IMAGE_JPEG),
            Map.entry("jpeg", IMAGE_JPEG),
            Map.entry("png", IMAGE_PNG),
            Map.entry("webp", IMAGE_WEBP),
            Map.entry("gif", IMAGE_GIF),
            Map.entry("mp4", VIDEO_MP4),
            Map.entry("webm", VIDEO_WEBM),
            Map.entry("mp3", AUDIO_MPEG),
            Map.entry("wav", AUDIO_WAV));

    private final String mediaType;
    private final long maxBytes;

    UploadContentType(String mediaType, long maxBytes) {
        this.mediaType = mediaType;
        this.maxBytes = maxBytes;
    }

    /**
     * The IANA media type sent to the service.
     *
     * @return the media type string, for example {@code image/png}
     */
    @JsonValue
    public String mediaType() {
        return mediaType;
    }

    /**
     * The documented ceiling for this type.
     *
     * <p>Checked locally so an oversized file fails before it is uploaded; the ticket returned
     * by the service carries the authoritative limit in {@link UploadTicket#maxBytes()}.
     *
     * @return the documented ceiling, in bytes
     */
    public long maxBytes() {
        return maxBytes;
    }

    /**
     * Resolves a media type string.
     *
     * @param mediaType for example {@code image/png}; parameters such as {@code ; charset} are
     *                  not accepted because the service matches the type exactly
     * @return the matching constant
     * @throws IllegalArgumentException when the type is not one this platform accepts
     */
    public static UploadContentType fromMediaType(String mediaType) {
        String normalized = Internal.requireText(mediaType, "mediaType").toLowerCase(Locale.ROOT);
        for (UploadContentType type : values()) {
            if (type.mediaType.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unsupported upload media type: " + mediaType);
    }

    /**
     * Guesses the media type from a file name extension.
     *
     * @param fileName a file name or path
     * @return the matching constant, or empty when the extension is unknown or absent
     */
    public static Optional<UploadContentType> fromFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_EXTENSION.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }
}
