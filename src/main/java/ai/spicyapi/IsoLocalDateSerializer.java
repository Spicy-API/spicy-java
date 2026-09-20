package ai.spicyapi;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.LocalDate;

/** Writes calendar dates back in the {@code YYYY-MM-DD} shape the service sends. */
final class IsoLocalDateSerializer extends JsonSerializer<LocalDate> {

    @Override
    public void serialize(LocalDate value, JsonGenerator generator, SerializerProvider serializers)
            throws IOException {
        generator.writeString(value.toString());
    }
}
