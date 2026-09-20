package ai.spicyapi;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Reads the contract's {@code format: date} values.
 *
 * <p>Separate from {@link Rfc3339InstantDeserializer} because the two are not the same thing.
 * A usage day is a calendar date with no time and no zone; turning one into an {@link java.time.Instant}
 * would invent a midnight and a zone the value never had, and every reader would then have to
 * guess which zone that was.
 */
final class IsoLocalDateDeserializer extends JsonDeserializer<LocalDate> {

    @Override
    public LocalDate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String text = parser.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException malformed) {
            throw new IOException("not an ISO calendar date: " + text, malformed);
        }
    }
}
