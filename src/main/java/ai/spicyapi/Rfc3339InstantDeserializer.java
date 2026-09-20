package ai.spicyapi;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Reads RFC 3339 timestamps, with or without an explicit offset. */
final class Rfc3339InstantDeserializer extends JsonDeserializer<Instant> {

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String text = parser.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException withOffset) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException plain) {
                // Only fail once both parses have failed, and attach the first failure as
                // suppressed - otherwise whoever debugs this sees only "not an Instant" and never
                // learns it was not an offset form either.
                plain.addSuppressed(withOffset);
                throw new IOException("not an RFC 3339 timestamp: " + text, plain);
            }
        }
    }
}
