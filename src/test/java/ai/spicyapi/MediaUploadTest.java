package ai.spicyapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The three upload steps, plus the two properties that can only fail silently: ticket headers are
 * forwarded verbatim, and the hop to object storage carries no API key. */
class MediaUploadTest {

    private static final String COMMITTED = """
            {"fileId":"fil_abc","status":"ready","bytes":4,"contentType":"image/png",
             "sha256":"0000000000000000000000000000000000000000000000000000000000000000",
             "uri":"spicy://f/fil_abc","expiresAt":"2026-09-21T08:30:00Z","width":2,"height":2}""";

    private static StubServer uploadStub() throws Exception {
        StubServer server = new StubServer();
        server.on("/api/v1/common/upload-url", (request, exchange) -> StubServer.respond(exchange, 200,
                StubServer.envelope("""
                        {"fileId":"fil_abc","key":"spicy://f/fil_abc",
                         "uploadUrl":"http://127.0.0.1:%d/storage/put?sig=abc","method":"PUT",
                         "headers":{"Content-Type":"image/png","x-amz-meta-owner":"acct_1","Content-Length":"999"},
                         "expiresAt":"2026-09-20T09:00:00Z","maxBytes":10485760}""".formatted(server.port()))));
        server.on("/storage/put", (request, exchange) -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.onData("/api/v1/files/", COMMITTED);
        return server;
    }

    @Test
    @DisplayName("the three steps run, and the committed URI is what goes into model input")
    void threeSteps() throws Exception {
        try (StubServer server = uploadStub()) {
            UploadedFile file = server.client().uploadBytes(new byte[] {1, 2, 3, 4}, UploadContentType.IMAGE_PNG);

            assertEquals("spicy://f/fil_abc", file.uri());
            assertTrue(file.ready());
            assertEquals("/api/v1/files/fil_abc/commit", server.first("/files/").path());
            assertEquals("POST", server.first("/files/").method());
        }
    }

    @Test
    @DisplayName("ticket headers are forwarded verbatim; they are part of what the URL signed")
    void ticketHeadersForwardedVerbatim() throws Exception {
        try (StubServer server = uploadStub()) {
            server.client().uploadBytes(new byte[] {1, 2, 3, 4}, UploadContentType.IMAGE_PNG);

            StubServer.Recorded put = server.first("/storage/put");
            assertEquals("PUT", put.method());
            assertEquals("acct_1", put.header("x-amz-meta-owner"));
            assertEquals("image/png", put.header("Content-Type"));
            // Content-Length is set by the JDK itself to the real length of 4, not the
            // placeholder 999 carried in the ticket.
            assertEquals("4", put.header("Content-Length"));
        }
    }

    @Test
    @DisplayName("the storage hop carries no Authorization; the URL is itself the grant")
    void noKeyOnTheStorageHop() throws Exception {
        try (StubServer server = uploadStub()) {
            server.client().uploadBytes(new byte[] {1, 2, 3, 4}, UploadContentType.IMAGE_PNG);

            assertFalse(server.first("/storage/put").hasHeader("Authorization"));
            assertTrue(server.first("/upload-url").hasHeader("Authorization"),
                    "the API hop does need the key; only the storage hop does not");
        }
    }

    @Test
    @DisplayName("an oversized file is refused locally, before any request is made")
    void oversizedRefusedLocally() throws Exception {
        try (StubServer server = uploadStub()) {
            SpicyClient client = server.client();

            assertThrows(IllegalArgumentException.class,
                    () -> client.createUploadUrl(UploadContentType.IMAGE_PNG, 11L * 1024 * 1024));
            assertThrows(IllegalArgumentException.class,
                    () -> client.createUploadUrl(UploadContentType.VIDEO_MP4, 91L * 1024 * 1024));
            assertThrows(IllegalArgumentException.class,
                    () -> client.createUploadUrl(UploadContentType.IMAGE_PNG, 0));
            assertEquals(0, server.count("/upload-url"), "none of the three left the process");
        }
    }

    @Test
    @DisplayName("a PUT that storage refuses is an upload failure, with storage's own status on it")
    void storageRefusal() throws Exception {
        try (StubServer server = new StubServer()) {
            server.on("/api/v1/common/upload-url", (request, exchange) -> StubServer.respond(exchange, 200,
                    StubServer.envelope("""
                            {"fileId":"fil_abc","key":"spicy://f/fil_abc",
                             "uploadUrl":"http://127.0.0.1:%d/storage/put?sig=abc","method":"PUT",
                             "headers":{"Content-Type":"image/png"},
                             "expiresAt":"2026-09-20T09:00:00Z","maxBytes":10485760}""".formatted(server.port()))));
            server.on("/storage/put", (request, exchange) -> {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
            });

            SpicyUploadException failure = assertThrows(SpicyUploadException.class,
                    () -> server.client().uploadBytes(new byte[] {1, 2, 3, 4}, UploadContentType.IMAGE_PNG));
            assertEquals(403, failure.httpStatus());
        }
    }

    @Test
    @DisplayName("uploadFile infers the media type from the extension, and says so when it cannot")
    void mediaTypeInference(@TempDir Path directory) throws Exception {
        try (StubServer server = uploadStub()) {
            Path png = Files.write(directory.resolve("reference.PNG"), new byte[] {1, 2, 3, 4});
            assertEquals("spicy://f/fil_abc", server.client().uploadFile(png).uri());

            Path unknown = Files.write(directory.resolve("reference.heic"), new byte[] {1, 2, 3, 4});
            SpicyClient client = server.client();
            assertThrows(IllegalArgumentException.class, () -> client.uploadFile(unknown));
        }
    }

    @Test
    @DisplayName("media types resolve from a string and from a file name, and reject the rest")
    void contentTypeLookup() {
        assertEquals(UploadContentType.IMAGE_PNG, UploadContentType.fromMediaType("image/png"));
        assertEquals(UploadContentType.IMAGE_JPEG, UploadContentType.fromMediaType("IMAGE/JPEG"));
        assertThrows(IllegalArgumentException.class, () -> UploadContentType.fromMediaType("image/png; charset=x"));
        assertThrows(IllegalArgumentException.class, () -> UploadContentType.fromMediaType("image/heic"));

        assertEquals(Optional.of(UploadContentType.VIDEO_MP4), UploadContentType.fromFileName("clip.mp4"));
        assertEquals(Optional.of(UploadContentType.IMAGE_JPEG), UploadContentType.fromFileName("/a/b/c.JPG"));
        assertEquals(Optional.empty(), UploadContentType.fromFileName("noextension"));
        assertEquals(Optional.empty(), UploadContentType.fromFileName("trailing."));
        assertEquals(Optional.empty(), UploadContentType.fromFileName(null));

        assertEquals(10L * 1024 * 1024, UploadContentType.IMAGE_PNG.maxBytes());
        assertEquals(90L * 1024 * 1024, UploadContentType.AUDIO_WAV.maxBytes());
    }

    @Test
    @DisplayName("a download URL can be minted for a named output")
    void downloadUrl() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/common/download-url", """
                {"key":"out-0","url":"https://cdn.example.test/a.png?sig=y",
                 "expiresAt":"2026-09-20T09:20:00Z"}""")) {
            DownloadTicket ticket = server.client().createDownloadUrl("task_123", "out-0");

            assertEquals("https://cdn.example.test/a.png?sig=y", ticket.url());
            assertTrue(server.first("download-url").body().contains("\"key\":\"out-0\""));

            server.client().createDownloadUrl("task_123");
            assertFalse(server.last("download-url").body().contains("\"key\""),
                    "no key means the first output, and an empty key is not sent");
        }
    }
}
