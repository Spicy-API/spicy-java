package ai.spicyapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three read-only endpoints: balance, usage and task history.
 *
 * <p>They do not move money, but they are <em>about</em> money, and the classic mistake with this
 * kind of endpoint is to read a failed response as if it were a number - an HTTP 200 envelope with
 * a business code that means rejection, and a perfectly plausible-looking balance sitting inside
 * {@code data}. Treated as a success, the caller reads a balance that came from nowhere instead of
 * an error.
 */
class AccountAndHistoryTest {

    private static final String BALANCE = """
            {"available":"12.3456","held":"0.9","total":"13.2456",
             "funding":{"balanceUsd":"12.3456","heldUsd":"0.9","prepaidAvailableUsd":"2.3456",
              "grantAvailableUsd":"10.00","cashShortfallUsd":"0",
              "credit":{"enabled":true,"limitUsd":"50","availableUsd":"50","usedUsd":"0","heldUsd":"0",
               "expiresAt":null,"status":"active","version":7},
              "grants":[
               {"id":"gr_open","name":"launch","amountUsd":"10","availableUsd":"10","heldUsd":"0",
                "spentUsd":"0","status":"active","startsAt":"2026-09-01T00:00:00Z","expiresAt":null,
                "modelSlugs":[],"customerMemo":"welcome","createdAt":"2026-09-01T00:00:00Z"},
               {"id":"gr_fenced","name":"video trial","amountUsd":"5","availableUsd":"5","heldUsd":"0",
                "spentUsd":"0","status":"active","startsAt":"2026-09-01T00:00:00Z",
                "expiresAt":"2026-12-01T00:00:00Z","modelSlugs":["publisher/model-2.0/text-to-video"],
                "customerMemo":"","createdAt":"2026-09-01T00:00:00Z"}],
              "grantsHasMore":false},
             "someFieldAddedNextYear":true}""";

    private static final String USAGE = """
            {"from":"2026-09-13","to":"2026-09-20","currency":"USD","totalCalls":3,"totalSpend":"0.27",
             "days":[{"day":"2026-09-14","calls":2,"succeeded":1,"failed":1,"spend":"0.18"},
                     {"day":"2026-09-19","calls":1,"succeeded":1,"failed":0,"spend":"0.09"}],
             "models":[{"model":"publisher/model-2.0/text-to-image","calls":3,"succeeded":2,"failed":1,
                        "spend":"0.27"}]}""";

    private static final String PAGE_ONE = """
            {"items":[
              {"taskId":"task_1","model":"publisher/model-2.0/text-to-image","state":"succeeded","cost":"0.09",
               "settled":true,"createdAt":"2026-09-19T08:30:00Z","deadlineAt":"2026-09-19T09:00:00Z",
               "completedAt":"2026-09-19T08:31:12Z"},
              {"taskId":"task_2","model":"publisher/model-2.0/text-to-image","state":"running","cost":"0.09",
               "settled":false,"createdAt":"2026-09-19T08:32:00Z","deadlineAt":"2026-09-19T09:02:00Z"}],
             "hasMore":true,"nextCursor":"cur_page_two"}""";

    private static final String PAGE_TWO = """
            {"items":[
              {"taskId":"task_3","model":"publisher/model-2.0/text-to-image","state":"failed","cost":"0",
               "settled":true,"createdAt":"2026-09-19T08:35:00Z","deadlineAt":"2026-09-19T09:05:00Z",
               "completedAt":"2026-09-19T08:35:40Z"}],
             "hasMore":false}""";

    @Test
    @DisplayName("the balance separates wallet money from grants and approved credit")
    void balance() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/chat/credit", BALANCE)) {
            Balance balance = server.client().getBalance();

            assertEquals(new BigDecimal("12.3456"), balance.available());
            assertEquals(new BigDecimal("0.9"), balance.held());
            assertEquals(new BigDecimal("13.2456"), balance.total());
            assertEquals("/api/v1/chat/credit", server.first("/chat/credit").path());
            assertEquals("GET", server.first("/chat/credit").method());

            FundingOverview funding = balance.fundingSources().orElseThrow();
            assertEquals("2.3456", funding.prepaidAvailableUsd(),
                    "unrestricted money is the part that pays for anything, and it is not the headline figure");
            assertEquals("10.00", funding.grantAvailableUsd());
            assertFalse(funding.grantsHasMore());

            CreditFacility credit = funding.creditFacility().orElseThrow();
            assertTrue(credit.enabled());
            assertEquals("50", credit.limitUsd());
            assertEquals(7L, credit.version());
            assertTrue(credit.expiry().isEmpty(), "a null expiry is 'never', not an unreadable date");

            assertEquals(2, funding.grants().size());
            assertTrue(funding.grants().get(0).appliesToEveryModel(),
                    "an empty model list means every model; reading it as 'none' hides usable money");
            assertFalse(funding.grants().get(1).appliesToEveryModel());
            assertEquals("publisher/model-2.0/text-to-video", funding.grants().get(1).modelSlugs().get(0));
            assertEquals(Instant.parse("2026-12-01T00:00:00Z"), funding.grants().get(1).expiry().orElseThrow());
        }
    }

    @Test
    @DisplayName("usage comes back as dates and exact decimals, and its buckets are sparse")
    void usage() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/usage", USAGE)) {
            UsageReport report = server.client().getUsage(LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 20));

            assertEquals(LocalDate.of(2026, 9, 13), report.from());
            assertEquals(LocalDate.of(2026, 9, 20), report.to(), "the upper bound is exclusive");
            assertEquals("USD", report.currency());
            assertEquals(3L, report.totalCalls());
            assertEquals(new BigDecimal("0.27"), report.totalSpend());

            assertEquals(2, report.days().size(),
                    "a seven-day range with two active days returns two buckets, not seven");
            assertEquals(LocalDate.of(2026, 9, 14), report.days().get(0).day());
            assertEquals(new BigDecimal("0.18"), report.days().get(0).spend());
            assertEquals(1L, report.days().get(0).failed());
            assertEquals("publisher/model-2.0/text-to-image", report.models().get(0).model());
            assertEquals(new BigDecimal("0.27"), report.models().get(0).spend());

            assertEquals("/api/v1/usage?from=2026-09-13&to=2026-09-20", server.first("/usage").path());
        }
    }

    @Test
    @DisplayName("an unbounded usage query sends no query string, leaving the window to the service")
    void usageWithoutARange() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/usage", USAGE)) {
            SpicyClient client = server.client();
            client.getUsage();

            assertEquals("/api/v1/usage", server.first("/usage").path());
            assertThrows(IllegalArgumentException.class,
                    () -> client.getUsage(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 13)),
                    "a reversed range is a local mistake; spending a round trip on it teaches nothing");
        }
    }

    @Test
    @DisplayName("task history maps metadata only, and an in-flight row has no completion time")
    void listTasks() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/jobs", PAGE_ONE)) {
            TaskPage page = server.client().listTasks();

            assertEquals("/api/v1/jobs", server.first("/jobs").path(), "an unset filter adds no query string");
            assertEquals(List.of("task_1", "task_2"), page.taskIds());
            assertTrue(page.hasMore());
            assertEquals("cur_page_two", page.nextCursor());

            TaskSummary done = page.items().get(0);
            assertEquals(TaskState.SUCCEEDED, done.state());
            assertTrue(done.terminal());
            assertTrue(done.settled());
            assertEquals(new BigDecimal("0.09"), done.cost());
            assertEquals(Instant.parse("2026-09-19T08:31:12Z"), done.completion().orElseThrow());

            TaskSummary running = page.items().get(1);
            assertFalse(running.terminal());
            assertFalse(running.settled(), "an unsettled cost is the hold, which is a ceiling and not a bill");
            assertTrue(running.completion().isEmpty());
        }
    }

    @Test
    @DisplayName("every filter is URL-encoded onto the query string, and UNKNOWN is refused locally")
    void taskFilterEncoding() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/jobs", PAGE_TWO)) {
            server.client().listTasks(TaskFilter.all()
                    .withRange(LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 20))
                    .withState(TaskState.SUCCEEDED)
                    .withModel("publisher/model-2.0/text-to-image")
                    .withLimit(50));

            assertEquals("/api/v1/jobs?from=2026-09-13&to=2026-09-20&state=succeeded"
                            + "&model=publisher%2Fmodel-2.0%2Ftext-to-image&limit=50",
                    server.first("/jobs").path());

            // UNKNOWN has an empty wire value. Letting it through would send state=, the server
            // would reject it as an empty parameter, and the resulting 400 would bear no visible
            // relation to anything the caller wrote.
            assertThrows(IllegalArgumentException.class, () -> TaskFilter.all().withState(TaskState.UNKNOWN));
            assertThrows(IllegalArgumentException.class, () -> TaskFilter.all().withLimit(0));
            assertThrows(IllegalArgumentException.class,
                    () -> TaskFilter.all().withRange(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 13)));
        }
    }

    @Test
    @DisplayName("the page size ceiling is the service's to enforce, not a constant frozen into this client")
    void pageSizeUpperBoundIsNotCopiedLocally() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/jobs", PAGE_TWO)) {
            // The contract's upper bound today is 100, and it is deliberately not enforced
            // locally: copying a limit into the client buries a constant that will expire - the day
            // the server relaxes to 200, this would still reject 150 on its behalf, and silently.
            // The cost of not checking is one round trip and an ordinary 400, far cheaper than a
            // stale local ceiling. Same stance as not checking the X-Spicy-Retention limit.
            server.client().listTasks(TaskFilter.all().withLimit(500));

            assertEquals("/api/v1/jobs?limit=500", server.first("/jobs").path(),
                    "an out-of-range page size must reach the service, which owns that number");
        }
    }

    @Test
    @DisplayName("paging moves the cursor and nothing else")
    void pagination() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (StubServer server = new StubServer().on("/api/v1/jobs", (request, exchange) ->
                StubServer.respond(exchange, 200,
                        StubServer.envelope(calls.incrementAndGet() == 1 ? PAGE_ONE : PAGE_TWO)))) {
            SpicyClient client = server.client();
            TaskFilter filter = TaskFilter.all()
                    .withRange(LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 20))
                    .withLimit(2);

            TaskPage first = client.listTasks(filter);
            TaskPage second = client.listTasks(filter.withCursor(first.nextCursor()));

            assertEquals(2, calls.get());
            assertEquals(List.of("task_3"), second.taskIds());
            assertFalse(second.hasMore());
            assertNull(second.nextCursor(), "the last page carries no cursor");

            // Every filter other than the cursor must be repeated verbatim. The contract says to
            // keep filters stable while paging, and the reason is concrete: a cursor only means
            // anything to the query that issued it, and with from/to left empty the server
            // recomputes its default window for every page (to being tomorrow in UTC) - so a walk
            // that crosses midnight asks two different questions and staples the two answers
            // together. Nothing reports that.
            assertEquals("/api/v1/jobs?from=2026-09-13&to=2026-09-20&limit=2", server.first("/jobs").path());
            assertEquals("/api/v1/jobs?from=2026-09-13&to=2026-09-20&limit=2&cursor=cur_page_two",
                    server.last("/jobs").path());
        }
    }

    @Test
    @DisplayName("a business code inside an HTTP 200 envelope is a failure, not a reading")
    void businessCodeIsNotSuccess() throws Exception {
        // All three endpoints can answer with an HTTP 200 envelope whose business code means
        // rejection, and with a perfectly plausible-looking number sitting in data. Read as a
        // success, the caller receives a balance from nowhere, a usage report from nowhere, rather
        // than an error - the error disappears without a sound.
        String[][] endpoints = {
            {"/api/v1/chat/credit", "{\"available\":\"999.00\",\"held\":\"0\",\"total\":\"999.00\"}"},
            {"/api/v1/usage", "{\"from\":\"2026-09-13\",\"to\":\"2026-09-20\",\"currency\":\"USD\","
                    + "\"totalCalls\":0,\"totalSpend\":\"0\",\"days\":[],\"models\":[]}"},
            {"/api/v1/jobs", "{\"items\":[],\"hasMore\":false}"},
        };

        for (String[] endpoint : endpoints) {
            AtomicInteger hits = new AtomicInteger();
            try (StubServer server = new StubServer().on(endpoint[0], (request, exchange) -> {
                hits.incrementAndGet();
                StubServer.respond(exchange, 200, "{\"code\":40301,\"msg\":\"this key may not call that model\","
                        + "\"data\":" + endpoint[1] + ",\"request_id\":\"req_403\"}");
            })) {
                SpicyClient client = server.client();
                SpicyApiException failure = assertThrows(SpicyApiException.class, () -> {
                    switch (endpoint[0]) {
                        case "/api/v1/chat/credit" -> client.getBalance();
                        case "/api/v1/usage" -> client.getUsage();
                        default -> client.listTasks();
                    }
                });

                assertEquals(40301, failure.businessCode(), endpoint[0] + " swallowed the business code");
                assertEquals(200, failure.httpStatus(), "the transport said fine; the platform did not");
                assertEquals("req_403", failure.requestId());
                assertFalse(failure.transientFailure());
                assertEquals(1, hits.get(), "a refusal this definite must not be resent");
            }
        }
    }
}
