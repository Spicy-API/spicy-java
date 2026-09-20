package ai.spicyapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Every rejection at build time is one incident that will not happen in production. */
class ClientBuilderTest {

    @Test
    @DisplayName("plain HTTP is refused except on loopback, where a local test double needs it")
    void baseUrlScheme() {
        assertThrows(IllegalArgumentException.class,
                () -> SpicyClient.builder().apiKey("k").baseUrl("http://api.example.test/v1").build());
        assertEquals("http://127.0.0.1:8080/api/v1",
                SpicyClient.builder().apiKey("k").baseUrl("http://127.0.0.1:8080/api/v1").build().baseUrl());
        assertEquals("http://localhost:8080/api/v1",
                SpicyClient.builder().apiKey("k").baseUrl("http://localhost:8080/api/v1").build().baseUrl());
        assertEquals("https://api.spicyapi.ai/api/v1",
                SpicyClient.builder().apiKey("k").baseUrl(URI.create("https://api.spicyapi.ai/api/v1/")).build()
                        .baseUrl());
    }

    @Test
    @DisplayName("a base URL that is not usable as a root is refused, one reason at a time")
    void baseUrlShape() {
        assertThrows(IllegalArgumentException.class,
                () -> SpicyClient.builder().apiKey("k").baseUrl("not-a-url"));
        assertThrows(IllegalArgumentException.class,
                () -> SpicyClient.builder().apiKey("k").baseUrl("https://api.example.test/v1?token=x"));
        assertThrows(IllegalArgumentException.class,
                () -> SpicyClient.builder().apiKey("k").baseUrl("https://api.example.test/v1#frag"));
        assertThrows(IllegalArgumentException.class,
                () -> SpicyClient.builder().apiKey("k").baseUrl("https://user:pass@api.example.test/v1"));
    }

    @Test
    @DisplayName("a key with a line break in it is refused before it can split a request")
    void headerInjection() {
        assertThrows(IllegalArgumentException.class, () -> SpicyClient.builder().apiKey("bad\r\nkey"));
        assertThrows(IllegalArgumentException.class, () -> SpicyClient.builder().apiKey("  "));
        assertThrows(IllegalArgumentException.class, () -> SpicyClient.builder().errorLanguage("en\r\nX: y"));
    }

    @Test
    @DisplayName("a client with no key is refused at build time, not at first call")
    void keyIsRequired() {
        assertThrows(IllegalStateException.class, () -> SpicyClient.builder().build());
    }

    @Test
    @DisplayName("intervals and bounds are validated against each other, not just individually")
    void boundsAreValidated() {
        SpicyClient.Builder builder = SpicyClient.builder().apiKey("k");

        assertThrows(IllegalArgumentException.class, () -> builder.requestTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.uploadTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> builder.maxRetries(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.maxRetries(6));
        assertThrows(IllegalArgumentException.class,
                () -> builder.pollInterval(Duration.ofSeconds(10), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> builder.retryDelays(Duration.ofSeconds(10), Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("the defaults are the ones the documentation promises")
    void defaults() {
        SpicyClient client = SpicyClient.builder().apiKey("k").build();

        assertEquals("https://api.spicyapi.ai/api/v1", client.baseUrl());
        assertEquals(Duration.ofSeconds(30), client.requestTimeout());
        assertEquals(Duration.ofMinutes(10), client.uploadTimeout());
        assertEquals(Duration.ofMinutes(10), client.waitTimeout());
        assertEquals(3, client.maxRetries());
    }
}
