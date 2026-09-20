package ai.spicyapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A real stub server bound to 127.0.0.1.
 *
 * <p>Built on {@code com.sun.net.httpserver} rather than WireMock because these tests are about the
 * bytes that actually go out - how a path was encoded, which headers really made it onto the wire,
 * whether a field is present in the body. A stub only needs to record requests faithfully; pulling
 * in a mocking framework would insert a layer of its own semantics in between.
 */
final class StubServer implements AutoCloseable {

    /** A single recorded request. */
    record Recorded(String method, String path, Map<String, List<String>> headers, String body) {

        /** HTTP header names are case-insensitive, and the stub server rewrites them into shapes
         * such as {@code Idempotency-key}, so this does not compare literally. */
        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().isEmpty() ? null : entry.getValue().get(0);
                }
            }
            return null;
        }

        boolean hasHeader(String name) {
            return header(name) != null;
        }
    }

    /** The handler for one context. */
    @FunctionalInterface
    interface Responder {
        void respond(Recorded request, HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(null);
        server.start();
    }

    StubServer on(String context, Responder responder) {
        server.createContext(context, exchange -> {
            Recorded request = record(exchange);
            responder.respond(request, exchange);
        });
        return this;
    }

    /** Always answers with one success envelope. */
    StubServer onData(String context, String data) {
        return on(context, (request, exchange) -> respond(exchange, 200, envelope(data)));
    }

    int port() {
        return server.getAddress().getPort();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port() + "/api/v1";
    }

    List<Recorded> recorded() {
        return List.copyOf(recorded);
    }

    Recorded first(String pathFragment) {
        return recorded().stream()
                .filter(r -> r.path().contains(pathFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no recorded request whose path contains " + pathFragment));
    }

    Recorded last(String pathFragment) {
        return recorded().stream()
                .filter(r -> r.path().contains(pathFragment))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no recorded request whose path contains " + pathFragment));
    }

    long count(String pathFragment) {
        return recorded().stream().filter(r -> r.path().contains(pathFragment)).count();
    }

    /** A client pointed at this stub, with retry and polling compressed to milliseconds. */
    SpicyClient client() {
        return clientBuilder().build();
    }

    SpicyClient.Builder clientBuilder() {
        return SpicyClient.builder()
                .apiKey("sk-spicy-0123456789abcdef")
                .baseUrl(baseUrl())
                .pollInterval(Duration.ofMillis(5), Duration.ofMillis(10))
                .retryDelays(Duration.ofMillis(1), Duration.ofMillis(5))
                .waitTimeout(Duration.ofSeconds(20));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static String envelope(String data) {
        return "{\"code\":200,\"msg\":\"success\",\"data\":" + data + ",\"request_id\":\"req_abc\"}";
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Recorded record(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String rawQuery = exchange.getRequestURI().getRawQuery();
        String path = exchange.getRequestURI().getRawPath() + (rawQuery == null ? "" : "?" + rawQuery);
        Recorded request =
                new Recorded(exchange.getRequestMethod(), path, Map.copyOf(exchange.getRequestHeaders()), body);
        recorded.add(request);
        return request;
    }
}
