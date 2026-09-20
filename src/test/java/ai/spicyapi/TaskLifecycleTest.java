package ai.spicyapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Quoting, task creation, polling and refunds - the stretch where money moves. */
class TaskLifecycleTest {

    private static final ObjectMapper MAPPER = SpicyClient.newObjectMapper();

    private static final String QUOTE = """
            {"quoteId":"qt_signed_token","model":"publisher/model-2.0/text-to-image","estimatedCost":"0.000000045",
             "maxCharge":"0.09","currency":"USD","quantity":"5","unit":"per_second",
             "expiresAt":"2036-09-20T08:35:00Z"}""";

    private static final String ACCEPTED = """
            {"taskId":"task_123","state":"queued","estimatedCost":"0.09",
             "deadlineAt":"2026-09-20T09:00:00Z"}""";

    private static CreateTaskRequest request() {
        return CreateTaskRequest.of("publisher/model-2.0/text-to-image", Map.of("prompt", "a paper-cut city"));
    }

    private static void serveUnavailable(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Retry-After", "0");
        StubServer.respond(exchange, 503,
                "{\"code\":50301,\"msg\":\"no usable deployment\",\"request_id\":\"req_503\"}");
    }

    @Test
    @DisplayName("the quote's amount survives as an exact decimal, and its expiry is read from the wire")
    void quote() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/jobs/quote", QUOTE)) {
            TaskQuote quote = server.client().quote(request());

            assertEquals(new BigDecimal("0.000000045"), quote.estimatedCost());
            assertEquals(new BigDecimal("0.09"), quote.maxCharge());
            assertFalse(quote.expired(Instant.now()));
            assertTrue(quote.expired(Instant.parse("2037-01-01T00:00:00Z")));
        }
    }

    @Test
    @DisplayName("expectedCost is sent as a plain decimal string, not 4.5E-8")
    void expectedCostSerialization() throws Exception {
        try (StubServer server = new StubServer()
                .onData("/api/v1/jobs/quote", QUOTE)
                .onData("/api/v1/jobs/createTask", ACCEPTED)) {
            SpicyClient client = server.client();
            TaskQuote quote = client.quote(request());
            client.createTask(request().withQuote(quote), "idem-key-1");

            JsonNode sent = MAPPER.readTree(server.last("createTask").body());
            assertEquals("qt_signed_token", sent.get("quoteId").asText());
            assertTrue(sent.get("expectedCost").isTextual(), "amounts travel as strings, never as JSON numbers");
            assertEquals("0.000000045", sent.get("expectedCost").asText());
        }
    }

    @Test
    @DisplayName("a null callBackUrl is absent from the body rather than present and null")
    void nullCallbackOmitted() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/jobs/createTask", ACCEPTED)) {
            server.client().createTask(request(), "idem-key-1");

            JsonNode sent = MAPPER.readTree(server.last("createTask").body());
            assertFalse(sent.has("callBackUrl"));
            assertFalse(sent.has("quoteId"));
            assertTrue(sent.has("model"));
            assertTrue(sent.has("input"));
        }
    }

    @Test
    @DisplayName("a callBackUrl, once set, is sent")
    void callbackSent() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/jobs/createTask", ACCEPTED)) {
            server.client().createTask(request().withCallBackUrl("https://hooks.example.test/spicy"), "k");

            JsonNode sent = MAPPER.readTree(server.last("createTask").body());
            assertEquals("https://hooks.example.test/spicy", sent.get("callBackUrl").asText());
        }
    }

    @Test
    @DisplayName("50301 with an idempotency key is retried once; the key rides along")
    void retriesWithAnIdempotencyKey() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (StubServer server = new StubServer().on("/api/v1/jobs/createTask", (request, exchange) -> {
            if (attempts.incrementAndGet() == 1) {
                serveUnavailable(exchange);
            } else {
                StubServer.respond(exchange, 202, StubServer.envelope(ACCEPTED));
            }
        })) {
            AcceptedTask accepted = server.client().createTask(request(), "idem-key-1");

            assertEquals(2, attempts.get(), "exactly one retry, not zero and not three");
            assertEquals(TaskState.QUEUED, accepted.state());
            assertEquals("task_123", accepted.taskId());
            assertEquals("idem-key-1", server.last("createTask").header("Idempotency-Key"));
        }
    }

    @Test
    @DisplayName("without an idempotency key the same failure is sent exactly once")
    void neverRetriesWithoutAKey() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (StubServer server = new StubServer().on("/api/v1/jobs/createTask", (request, exchange) -> {
            attempts.incrementAndGet();
            serveUnavailable(exchange);
        })) {
            SpicyApiException failure = assertThrows(SpicyApiException.class,
                    () -> server.client().createTask(request(), null));

            assertEquals(1, attempts.get(),
                    "a resend without a key can turn one lost response into a second paid generation");
            assertFalse(server.last("createTask").hasHeader("Idempotency-Key"));
            assertEquals(50301, failure.businessCode());
            assertEquals(503, failure.httpStatus());
            assertEquals("req_503", failure.requestId());
            assertTrue(failure.retryAfter().isPresent());
            assertTrue(failure.transientFailure());
            assertTrue(failure.remediation().contains("usable deployment"));
            assertTrue(failure.describe().contains("HTTP 503"));
            assertTrue(failure.describe().contains("code 50301"));
            assertTrue(failure.describe().contains("req_503"));
        }
    }

    @Test
    @DisplayName("503 carries three different business codes, and each gets its own advice")
    void businessCodesAreNotTheHttpStatus() {
        SpicyApiException dependency = new SpicyApiException("x", 503, 503, "r", null);
        SpicyApiException noDeployment = new SpicyApiException("x", 503, 50301, "r", null);
        SpicyApiException refunded = new SpicyApiException("x", 503, 50302, "r", null);

        assertTrue(dependency.remediation().contains("Retry-After"));
        assertTrue(noDeployment.remediation().contains("another model"));
        assertTrue(refunded.remediation().contains("already refunded"));
        assertEquals(503, dependency.httpStatus());
        assertEquals(503, noDeployment.httpStatus());
        assertEquals(503, refunded.httpStatus());

        // All three codes share HTTP 503, but the remedy differs - a distinction that used to live
        // only in prose: the three assertions above pass against any wording, which is how 50302
        // went on being treated as retryable for so long.
        assertTrue(dependency.transientFailure(), "a bare 503 is transient");
        assertTrue(noDeployment.transientFailure(), "50301 may resolve itself");
        assertFalse(refunded.transientFailure(),
                "retrying 50302 requires a fresh idempotency key - reusing the original only replays "
                        + "the recorded failure");
        assertTrue(refunded.remediation().contains("NEW Idempotency-Key"),
                "the remedy must spell out the new key, or readers will resend it the way they would "
                        + "resend a 503");
    }

    @Test
    @DisplayName("a refunded 50302 is not resent under the same idempotency key")
    void refundedFailureIsNotResentUnderTheSameKey() throws Exception {
        // Pins the unit assertion above end to end: 50302 rides on a 503, and a transport layer
        // that looks only at the status will retry it. Retrying does not charge twice, but all four
        // attempts are doomed, and the final error reads as "we retried and it still failed",
        // burying the actual remedy. 50301 is the control: that one should be retried.
        for (int[] probe : new int[][] {{50302, 1}, {50301, 3}}) {
            int code = probe[0];
            int expected = probe[1];
            AtomicInteger hits = new AtomicInteger();
            try (StubServer server = new StubServer().on("/api/v1/jobs/recordInfo", (request, exchange) -> {
                hits.incrementAndGet();
                StubServer.respond(exchange, 503,
                        "{\"code\":" + code + ",\"msg\":\"upstream\",\"data\":null,\"request_id\":\"r\"}");
            })) {
                SpicyClient client = server.clientBuilder().maxRetries(2).build();
                assertThrows(SpicyApiException.class, () -> client.getTask("task_123"));
                assertEquals(expected, hits.get(),
                        code == 50302
                                ? "a recorded failure was replayed verbatim"
                                : "a transient failure that can resolve itself should be retried");
            }
        }
    }

    @Test
    @DisplayName("a succeeded record whose asset is still pending does not end the wait")
    void pendingAssetKeepsPolling() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        try (StubServer server = new StubServer().on("/api/v1/jobs/recordInfo", (request, exchange) -> {
            int n = polls.incrementAndGet();
            String state = n == 1 ? "queued" : n == 2 ? "running" : "succeeded";
            String pending = n == 3 ? "true" : "false";
            String url = n == 3 ? "null" : "\"https://cdn.example.test/a.png?sig=x\"";
            StubServer.respond(exchange, 200, StubServer.envelope("""
                    {"taskId":"task_123","model":"publisher/model-2.0/text-to-image","state":"%s","cost":"0.09",
                     "settled":%s,"createdAt":"2026-09-20T08:30:00Z","deadlineAt":"2026-09-20T09:00:00Z",
                     "output":{"text":"a written answer","assets":[{"key":"out-0","url":%s,"mime":"image/png",
                     "pending":%s,"unavailable":false,"width":1024,"height":1024,"bytes":812345}]},
                     "retention":{"outputsExpireAt":"2026-10-04T08:30:00Z",
                     "promptsExpireAt":"2026-10-20T08:30:00Z","source":"platform"}}"""
                    .formatted(state, n >= 4 ? "true" : "false", url, pending)));
        })) {
            TaskRecord task = server.client().waitForTerminal("task_123");

            assertEquals(4, polls.get(), "poll 3 said succeeded with a pending asset; stopping there hands back nothing");
            assertEquals(TaskState.SUCCEEDED, task.state());
            assertTrue(task.terminal());
            assertTrue(task.succeeded());
            assertTrue(task.settled());
            assertEquals("a written answer", task.outputText().orElseThrow());
            assertEquals("https://cdn.example.test/a.png?sig=x", task.assets().get(0).url().orElseThrow());
            assertTrue(task.assets().get(0).readable());
            assertEquals("platform", task.retention().source());
            assertTrue(task.failure().isEmpty());
            assertEquals("/api/v1/jobs/recordInfo?taskId=task_123", server.first("recordInfo").path());
        }
    }

    @Test
    @DisplayName("a state this release does not know stops the poll loop instead of spinning to the timeout")
    void unknownStateStopsTheLoop() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/jobs/recordInfo", """
                {"taskId":"task_123","model":"m","state":"quarantined","cost":"0","settled":false,
                 "createdAt":"2026-09-20T08:30:00Z"}""")) {
            SpicyApiException failure = assertThrows(SpicyApiException.class,
                    () -> server.client().waitForTerminal("task_123", Duration.ofSeconds(5)));

            assertTrue(failure.getMessage().contains("does not recognise"));
        }
    }

    @Test
    @DisplayName("giving up on the wait names the task, so the work can be reconciled rather than repeated")
    void waitTimeoutCarriesTheTask() throws Exception {
        // The clock is injected rather than slept through: the first read returns the start instant
        // (which fixes the deadline) and every read after it returns an hour later, so the polling
        // loop is already expired on entry. Squeezing a few tens of milliseconds out of a real clock
        // would occasionally land on a different path - the last sub-millisecond of budget gets
        // truncated to 0 by toMillis(), that lap's request timeout is sub-millisecond, and what
        // comes back is a SpicyTimeoutException. That is genuine client behaviour and is not being
        // papered over here; it simply is not what this test measures.
        AtomicInteger reads = new AtomicInteger();
        Instant start = Instant.parse("2026-09-20T08:30:00Z");
        java.time.Clock stepping = new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return reads.getAndIncrement() == 0 ? start : start.plus(Duration.ofHours(1));
            }
        };

        try (StubServer server = new StubServer().onData("/api/v1/jobs/recordInfo", """
                {"taskId":"task_123","model":"m","state":"running","cost":"0.09","settled":false,
                 "createdAt":"2026-09-20T08:30:00Z"}""")) {
            SpicyClient client = server.clientBuilder().clock(stepping).build();
            SpicyWaitTimeoutException timeout = assertThrows(SpicyWaitTimeoutException.class,
                    () -> client.waitForTerminal("task_123", Duration.ofMinutes(1)));

            assertEquals("task_123", timeout.taskId());
            assertEquals(Duration.ofMinutes(1), timeout.timeout());
            assertTrue(timeout.lastObserved().isEmpty(), "the budget was gone before the first poll");
            assertTrue(timeout.getMessage().contains("task_123"));
            assertEquals(0, server.recorded().size());
        }
    }

    @Test
    @DisplayName("a sub-second budget stops early instead of losing the task id")
    void subSecondBudgetStillNamesTheTask() throws Exception {
        // When the budget is nearly spent, do NOT issue another request.
        //
        // If one goes out, that lap's timeout is min(remaining, requestTimeout) - a near-zero value,
        // and a Duration under one millisecond is truncated to 0 by toMillis(). That request is all
        // but guaranteed to time out, and what it throws is SpicyTimeoutException, which carries no
        // taskId. The caller loses the task id at the exact moment it matters most, while the task
        // keeps running and keeps being billed.
        //
        // What this test measures is the REQUEST COUNT: after stopping early there should be exactly
        // one poll. The count is observable, whereas "which exception was thrown" cannot be tested
        // when the stub server never actually times out.
        AtomicInteger reads = new AtomicInteger();
        Instant start = Instant.parse("2026-09-20T08:30:00Z");
        java.time.Clock stepping = new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                // The first three reads (computing the deadline, the while condition, and the
                // first lap's remaining) return the start instant so the first lap goes out
                // normally; after that it jumps to 400 milliseconds left, forcing the early stop.
                return reads.getAndIncrement() <= 2 ? start : start.plusMillis(59_600);
            }
        };

        try (StubServer server = new StubServer().onData("/api/v1/jobs/recordInfo", """
                {"taskId":"task_123","model":"m","state":"running","cost":"0.09","settled":false,
                 "createdAt":"2026-09-20T08:30:00Z"}""")) {
            SpicyClient client = server.clientBuilder().clock(stepping).build();
            SpicyWaitTimeoutException timeout = assertThrows(SpicyWaitTimeoutException.class,
                    () -> client.waitForTerminal("task_123", Duration.ofMinutes(1)));

            assertEquals("task_123", timeout.taskId(), "the wait timeout must carry the task id");
            assertEquals(1, server.recorded().size(),
                    "a sub-second budget must not buy one more doomed request");
        }
    }

    @Test
    @DisplayName("a polling request that times out does not end the wait")
    void aPollingTimeoutDoesNotEndTheWait() throws Exception {
        // This pins the shape the test above cannot catch.
        //
        // That one covers "not enough budget for a round trip", which the MIN_POLL_BUDGET early stop
        // handles. What it does not cover is this: ten minutes of budget remain, and this single
        // attempt still stalls for the full requestTimeout. Before the fix it would
        //   - give up after a single poll, voiding ten minutes of budget over one piece of network
        //     turbulence, and
        //   - throw the SpicyTimeoutException from callForData, which carries no taskId.
        // The task is still running and still being billed at that point, and the caller holds
        // nothing that could lead them back to it.
        //
        // The measure is "did the later successful poll come through", which is more direct than
        // "does the exception carry a taskId": a wait is supposed to survive one timed-out poll.
        // Python had the identical hole and was fixed the same day.
        AtomicInteger polls = new AtomicInteger();
        try (StubServer server = new StubServer().on("/api/v1/jobs/recordInfo", (request, exchange) -> {
            if (polls.incrementAndGet() == 1) {
                // Stall the first attempt to force a genuine local timeout (the client gives up
                // after 150 milliseconds).
                try {
                    Thread.sleep(600L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            StubServer.respond(exchange, 200, StubServer.envelope("""
                    {"taskId":"task_123","model":"m","state":"succeeded","cost":"0.09","settled":true,
                     "createdAt":"2026-09-20T08:30:00Z"}"""));
        })) {
            SpicyClient client = server.clientBuilder()
                    .requestTimeout(Duration.ofMillis(150))
                    .maxRetries(0)
                    .build();

            TaskRecord finished = client.waitForTerminal("task_123", Duration.ofMinutes(10));

            assertEquals(TaskState.SUCCEEDED, finished.state(),
                    "a wait must survive one timed-out poll and read the next one");
            assertTrue(polls.get() >= 2,
                    "one timed-out poll ended the whole wait - while budget remains it must keep polling");
        }
    }

    @Test
    @DisplayName("the give-up exception hands back the last record it saw")
    void waitTimeoutKeepsTheLastRecord() {
        TaskRecord last = new TaskRecord("task_123", null, "m", TaskState.RUNNING, null, null, null, null,
                new BigDecimal("0.09"), false, Instant.parse("2026-09-20T08:30:00Z"), null, null,
                null, null, null, null);
        SpicyWaitTimeoutException timeout =
                new SpicyWaitTimeoutException("task_123", Duration.ofMinutes(10), last);

        assertEquals(TaskState.RUNNING, timeout.lastObserved().orElseThrow().state());
        assertEquals("task_123", timeout.taskId());
    }

    @Test
    @DisplayName("purge sends no idempotency header, because the task id is already the key")
    void purge() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/jobs/purge", """
                {"taskId":"task_123","contentState":"purged","purgedAt":"2026-09-20T10:00:00Z",
                 "contentRemovedBy":"user","billingRetained":true,"mediaDeletionPending":true}""")) {
            PurgeResult purged = server.client().purgeTask("task_123");

            assertTrue(purged.purged());
            assertTrue(purged.billingRetained(), "destroying content never touches the ledger");
            assertTrue(purged.mediaDeletionPending());
            assertFalse(server.first("purge").hasHeader("Idempotency-Key"));
            assertEquals("POST", server.first("purge").method());
        }
    }

    @Test
    @DisplayName("retryTask names its source task")
    void retryTask() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/jobs/retry", """
                {"taskId":"task_456","state":"queued","estimatedCost":"0.09","sourceTaskId":"task_123"}""")) {
            AcceptedTask retried = server.client().retryTask("task_123", "idem-retry-1");

            assertEquals("task_123", retried.sourceTask().orElseThrow());
            assertEquals("task_456", retried.taskId());
            assertEquals("idem-retry-1", server.first("retry").header("Idempotency-Key"));
        }
    }

    @Test
    @DisplayName("an envelope that is not the documented shape is a protocol failure, not an API failure")
    void nonEnvelopeResponse() throws Exception {
        try (StubServer server = new StubServer().on("/api/v1/jobs/quote",
                (request, exchange) -> StubServer.respond(exchange, 200, "<html>captive portal</html>"))) {
            assertThrows(SpicyProtocolException.class, () -> server.client().quote(request()));
        }
    }

    @Test
    @DisplayName("quote asks for a price and therefore never carries the previous one")
    void quoteDoesNotCarryTheOldQuote() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/jobs/quote", QUOTE)) {
            SpicyClient client = server.client();
            // The most natural thing to write on the caller's side: take a quote, bind it with
            // withQuote(...), have createTask fail (40901 or otherwise), then re-quote from the SAME
            // request variable - which by now is already carrying the previous quote.
            CreateTaskRequest bound = request().withQuote(client.quote(request()))
                    .withCallBackUrl("https://hooks.example.test/spicy");
            client.quote(bound);

            JsonNode sent = MAPPER.readTree(server.last("quote").body());
            assertFalse(sent.has("quoteId"),
                    "this would ask for a quote while carrying an old price, and a quote exists "
                            + "precisely to discover that the price moved");
            assertFalse(sent.has("expectedCost"));
            assertEquals("publisher/model-2.0/text-to-image", sent.get("model").asText());
            assertTrue(sent.has("input"));
            assertEquals("https://hooks.example.test/spicy", sent.get("callBackUrl").asText(),
                    "the callback is validated at admission, so it is part of what gets priced");
        }
    }

    @Test
    @DisplayName("retention rides along as X-Spicy-Retention, in whole seconds, with no local ceiling")
    void retentionHeader() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/jobs/createTask", ACCEPTED)) {
            SpicyClient client = server.client();

            client.createTask(request(), "k1", Duration.ofHours(2));
            assertEquals("7200", server.last("createTask").header("X-Spicy-Retention"));

            client.createTask(request(), "k2", Duration.ZERO);
            assertEquals("0", server.last("createTask").header("X-Spicy-Retention"),
                    "0 is a meaningful value - destroy the output as soon as it reaches a terminal "
                            + "state - and not the absence of a setting");

            client.createTask(request(), "k3");
            assertFalse(server.last("createTask").hasHeader("X-Spicy-Retention"),
                    "omitting it defers to the account setting instead of letting the client invent "
                            + "a default");

            // The ceiling is deliberately not checked locally: only the server knows the platform
            // limit, and copying it into the client buries a constant that will expire and will
            // eventually reject, on the server's behalf, a value the server would have accepted.
            // Anything over the limit is clamped server-side, and the value that actually took
            // effect is read back from TaskRecord.retention.
            client.createTask(request(), "k4", Duration.ofDays(3650));
            assertEquals("315360000", server.last("createTask").header("X-Spicy-Retention"));

            assertThrows(IllegalArgumentException.class,
                    () -> client.createTask(request(), "k5", Duration.ofSeconds(-1)));
            assertThrows(IllegalArgumentException.class,
                    () -> client.createTask(request(), "k6", Duration.ofMillis(1500)),
                    "1500 milliseconds does not mean 1 second; truncating would be a silent "
                            + "shortening, and this header governs exactly when content is destroyed");
        }
    }

    @Test
    @DisplayName("a retention header does not turn a keyless submission into a retryable one")
    void retentionDoesNotEnableRetries() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (StubServer server = new StubServer().on("/api/v1/jobs/createTask", (request, exchange) -> {
            attempts.incrementAndGet();
            serveUnavailable(exchange);
        })) {
            assertThrows(SpicyApiException.class,
                    () -> server.client().createTask(request(), null, Duration.ofHours(1)));

            assertEquals(1, attempts.get(),
                    "shortening retention does not make a resend safe - whether it may be resent "
                            + "depends solely on the idempotency key");
            assertEquals("3600", server.last("createTask").header("X-Spicy-Retention"));
        }
    }

    @Test
    @DisplayName("run spends one budget on both legs, not one budget on each")
    void runSubtractsTheSubmissionFromTheWaitBudget() throws Exception {
        // The clock only advances while the stub server handles a request, so every elapsed span is
        // exact and assertable. Task creation consumes 55 seconds, which should leave polling with
        // five - not the full sixty.
        Instant start = Instant.parse("2026-09-20T08:30:00Z");
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(start);
        java.time.Clock advancing = new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };

        AtomicInteger polls = new AtomicInteger();
        try (StubServer server = new StubServer()
                .on("/api/v1/jobs/createTask", (request, exchange) -> {
                    now.set(start.plusSeconds(55));
                    StubServer.respond(exchange, 202, StubServer.envelope(ACCEPTED));
                })
                .on("/api/v1/jobs/recordInfo", (request, exchange) -> {
                    polls.incrementAndGet();
                    now.set(now.get().plusSeconds(2));
                    StubServer.respond(exchange, 200, StubServer.envelope("""
                            {"taskId":"task_123","model":"m","state":"running","cost":"0.09","settled":false,
                             "createdAt":"2026-09-20T08:30:00Z"}"""));
                })) {
            SpicyClient client = server.clientBuilder()
                    .clock(advancing)
                    .waitTimeout(Duration.ofSeconds(60))
                    .build();

            SpicyWaitTimeoutException timeout =
                    assertThrows(SpicyWaitTimeoutException.class, () -> client.run(request(), "idem-run-1"));

            assertEquals(Duration.ofSeconds(5), timeout.timeout(),
                    "run's ceiling is waitTimeout itself, not submission time plus waitTimeout - "
                            + "time spent creating the task must come out of the wait budget");
            assertEquals(3, polls.get(),
                    "a five-second budget at two seconds per lap affords exactly three polls");
            assertEquals("task_123", timeout.taskId());
            assertTrue(timeout.lastObserved().isPresent());
        }
    }

    @Test
    @DisplayName("a budget already gone at acceptance still names the task that is now running")
    void runOutOfBudgetAtAcceptanceStillNamesTheTask() throws Exception {
        // The most dangerous cell of all: the task exists, the funds are held, and the budget runs
        // out at exactly that moment. Throwing a timeout without a taskId here strands a charge that
        // is actively accruing.
        Instant start = Instant.parse("2026-09-20T08:30:00Z");
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(start);
        java.time.Clock advancing = new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };

        try (StubServer server = new StubServer()
                .on("/api/v1/jobs/createTask", (request, exchange) -> {
                    now.set(start.plusSeconds(61));
                    StubServer.respond(exchange, 202, StubServer.envelope(ACCEPTED));
                })
                .on("/api/v1/jobs/recordInfo", (request, exchange) -> {
                    // This call should never happen. It still advances the clock so that the
                    // counter-proof fails cleanly: with a frozen clock, reverting to the old
                    // implementation (waiting out the full waitTimeout) would leave the polling
                    // loop's deadline permanently out of reach, and the test would hang rather than
                    // fail - a test that hangs proves nothing, it just drags CI to its timeout.
                    now.set(now.get().plusSeconds(2));
                    StubServer.respond(exchange, 200, StubServer.envelope("""
                            {"taskId":"task_123","model":"m","state":"running","cost":"0.09","settled":false,
                             "createdAt":"2026-09-20T08:30:00Z"}"""));
                })) {
            SpicyClient client = server.clientBuilder()
                    .clock(advancing)
                    .waitTimeout(Duration.ofSeconds(60))
                    .build();

            SpicyWaitTimeoutException timeout =
                    assertThrows(SpicyWaitTimeoutException.class, () -> client.run(request(), "idem-run-2"));

            assertEquals("task_123", timeout.taskId(),
                    "the task is running and billing; its id is the only thing that leads back to it");
            assertEquals(Duration.ofSeconds(60), timeout.timeout(),
                    "what is reported is the budget the caller set");
            assertEquals(0, server.count("recordInfo"),
                    "once the budget is gone there must be no further poll doomed to time out");
        }
    }

    @Test
    @DisplayName("an idempotency key longer than the contract allows is refused before the call")
    void idempotencyKeyBounds() throws Exception {
        try (StubServer server = new StubServer().onData("/api/v1/jobs/createTask", ACCEPTED)) {
            SpicyClient client = server.client();
            assertThrows(IllegalArgumentException.class, () -> client.createTask(request(), "k".repeat(129)));
            assertThrows(IllegalArgumentException.class, () -> client.createTask(request(), "bad\r\nkey"));
            assertEquals(0, server.count("createTask"), "neither one reached the network");
        }
    }
}
