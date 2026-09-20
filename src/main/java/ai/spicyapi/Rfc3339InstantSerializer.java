package ai.spicyapi;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** Writes timestamps back in the same shape the service sends. */
final class Rfc3339InstantSerializer extends JsonSerializer<Instant> {

    @Override
    public void serialize(Instant value, JsonGenerator generator, SerializerProvider serializers)
            throws IOException {
        generator.writeString(DateTimeFormatter.ISO_INSTANT.format(value));
    }
}
