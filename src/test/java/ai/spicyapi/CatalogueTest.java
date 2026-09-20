package ai.spicyapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Catalogue queries: encoding, headers, and "an older client must not break when the server grows
 * one more field". */
class CatalogueTest {

    private static final String LIST = """
            {"total":1,"items":[{"model":"publisher/model-2.0/text-to-image","family":"publisher/model-2.0",
             "displayName":"Model 2.0","provider":"publisher","modality":"image","tasks":["text-to-image"],
             "async":true,"mature":false,"policyTier":"unrestricted","taskTimeoutSeconds":600,
             "enabled":true,"available":true,"quantityField":"","pricing":[{"variant":"720p","unit":"per_second",
             "price":"0.09","regularPrice":"0.10","currency":"USD","offerEndsAt":"2026-10-01T00:00:00Z"}],
             "inputSchema":{"type":"object","required":["prompt"]},"version":"v7","availability":"available",
             "badges":["commercial_use","brand_new_badge_from_the_future"],"relatedModels":[],
             "updatedAt":"2026-09-20T08:30:00Z","someFieldAddedNextYear":{"nested":true}}]}""";

    private static final String ONE = """
            {"model":"publisher/model-2.0/text-to-image","family":"publisher/model-2.0","displayName":"Model 2.0",
             "provider":"publisher","modality":"image","tasks":["text-to-image"],"async":true,"mature":false,
             "policyTier":"unrestricted","taskTimeoutSeconds":600,"enabled":true,"available":false,
             "quantityField":"","pricing":[],"inputSchema":{},"version":"v7","availability":"maintenance",
             "badges":[],"relatedModels":[],"updatedAt":"2026-09-20T08:30:00Z"}""";

    private static StubServer catalogue() throws IOException {
        StubServer server = new StubServer();
        return server.on("/api/v1/models", (request, exchange) -> StubServer.respond(exchange, 200,
                StubServer.envelope("/api/v1/models".equals(exchange.getRequestURI().getRawPath()) ? LIST : ONE)));
    }

    @Test
    @DisplayName("listModels maps the catalogue and tolerates fields this release has never seen")
    void listModels() throws Exception {
        try (StubServer server = catalogue()) {
            ModelPage page = server.client().listModels(
                    ModelFilter.all().withModality("image").withIncludeSchema(true).withSearch("a b"));

            assertEquals(1, page.total());
            assertEquals("publisher/model-2.0/text-to-image", page.modelIds().get(0));
            ApiModel model = page.items().get(0);
            assertTrue(model.callable(), "enabled and available means callable");
            assertEquals("text-to-image", model.task().orElseThrow());
            assertEquals(new BigDecimal("0.09"), model.pricing().get(0).price());
            assertTrue(model.pricing().get(0).discounted(), "regularPrice above price is a live discount");
            assertEquals(Instant.parse("2026-09-20T08:30:00Z"), model.updatedAt());
            assertTrue(model.inputSchema().has("required"), "the schema stays a tree, not fixed fields");
            assertTrue(model.badges().contains("brand_new_badge_from_the_future"),
                    "an unknown badge is data, not an error");
        }
    }

    @Test
    @DisplayName("filters go on the query string, URL-encoded, with unset fields omitted")
    void queryEncoding() throws Exception {
        try (StubServer server = catalogue()) {
            server.client().listModels(
                    ModelFilter.all().withModality("image").withIncludeSchema(true).withSearch("a b"));

            assertEquals("/api/v1/models?modality=image&search=a+b&includeSchema=1",
                    server.first("/models").path());
        }
    }

    @Test
    @DisplayName("every call carries the bearer key and the selected error language")
    void headers() throws Exception {
        try (StubServer server = catalogue()) {
            server.clientBuilder().errorLanguage("de").build().listModels();

            StubServer.Recorded request = server.first("/models");
            assertEquals("Bearer sk-spicy-0123456789abcdef", request.header("Authorization"));
            assertEquals("de", request.header("Accept-Language"));
            assertEquals("application/json", request.header("Accept"));
            assertTrue(request.header("User-Agent").startsWith("SpicyAPI-Java/"),
                    "the User-Agent is the only thing that tells us which release a caller runs");
        }
    }

    @Test
    @DisplayName("a model id with slashes becomes one path segment, not three")
    void modelIdIsOneSegment() throws Exception {
        try (StubServer server = catalogue()) {
            ApiModel model = server.client().getModel("publisher/model-2.0/text-to-image");

            assertEquals("/api/v1/models/publisher%2Fmodel-2.0%2Ftext-to-image", server.last("/models/").path());
            assertFalse(model.callable(), "available=false is not callable however enabled it is");
        }
    }

    @Test
    @DisplayName("an unset filter adds no query string at all")
    void emptyFilterHasNoQuery() throws Exception {
        try (StubServer server = catalogue()) {
            server.client().listModels();
            assertEquals("/api/v1/models", server.first("/models").path());
        }
    }
}
