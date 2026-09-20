<div align="center">

# spicyapi-java

**Official Java SDK for [SpicyAPI](https://spicyapi.ai)** — image, video and text models behind one API.

[Get a key](https://spicyapi.ai) · [Models](https://spicyapi.ai/models) · [Docs](https://docs.spicyapi.ai) · [Status](https://status.spicyapi.ai)

</div>

---

One endpoint in front of 83 model families across 121 callable endpoints, billed in USD per request
rather than in credits. Media generation is asynchronous and quotable before you spend; text models
speak the OpenAI, Anthropic and Gemini wire formats.

```xml
<dependency>
  <groupId>ai.spicyapi</groupId>
  <artifactId>spicyapi-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

Requires **Java 17+**. One dependency: **Jackson** (`com.fasterxml.jackson.core:jackson-databind`),
**2.12 or newer** — that is the release where records became mappable, and the types here are
records. Jackson is declared as a plain compile dependency, not `optional` and not shaded, so
whichever version your project already has wins.

On the module path the client is `ai.spicyapi`, pinned in the manifest rather than derived from the
JAR file name.

## Generate something

```java
SpicyClient client = SpicyClient.builder().apiKeyFromEnvironment().build();  // SPICY_API_KEY

CreateTaskRequest request = CreateTaskRequest.of(
        "MODEL_ID_FROM_CATALOG",                      // copy a real id from listModels()
        Map.of("prompt", "a lantern in fog"));

TaskQuote quote = client.quote(request);              // free; reserves nothing
System.out.println("this will cost at most " + quote.maxCharge());

TaskRecord task = client.run(                         // createTask + waitForTerminal
        request.withQuote(quote), UUID.randomUUID().toString());

task.outputText().ifPresent(System.out::println);     // some endpoints answer in text, not files
for (TaskAsset asset : task.assets()) {
    asset.url().ifPresent(System.out::println);
}
```

Build the input from that model's own `inputSchema` — every model has different fields, and
`listModels(ModelFilter.all().withIncludeSchema(true))` returns them.

**Check `outputText()` before walking `assets()`.** Transcription and similar endpoints put the
whole result in the text field and return no files at all; a client that only looks for files
reports a perfectly successful task as having produced nothing.

## Start from a local file

Image-to-video, face swap and image editing need your material on our side first. Upload returns a
`spicy://` URI; that is what goes into the input. A publicly reachable HTTPS URL can go in directly
and needs no upload at all.

```java
UploadedFile uploaded = client.uploadFile(Path.of("/path/to/reference.png"));
// uploaded.uri() -> "spicy://f/fil_..."

TaskRecord task = client.run(
        CreateTaskRequest.of("MODEL_ID_FROM_CATALOG",
                Map.of("image", uploaded.uri(), "prompt", "slow dolly in")),
        UUID.randomUUID().toString());
```

`uploadFile` is the three steps in one call. They are also public, for bytes that are streamed from
somewhere else:

```java
UploadTicket ticket = client.createUploadUrl(UploadContentType.IMAGE_PNG, data.length);
client.putUploadBytes(ticket, data);                  // goes to object storage, not to the API
UploadedFile file = client.commitUploadedFile(ticket.fileId());
```

Two things about the middle step fail opaquely if you reimplement it. The ticket's headers are part
of what the presigned URL signed, so they are forwarded **unchanged** — drop one, rename one or
helpfully add one and storage answers 403. And no SpicyAPI key is attached: the URL is itself the
grant, and it points at storage rather than at us.

Images are accepted to 10 MiB, audio and video to 90 MiB. The client refuses an oversized file
locally, before the ticket is requested.

## Keep the result for less time

Outputs and stored request text are kept for whatever the account is configured for. One task can
ask for less:

```java
TaskRecord task = client.run(request, idempotencyKey, Duration.ofHours(1));
task.retention().outputsExpireAt();                   // what was actually applied
```

It only ever shortens. The service keeps the smallest of your value, the account setting and the
platform maximum, so a request can never buy back a longer window than the account allows.
`Duration.ZERO` is a real value: the outputs go as soon as the task reaches a terminal state.

**The platform's ceiling is not checked here, on purpose.** It is the service's number, and a copy
of it in this client would be a constant that goes stale in silence — it would start refusing a
window the platform had already begun accepting. An oversized value is clamped rather than
rejected, which is why `retention()` on the record, not the number you sent, is what was applied.

`retryTask` takes no retention of its own: a retry inherits its source task's, so one task family
never ends up with two different expiry times.

## Balance, usage and history

Three read-only calls, all free:

```java
Balance balance = client.getBalance();                          // account-wide
UsageReport usage = client.getUsage(                            // this key only
        LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 20));  // [from, to)
TaskPage page = client.listTasks(TaskFilter.all().withState(TaskState.FAILED));
```

**A balance is a budget indicator, not an admission decision.** Part of it can be grant money fenced
to particular models, and part can be approved credit rather than cash — `balance.fundingSources()`
is the breakdown, and an empty `modelSlugs` on a grant means *every* model, not none. The exact
answer for one exact request is `quote()`, which runs the same admission rules and reserves nothing.

**Usage is for reconciliation, not for progress.** It counts settled charges only, so a period
still running always reads low; late settlement can change a past day; and it carries its own
account-wide rate limit whose limiter fails closed, so polling it can lock every key on the account
out of its own reporting. Follow one task with `waitForTerminal` instead.

`listTasks` is scoped to the calling key — sibling keys and console generations are not in it — and
returns metadata only. Walk the pages by moving the cursor and changing nothing else:

```java
TaskFilter filter = TaskFilter.all().withRange(from, to);   // pin the dates before walking
TaskPage page = client.listTasks(filter);
while (true) {
    page.items().forEach(...);
    if (!page.hasMore() || page.nextCursor() == null) break;
    page = client.listTasks(filter.withCursor(page.nextCursor()));
}
```

Pinning `from` and `to` matters for any walk that can cross midnight UTC. Left unset, the service
recomputes its default window — `to` is tomorrow in UTC — for every request, so a walk that starts
at 23:59 and continues at 00:01 asks two different questions and splices the answers together.
Nothing reports that: the pages arrive, the loop ends, and the result is quietly wrong.

## Callbacks

Set `callBackUrl` on the request and the terminal record is delivered to you. Verify it before you
believe it:

```java
CallbackSignatureVerifier verifier =
        new CallbackSignatureVerifier(System.getenv("SPICY_WEBHOOK_SECRET"));

byte[] body = request.getInputStream().readAllBytes();   // the raw bytes, never re-serialized
WebhookDelivery delivery = verifier.verify(body, request::getHeader);
delivery.task().ifPresent(record -> { /* the same record getTask returns */ });
```

Three ways this goes wrong, all of which the class avoids: re-serializing the body before verifying
(`readValue` then `writeValueAsString` changes the bytes, and the digest never matches again),
comparing signatures with `equals` (it returns on the first differing byte, which is a measurable
timing difference), and skipping the freshness check (a valid signature stays valid forever, so a
captured delivery can be replayed).

Deliveries are retried. Deduplicate on `delivery.deliveryId()`.

The class is named for the scheme it implements: the `callBackUrl` signature, with its three
`X-Webhook-*` headers. It is not a general-purpose webhook verifier, and a delivery signed under
some other scheme is rejected here as a mismatch.

## Errors

Every failure is a `SpicyException`, which is sealed, so a `switch` over the taxonomy is exhaustive:

| Type | Means |
| --- | --- |
| `SpicyApiException` | the service answered, and the answer was no |
| `SpicyProtocolException` | something answered, but not with our envelope — a proxy, a captive portal, a wrong base URL |
| `SpicyTransportException` | no usable answer: DNS, TLS, a dropped connection |
| `SpicyTimeoutException` | a local request budget elapsed. Outcome unknown, not "did not happen" |
| `SpicyWaitTimeoutException` | polling gave up. The task is still running and still billable; the exception carries its id |
| `SpicyUploadException` | object storage refused the presigned PUT |
| `SpicyWebhookException` | an inbound delivery was not authentic, fresh and well-formed |

Branch on `businessCode()`, never on the message — messages are prose, they are translated, and
they are not part of the contract. **HTTP 503 is shared by three different business codes**, so the
status alone cannot tell you what to do:

| Code | Meaning | What to do |
| --- | --- | --- |
| `40003` | uploaded bytes do not match their ticket | start over from a fresh ticket; committing again cannot fix it |
| `40004` | no deployment serves that parameter combination | change the parameter named in the message |
| `40201` | insufficient balance | add funds |
| `40901` | the price moved before the task was created | quote again, keep the same idempotency key |
| `503` | a dependency is briefly unavailable | back off by `Retry-After` |
| `50301` | no usable deployment or price right now | back off, or pick another model |
| `50302` | a synchronous generation failed upstream and was refunded | retry under a **new** idempotency key; the original one replays the failure |

`remediation()` returns the same advice at runtime, and `describe()` puts status, code, request id,
message and advice on one line for a log record. Quote `requestId()` to support.

## Two things that will save you money

**Keep one idempotency key per submission.** Reuse it for every resend, including after a timeout.
A lost response does not prove the task was not created — a fresh key turns an unknown outcome into
a second paid task. This is also why `createTask` retries automatically **only** when you passed a
key: without one, a retry after a timeout would be a second generation and a second charge.

**A task that succeeds is charged, even if the result disappoints.** `quote()` reserves nothing and
creates nothing, so quote first when the price matters. `createTask` holds
`estimatedCost` and that hold is the ceiling: unused funds come back, and nothing above it is
collected later.

## What this package does not do

**Text models.** They speak the OpenAI, Anthropic and Gemini wire formats — the established Java
clients for those already work against `https://api.spicyapi.ai/v1` with the same key. Duplicating
them here would just be a worse version of something you can already use.

**Client-side applications.** Never ship this key inside an Android app, a desktop binary or
anything else a user can open. Call from your server.

## Development

```bash
mvn -B verify
```

Runs the unit suite against a local `com.sun.net.httpserver` stub, plus the contract drift check.
That check compares `contracts/openapi.yaml` against the published contract at
`https://docs.spicyapi.ai/openapi.yaml` and then re-checks every enum this package hard-codes
against it, because a contract that grows a value without the client growing it does not fail
anywhere — it just rejects the new value locally, silently. It needs no credentials: public
contract, public repository.

## Links

- [Documentation](https://docs.spicyapi.ai)
- [API reference](https://docs.spicyapi.ai/docs/api-reference)

---

<div align="center">
<sub>

Also available in [TypeScript](https://github.com/Spicy-API/spicy-sdk) · [Python](https://github.com/Spicy-API/spicy-python) · [Go](https://github.com/Spicy-API/spicy-go) · [PHP](https://github.com/Spicy-API/spicy-php) · **Java**

</sub>
</div>
