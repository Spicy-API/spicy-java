package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.time.Instant;
import java.time.LocalDate;

// The single place the mapping is configured. Both public factory methods on SpicyClient and the
// default mapper inside CallbackSignatureVerifier draw from here, so that "how the client is
// configured" and "how the verifier is configured" cannot become two descriptions that drift.
final class SpicyJson {

    static final ObjectMapper DEFAULT = newMapper();

    private SpicyJson() {
    }

    static Module module() {
        SimpleModule module =
                new SimpleModule("spicyapi-client", com.fasterxml.jackson.core.Version.unknownVersion());
        module.addDeserializer(Instant.class, new Rfc3339InstantDeserializer());
        module.addSerializer(Instant.class, new Rfc3339InstantSerializer());
        // The contract carries two kinds of time: RFC 3339 timestamps (format: date-time) and
        // calendar dates (format: date, used only by /usage). Both must be registered, or the
        // unregistered one has no handler in databind at all - and the symptom is an outright
        // parse failure, not a missing field: the whole response fails to map.
        module.addDeserializer(LocalDate.class, new IsoLocalDateDeserializer());
        module.addSerializer(LocalDate.class, new IsoLocalDateSerializer());
        return module;
    }

    static ObjectMapper newMapper() {
        return JsonMapper.builder()
                .addModule(module())
                // The server's contract grows new fields, and an older client must not fail to
                // parse because of it. Every DTO also carries @JsonIgnoreProperties; this is the
                // second line of defence.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }
}
