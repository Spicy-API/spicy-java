package ai.spicyapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Java client for the SpicyAPI generation platform.
 *
 * <p>SpicyAPI is predominantly image and video generation. Its text models are served through the
 * OpenAI, Anthropic and Gemini compatible layers, where the official vendor SDKs work by changing
 * only the base URL, so this client does not duplicate them. What it does cover is the part that
 * has no standard client: the asynchronous media task, from catalogue lookup and price quote
 * through submission, polling, retry and content destruction, plus direct media upload, download
 * URLs and callback signature verification.
 *
 * <h2>Dependencies</h2>
 * Jackson ({@code com.fasterxml.jackson.core:jackson-databind}) for JSON, and the JDK's own
 * {@link java.net.http.HttpClient} for transport. Nothing else.
 *
 * <h2>Getting started</h2>
 * <pre>{@code
 * SpicyClient client = SpicyClient.builder().apiKeyFromEnvironment().build();
 *
 * CreateTaskRequest request = CreateTaskRequest.of(
 *         "publisher/model/text-to-image", Map.of("prompt", "a paper-cut city at dusk"));
 *
 * TaskQuote quote = client.quote(request);                       // no funds are reserved
 * AcceptedTask accepted = client.createTask(                     // funds are held here
 *         request.withQuote(quote), UUID.randomUUID().toString());
 * TaskRecord task = client.waitForTerminal(accepted.taskId());
 *
 * task.outputText().ifPresent(...);                              // some endpoints answer in text
 * for (TaskAsset asset : task.assets()) { asset.url().ifPresent(...); }
 * }</pre>
 *
 * <h2>Costs</h2>
 * Methods that move money say so in their own documentation. In short: {@link #quote} and every
 * read are free, {@link #createTask(CreateTaskRequest, String) createTask}, {@link #retryTask} and
 * {@link #run(CreateTaskRequest, String) run} place a hold and lead to
 * a charge, and {@link #purgeTask} destroys content without touching the ledger.
 *
 * <h2>Errors</h2>
 * Every failure is a {@link SpicyException}, which is sealed, so a caller can switch over the
 * whole taxonomy. {@link SpicyApiException} carries the HTTP status, the platform's business code,
 * the {@code request_id} to quote to support, and {@code Retry-After} when the service sent one.
 * Branch on codes; error text is prose in whichever language the account or request selected.
 *
 * <h2>Thread safety</h2>
 * Instances are immutable and safe to share. Build one per process and reuse it; each instance owns
 * an {@link HttpClient} and its connection pool.
 *
 * @see <a href="https://docs.spicyapi.ai/openapi.yaml">The OpenAPI contract this client implements</a>
 */
public final class SpicyClient {

    /** Production API root. */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.spicyapi.ai/api/v1");

    /** Default TCP connect budget. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Default budget for one API call. Generation is asynchronous, so no call waits on a model. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Default budget for the leg that moves upload bytes.
     *
     * <p>Deliberately far longer than {@link #DEFAULT_REQUEST_TIMEOUT}: audio and video are accepted
     * up to 90 MiB, which 30 seconds only covers at a sustained 25 Mbps. A shared budget would cut
     * large transfers halfway and report it as a local timeout, which reads like a network fault
     * but is the client severing its own connection.
     */
    public static final Duration DEFAULT_UPLOAD_TIMEOUT = Duration.ofMinutes(10);

    /** Default bound for {@link #waitForTerminal(String)}. A local bound, not a service level. */
    public static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofMinutes(10);

    /** Default first polling interval. */
    public static final Duration DEFAULT_INITIAL_POLL_INTERVAL = Duration.ofSeconds(2);

    /** Default ceiling for the polling interval as it backs off. */
    public static final Duration DEFAULT_MAX_POLL_INTERVAL = Duration.ofSeconds(15);

    /**
     * The smallest remaining budget that still justifies one more poll.
     *
     * Below this, {@code waitForTerminal} stops early and reports the wait timeout — which carries
     * the task id — rather than letting a near-zero request timeout surface as a plain transport
     * timeout that does not.
     */
    private static final Duration MIN_POLL_BUDGET = Duration.ofSeconds(1);

    /** Default first retry delay, before jitter. */
    public static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofMillis(500);

    /** Default ceiling for one retry delay. */
    public static final Duration DEFAULT_MAX_RETRY_DELAY = Duration.ofSeconds(8);

    /** Default automatic retries per call. */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * The largest API response body this client will buffer, 8 MiB.
     *
     * <p><b>Not a contract limit.</b> Nothing in the OpenAPI document bounds a response, and the
     * service has no reason to approach this number: the largest thing it sends is
     * {@code listModels} with every input schema included, which is orders of magnitude below it.
     * The bound exists for the other case — a broken proxy, a captive portal or a hijacked
     * response that streams bytes without end. Without a ceiling, reading such a response is an
     * unbounded allocation in the caller's process, and it looks exactly like a slow download
     * until the heap is gone.
     *
     * <p>The value is 8 MiB because that is what the Go client uses, so a response accepted by one
     * client is accepted by the other. The TypeScript client stops at 4 MiB; the two differ, and
     * that difference is deliberate rather than an oversight to be tidied up here.
     */
    public static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    /** Environment variable read by {@link Builder#apiKeyFromEnvironment()}. */
    public static final String API_KEY_ENVIRONMENT_VARIABLE = "SPICY_API_KEY";

    /** Environment variable read by {@link Builder#baseUrlFromEnvironment()}. */
    public static final String BASE_URL_ENVIRONMENT_VARIABLE = "SPICY_API_BASE_URL";

    // Read from the JAR manifest's Implementation-Version rather than keeping a second copy in
    // source. A hard-coded copy eventually drifts from the released version, and it happens to be
    // what lands in the User-Agent — the one thing that tells us which release a caller is
    // actually running when something goes wrong.
    private static final String CLIENT_VERSION = readImplementationVersion();
    private static final String USER_AGENT = "SpicyAPI-Java/" + CLIENT_VERSION;

    // Logging goes through the JDK's own System.Logger: a published library has no business
    // picking a logging framework for its users, nor writing to stdout. Binding this to SLF4J or
    // Log4j is the caller's decision, made at runtime.
    private static final System.Logger LOG = System.getLogger(SpicyClient.class.getName());

    // java.net.http manages these headers itself; setting them throws IllegalArgumentException.
    private static final Set<String> HEADERS_MANAGED_BY_THE_JDK =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    /**
     * The client version, as reported in {@code User-Agent}.
     *
     * <p>Read from the {@code Implementation-Version} entry of the JAR manifest, so it is whatever
     * the build published. Outside a packaged JAR, for instance when running from class files,
     * there is no manifest to read and the value is {@code dev}.
     *
     * @return the published version, or {@code dev} when running outside a packaged JAR
     */
    public static String version() {
        return CLIENT_VERSION;
    }

    private static String readImplementationVersion() {
        Package descriptor = SpicyClient.class.getPackage();
        String version = descriptor == null ? null : descriptor.getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    // ── JSON mapping ────────────────────────────────────────────────────

    /**
     * Jackson module for the types this client exchanges.
     *
     * <p>Registered automatically on the client's own mapper. It is public so that an application
     * that decodes a webhook body itself, or persists a {@link TaskRecord}, can configure its own
     * {@link ObjectMapper} the same way.
     *
     * <p>It exists so the client can expose {@link Instant} and {@link java.time.LocalDate} rather
     * than raw strings without pulling in {@code jackson-datatype-jsr310}: the service emits RFC
     * 3339 timestamps and plain calendar dates, and those two types are all of it, so a second
     * artifact would not earn its place.
     *
     * <p>The return type is Jackson's own {@link Module}, which is also why this library does not
     * shade Jackson: a shaded module would be an internal type no caller could register.
     *
     * @return a fresh module instance
     */
    public static Module jacksonModule() {
        return SpicyJson.module();
    }

    /**
     * A mapper configured the way this client configures its own.
     *
     * @return a new, independently configurable mapper
     */
    public static ObjectMapper newObjectMapper() {
        return SpicyJson.newMapper();
    }

    // ── Construction ────────────────────────────────────────────────────

    private final String apiKey;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final Duration uploadTimeout;
    private final Duration waitTimeout;
    private final Duration initialPollInterval;
    private final Duration maxPollInterval;
    private final Duration retryBaseDelay;
    private final Duration maxRetryDelay;
    private final int maxRetries;
    private final String errorLanguage;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Clock clock;

    private SpicyClient(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.requestTimeout = builder.requestTimeout;
        this.uploadTimeout = builder.uploadTimeout;
        this.waitTimeout = builder.waitTimeout;
        this.initialPollInterval = builder.initialPollInterval;
        this.maxPollInterval = builder.maxPollInterval;
        this.retryBaseDelay = builder.retryBaseDelay;
        this.maxRetryDelay = builder.maxRetryDelay;
        this.maxRetries = builder.maxRetries;
        this.errorLanguage = builder.errorLanguage;
        this.mapper = builder.objectMapper == null ? SpicyJson.DEFAULT : builder.objectMapper;
        this.clock = builder.clock;
        this.http = builder.httpClient == null
                ? HttpClient.newBuilder()
                        .connectTimeout(builder.connectTimeout)
                        // Never follow redirects: following one forwards the Authorization
                        // header verbatim to whatever host the response happens to name.
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()
                : builder.httpClient;
    }

    /**
     * Starts building a client.
     *
     * @return a builder with production defaults and no key set
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds {@link SpicyClient} instances.
     *
     * <p>Every timeout, interval and retry bound is settable, because the defaults are tuned for an
     * interactive service call and a batch job has different needs.
     *
     * <p>Nested in its product on purpose, as {@code HttpClient.Builder} is: the builder has no
     * meaning apart from the type it builds, and {@code SpicyClient.Builder} is the name callers
     * already expect to write.
     */
    public static final class Builder {

        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL.toString();
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private Duration uploadTimeout = DEFAULT_UPLOAD_TIMEOUT;
        private Duration waitTimeout = DEFAULT_WAIT_TIMEOUT;
        private Duration initialPollInterval = DEFAULT_INITIAL_POLL_INTERVAL;
        private Duration maxPollInterval = DEFAULT_MAX_POLL_INTERVAL;
        private Duration retryBaseDelay = DEFAULT_RETRY_BASE_DELAY;
        private Duration maxRetryDelay = DEFAULT_MAX_RETRY_DELAY;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private String errorLanguage = "en";
        private HttpClient httpClient;
        private ObjectMapper objectMapper;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /**
         * Sets the API key.
         *
         * <p>Intended for keys that arrive from a secrets manager or a configuration service at
         * runtime. A key written into source is a key in version control, in build artifacts and in
         * every clone of the repository; prefer {@link #apiKeyFromEnvironment()} or fetch it from
         * whatever vault the deployment already has.
         *
         * @param apiKey the key, of the form {@code sk-spicy-...}
         * @return this builder
         * @throws IllegalArgumentException when the key is blank or contains a line break
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = Internal.requireHeaderValue(apiKey, "apiKey");
            return this;
        }

        /**
         * Reads the API key from the {@value SpicyClient#API_KEY_ENVIRONMENT_VARIABLE} environment
         * variable.
         *
         * @return this builder
         * @throws IllegalStateException when the variable is unset or blank
         */
        public Builder apiKeyFromEnvironment() {
            String value = System.getenv(API_KEY_ENVIRONMENT_VARIABLE);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(API_KEY_ENVIRONMENT_VARIABLE + " is not set");
            }
            return apiKey(value.trim());
        }

        /**
         * Overrides the API root.
         *
         * @param baseUrl an absolute HTTPS URL with no query or fragment; plain HTTP is accepted
         *                only for loopback addresses, so that a local test double is possible
         *                without making an unencrypted production call possible
         * @return this builder
         * @throws IllegalArgumentException when the URL is not usable as an API root
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Internal.normalizeBaseUrl(baseUrl);
            return this;
        }

        /**
         * Overrides the API root.
         *
         * @param baseUrl an absolute HTTPS URL
         * @return this builder
         * @throws IllegalArgumentException when the URL is not usable as an API root
         */
        public Builder baseUrl(URI baseUrl) {
            return baseUrl(Objects.requireNonNull(baseUrl, "baseUrl").toString());
        }

        /**
         * Reads the API root from the {@value SpicyClient#BASE_URL_ENVIRONMENT_VARIABLE}
         * environment variable, leaving the default in place when it is unset.
         *
         * @return this builder
         * @throws IllegalArgumentException when the variable holds an unusable URL
         */
        public Builder baseUrlFromEnvironment() {
            String value = System.getenv(BASE_URL_ENVIRONMENT_VARIABLE);
            return value == null || value.isBlank() ? this : baseUrl(value.trim());
        }

        /**
         * Sets the TCP connect budget. Ignored when {@link #httpClient(HttpClient)} supplies a
         * client, which carries its own.
         *
         * @param connectTimeout a positive duration
         * @return this builder
         * @throws IllegalArgumentException when the duration is not positive
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Internal.requirePositive(connectTimeout, "connectTimeout");
            return this;
        }

        /**
         * Sets the budget for one API call, retries included.
         *
         * @param requestTimeout a positive duration
         * @return this builder
         * @throws IllegalArgumentException when the duration is not positive
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Internal.requirePositive(requestTimeout, "requestTimeout");
            return this;
        }

        /**
         * Sets the budget for the upload leg that moves bytes to object storage.
         *
         * @param uploadTimeout a positive duration; the default allows 90 MiB over a slow link
         * @return this builder
         * @throws IllegalArgumentException when the duration is not positive
         */
        public Builder uploadTimeout(Duration uploadTimeout) {
            this.uploadTimeout = Internal.requirePositive(uploadTimeout, "uploadTimeout");
            return this;
        }

        /**
         * Sets the default polling budget for {@link SpicyClient#waitForTerminal(String)}.
         *
         * @param waitTimeout a positive duration
         * @return this builder
         * @throws IllegalArgumentException when the duration is not positive
         */
        public Builder waitTimeout(Duration waitTimeout) {
            this.waitTimeout = Internal.requirePositive(waitTimeout, "waitTimeout");
            return this;
        }

        /**
         * Sets the polling interval range. The interval starts at {@code initial}, grows by half
         * each round and stops at {@code max}.
         *
         * @param initial first interval
         * @param max     ceiling, not below {@code initial}
         * @return this builder
         * @throws IllegalArgumentException when either is not positive, or {@code max} is smaller
         *                                  than {@code initial}
         */
        public Builder pollInterval(Duration initial, Duration max) {
            Internal.requirePositive(initial, "initial");
            Internal.requirePositive(max, "max");
            if (max.compareTo(initial) < 0) {
                throw new IllegalArgumentException("max poll interval must not be smaller than the initial one");
            }
            this.initialPollInterval = initial;
            this.maxPollInterval = max;
            return this;
        }

        /**
         * Sets how many times a retryable call is retried automatically.
         *
         * @param maxRetries 0 to 5; 0 disables automatic retries
         * @return this builder
         * @throws IllegalArgumentException when outside that range
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0 || maxRetries > 5) {
                throw new IllegalArgumentException("maxRetries must be between 0 and 5");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets the retry backoff range. Delays double from {@code base} and are capped at
         * {@code max}, with jitter applied so that clients started by one event do not retry in
         * lockstep. A {@code Retry-After} from the service always wins over both.
         *
         * @param base first delay
         * @param max  ceiling, not below {@code base}
         * @return this builder
         * @throws IllegalArgumentException when either is not positive, or {@code max} is smaller
         *                                  than {@code base}
         */
        public Builder retryDelays(Duration base, Duration max) {
            Internal.requirePositive(base, "base");
            Internal.requirePositive(max, "max");
            if (max.compareTo(base) < 0) {
                throw new IllegalArgumentException("max retry delay must not be smaller than the base delay");
            }
            this.retryBaseDelay = base;
            this.maxRetryDelay = max;
            return this;
        }

        /**
         * Selects the language of error text.
         *
         * <p>Sent as {@code Accept-Language}. Affects {@code msg} and a task's
         * {@code errorMessage} only; codes never change with the language, which is why code is
         * what client logic branches on.
         *
         * @param errorLanguage one of {@code en}, {@code ja}, {@code ko}, {@code de}, {@code fr},
         *                      {@code es}, {@code pt-BR}, {@code ru}, {@code it}, {@code pl}
         * @return this builder
         * @throws IllegalArgumentException when blank
         */
        public Builder errorLanguage(String errorLanguage) {
            this.errorLanguage = Internal.requireHeaderValue(errorLanguage, "errorLanguage");
            return this;
        }

        /**
         * Supplies the HTTP client, for callers that need a proxy, a custom SSL context, a specific
         * executor or a shared connection pool.
         *
         * <p>Configure it with redirects disabled. A followed redirect re-sends the
         * {@code Authorization} header to a host named by the response.
         *
         * @param httpClient the client to use
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        /**
         * Supplies the JSON mapper.
         *
         * <p>It must have {@link SpicyClient#jacksonModule()} registered and should ignore unknown
         * properties, or responses from a newer service version will fail to map. Start from
         * {@link SpicyClient#newObjectMapper()} and adjust.
         *
         * @param objectMapper the mapper to use
         * @return this builder
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            return this;
        }

        /**
         * Supplies the clock used for timeouts, backoff and expiry checks. Intended for tests.
         *
         * @param clock the clock to read
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Builds the client.
         *
         * @return a new immutable, thread-safe client
         * @throws IllegalStateException when no API key was supplied
         */
        public SpicyClient build() {
            if (apiKey == null) {
                throw new IllegalStateException(
                        "an API key is required; call apiKey(String) or apiKeyFromEnvironment()");
            }
            return new SpicyClient(this);
        }
    }

    /**
     * The API root this client calls, without a trailing slash.
     *
     * @return the configured root, with any trailing slash removed
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * The budget for one API call.
     *
     * @return the configured per-call budget
     */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * The budget for the upload leg.
     *
     * @return the configured budget for the presigned upload
     */
    public Duration uploadTimeout() {
        return uploadTimeout;
    }

    /**
     * The default polling budget.
     *
     * @return the budget used when {@link #waitForTerminal(String)} is called without one
     */
    public Duration waitTimeout() {
        return waitTimeout;
    }

    /**
     * How many times a retryable call is retried automatically.
     *
     * @return the configured retry count, between 0 and 5
     */
    public int maxRetries() {
        return maxRetries;
    }

    // ── Catalogue ───────────────────────────────────────────────────────

    /**
     * Lists every model callable with this API key, with this account's prices.
     *
     * <p>Free. Results are cached by the service for one minute.
     *
     * @return the catalogue
     * @throws SpicyApiException       when the service rejects the request
     * @throws SpicyProtocolException  when the response is not the documented envelope
     * @throws SpicyTransportException when no usable response is obtained
     */
    public ModelPage listModels() {
        return listModels(ModelFilter.all());
    }

    /**
     * Lists the models matching a filter.
     *
     * <p>Free. This is the only source of callable model identifiers: models are added, renamed
     * behind a redirect and retired without any client release, so an identifier baked into a build
     * will eventually stop being the right one.
     *
     * @param filter the filter to apply; use {@link ModelFilter#all()} for none
     * @return the matching models
     * @throws NullPointerException    when {@code filter} is null
     * @throws SpicyApiException       when the service rejects the request
     * @throws SpicyProtocolException  when the response is not the documented envelope
     * @throws SpicyTransportException when no usable response is obtained
     */
    public ModelPage listModels(ModelFilter filter) {
        Objects.requireNonNull(filter, "filter");
        return call("GET", "/models" + filter.toQuery(), null, Map.of(), true, requestTimeout, ModelPage.class);
    }

    /**
     * Fetches one model, including its input JSON Schema.
     *
     * <p>Free.
     *
     * @param model the exact identifier from the catalogue; it contains slashes, which are encoded
     *              here into a single path segment
     * @return the model
     * @throws IllegalArgumentException when {@code model} is blank
     * @throws SpicyApiException        with status 404 when no such model is visible to this key
     * @throws SpicyProtocolException   when the response is not the documented envelope
     * @throws SpicyTransportException  when no usable response is obtained
     */
    public ApiModel getModel(String model) {
        String path = "/models/" + Internal.encodePathSegment(Internal.requireText(model, "model"));
        return call("GET", path, null, Map.of(), true, requestTimeout, ApiModel.class);
    }

    // ── Account ─────────────────────────────────────────────────────────

    /**
     * Reads the account's balance, held funds and funding sources.
     *
     * <p>Free. Scoped to the account, not to this key: every key on the account spends the same
     * money.
     *
     * <p>Useful as a budget indicator and as the thing to alert on. It is <b>not</b> a way to
     * decide whether one request will be admitted — part of a balance can be grant money fenced to
     * particular models, and part can be approved credit. {@link #quote} answers that question
     * exactly, for one exact request, and reserves nothing.
     *
     * @return the current balance
     * @throws SpicyApiException       when the service rejects the request
     * @throws SpicyProtocolException  when the response is not the documented envelope
     * @throws SpicyTransportException when no usable response is obtained
     */
    public Balance getBalance() {
        return call("GET", "/chat/credit", null, Map.of(), true, requestTimeout, Balance.class);
    }

    /**
     * Reads this API key's settled spend over the service's default window.
     *
     * <p>Free, but rate limited; see {@link #getUsage(LocalDate, LocalDate)} for why that matters
     * more here than anywhere else in this client.
     *
     * @return the usage report for the default window, which is the seven days before tomorrow UTC
     * @throws SpicyApiException       when the service rejects the request, including 429 when the
     *                                 account's shared reporting budget is exhausted
     * @throws SpicyProtocolException  when the response is not the documented envelope
     * @throws SpicyTransportException when no usable response is obtained
     */
    public UsageReport getUsage() {
        return getUsage(null, null);
    }

    /**
     * Reads this API key's settled spend over a date range.
     *
     * <p>Free, and scoped to this key: sibling keys and console generations are not counted.
     *
     * <p><b>Do not poll this to follow work in flight.</b> Two reasons, and the second one is
     * shared with everyone else on the account. It reports settled charges only, so a task that
     * has not finished contributes nothing yet and a period still running always reads low. And it
     * carries its own account-wide budget — a burst of 30 requests replenishing at 30 a minute,
     * shared by every key — whose limiter <b>fails closed</b>, so a polling loop here can lock the
     * whole account out of its own reporting. Follow one task with {@link #waitForTerminal(String)}.
     *
     * <p>Late settlement is attributed to the day the task was created, so a figure read today for
     * yesterday can still change.
     *
     * @param from first day of the range, inclusive; {@code null} for the service default, which
     *             is seven days before {@code to}
     * @param to   last day of the range, <b>exclusive</b>; {@code null} for the service default,
     *             which is tomorrow in UTC. The range may span at most 92 days
     * @return the usage report
     * @throws IllegalArgumentException when {@code to} is before {@code from}
     * @throws SpicyApiException        when the service rejects the request, including 429 when the
     *                                  account's shared reporting budget is exhausted and 503 when
     *                                  the query exceeds its five-second server budget — retry that
     *                                  one with a shorter range, not with the same one
     * @throws SpicyProtocolException   when the response is not the documented envelope
     * @throws SpicyTransportException  when no usable response is obtained
     */
    public UsageReport getUsage(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
        StringBuilder query = new StringBuilder();
        Internal.appendParameter(query, "from", from == null ? null : from.toString());
        Internal.appendParameter(query, "to", to == null ? null : to.toString());
        String path = query.length() == 0 ? "/usage" : "/usage?" + query;
        return call("GET", path, null, Map.of(), true, requestTimeout, UsageReport.class);
    }

    // ── Task lifecycle ──────────────────────────────────────────────────

    /**
     * Lists this API key's most recent tasks.
     *
     * <p>Free. Metadata only, and see {@link #listTasks(TaskFilter)} for what that excludes.
     *
     * @return the first page of task history for the service's default window
     * @throws SpicyApiException       when the service rejects the request
     * @throws SpicyProtocolException  when the response is not the documented envelope
     * @throws SpicyTransportException when no usable response is obtained
     */
    public TaskPage listTasks() {
        return listTasks(TaskFilter.all());
    }

    /**
     * Lists the tasks matching a filter, newest first.
     *
     * <p>Free. The rows carry metadata only: no input, no output, no signed media URL and no
     * processing detail. Read a selected task back with {@link #getTask(String)} for any of that.
     *
     * <p><b>The scope is this API key alone.</b> Tasks created by sibling keys on the same account,
     * and generations started in the web console, which use no API key at all, are not here. Their
     * absence is the documented behaviour rather than a gap to work around.
     *
     * <p>Pages read live state, not a snapshot, and {@link TaskPage} documents how to walk them —
     * including why the date range has to be pinned before a walk that can cross midnight UTC.
     *
     * @param filter the filter to apply; use {@link TaskFilter#all()} for none
     * @return the matching page
     * @throws NullPointerException    when {@code filter} is null
     * @throws SpicyApiException       when the service rejects the request, including 503 when the
     *                                 query exceeds its five-second server budget — retry that one
     *                                 with a narrower range rather than unchanged
     * @throws SpicyProtocolException  when the response is not the documented envelope
     * @throws SpicyTransportException when no usable response is obtained
     */
    public TaskPage listTasks(TaskFilter filter) {
        Objects.requireNonNull(filter, "filter");
        return call("GET", "/jobs" + filter.toQuery(), null, Map.of(), true, requestTimeout, TaskPage.class);
    }

    /**
     * Prices one exact request without creating anything.
     *
     * <p>Free, and safe to resend: it runs the same validation and admission checks as submission
     * but reserves no funds and creates no task. The returned quote is signed, bound to this
     * account, key and request, and valid for five minutes.
     *
     * @param request the submission to price
     * @return the quote
     * @throws NullPointerException    when {@code request} is null
     * @throws SpicyApiException       when validation or admission fails, for example business code
     *                                 40201 for an insufficient balance
     * @throws SpicyProtocolException  when the response is not the documented envelope
     * @throws SpicyTransportException when no usable response is obtained
     */
    public TaskQuote quote(CreateTaskRequest request) {
        Objects.requireNonNull(request, "request");
        // Send only the three fields that are actually priced. Passing the previous quoteId and
        // expectedCost back would be asking for a quote while still carrying an old price — and a
        // quote exists precisely to discover that the price moved. The mistake is a natural one to
        // make: take a quote, bind it with withQuote(...), have createTask fail (40901 or
        // otherwise), then re-quote from the same request variable. By then that variable is
        // already carrying the previous quote.
        //
        // The server most likely ignores these two fields today, but "most likely" is not a
        // property worth depending on, and obtaining a current price is the entire point of this
        // call. The TypeScript and Go clients do the same, and the Go one records the same
        // reasoning.
        CreateTaskRequest priced =
                new CreateTaskRequest(request.model(), request.input(), request.callBackUrl(), null, null);
        return call("POST", "/jobs/quote", priced, Map.of(), true, requestTimeout, TaskQuote.class);
    }

    /**
     * Creates an asynchronous generation task.
     *
     * <p><b>This reserves funds.</b> Acceptance holds {@link AcceptedTask#estimatedCost()} and that
     * amount is the ceiling for the eventual charge; unused funds are released when the task
     * settles. Acceptance is not completion: poll with {@link #waitForTerminal(String)} or receive
     * the terminal record on a callback.
     *
     * <p>Persist {@link AcceptedTask#taskId()} before doing anything else with it. Everything after
     * this point, including this client's own retries, depends on being able to name the task
     * again.
     *
     * @param request        the submission
     * @param idempotencyKey a stable key for this one logical submission, at most 128 characters,
     *                       retained for 24 hours and bound to both this API key and the normalized
     *                       request. Replaying it returns the original task instead of creating a
     *                       second one. Pass {@code null} only when a duplicate generation would be
     *                       acceptable: <b>without a key this call is never retried automatically</b>,
     *                       because a request that times out after the service accepted it would
     *                       otherwise become a second generation and a second charge
     * @return the accepted task
     * @throws NullPointerException     when {@code request} is null
     * @throws IllegalArgumentException when the key contains a line break or exceeds 128 characters
     * @throws SpicyApiException        when admission fails: 402 for funds, 403 for authorization,
     *                                  409 for a key already bound to a different request or to a
     *                                  sibling key, 40901 for a quote that expired or no longer
     *                                  matches
     * @throws SpicyProtocolException   when the response is not the documented envelope
     * @throws SpicyTransportException  when no usable response is obtained. With an idempotency key
     *                                  the submission can be repeated safely; without one, treat the
     *                                  outcome as unknown and reconcile before resubmitting
     */
    public AcceptedTask createTask(CreateTaskRequest request, String idempotencyKey) {
        return createTask(request, idempotencyKey, null);
    }

    /**
     * Creates an asynchronous generation task, keeping its content for a shorter time than the
     * account otherwise would.
     *
     * <p><b>This reserves funds</b>, exactly as {@link #createTask(CreateTaskRequest, String)}
     * does; everything that method documents applies here too.
     *
     * <p>Retention <b>only ever shortens</b>. The effective value is the smallest of this
     * argument, the account setting and the platform maximum, so a request cannot buy back a
     * longer window than the account allows. {@link Duration#ZERO} is meaningful and valid: the
     * outputs go as soon as the task reaches a terminal state.
     *
     * <p>The platform's ceiling is deliberately <b>not</b> checked here. It is the service's
     * number, and a copy of it in this client would be a constant that goes stale without anything
     * saying so — it would start refusing a window the platform had already begun accepting. An
     * oversized value is clamped by the service rather than rejected, and what was actually
     * applied comes back in {@link TaskRecord#retention()}, which is where to read it rather than
     * assuming the request got what it asked for.
     *
     * <p>{@link #retryTask} takes no retention of its own: a retry inherits its source task's
     * effective value, so one task family never ends up with two different expiry times.
     *
     * @param request        the submission
     * @param idempotencyKey see {@link #createTask(CreateTaskRequest, String)}; {@code null}
     *                       disables automatic retries for this call, and the retention header
     *                       does not change that — shortening retention does not make a resend
     *                       safe
     * @param retention      how long to keep this task's outputs and its stored request text;
     *                       {@code null} leaves the account setting in charge
     * @return the accepted task
     * @throws NullPointerException     when {@code request} is null
     * @throws IllegalArgumentException when the key is malformed, or the retention is negative or
     *                                  not a whole number of seconds
     * @throws SpicyApiException        when admission fails
     * @throws SpicyProtocolException   when the response is not the documented envelope
     * @throws SpicyTransportException  when no usable response is obtained
     */
    public AcceptedTask createTask(CreateTaskRequest request, String idempotencyKey, Duration retention) {
        Objects.requireNonNull(request, "request");
        Map<String, String> idempotency = idempotencyHeaders(idempotencyKey);
        Map<String, String> headers = new LinkedHashMap<>(idempotency);
        headers.putAll(retentionHeaders(retention));
        // The idempotency key decides whether this call may be retried automatically. Without
        // one, retrying an "accepted but the response was lost" timeout means a second generation
        // and a second charge. Only the key settles that — a retention header does not make a
        // retry safe — which is why this reads idempotency rather than the merged headers.
        return call("POST", "/jobs/createTask", request, headers, !idempotency.isEmpty(), requestTimeout,
                AcceptedTask.class);
    }

    /**
     * Fetches the current state of one task created by this API key.
     *
     * <p>Free.
     *
     * @param taskId the task identifier
     * @return the task record
     * @throws IllegalArgumentException when {@code taskId} is blank
     * @throws SpicyApiException        with status 404 when the task does not exist, belongs to
     *                                  another account, or was created by a sibling key; the three
     *                                  cases are deliberately indistinguishable
     * @throws SpicyProtocolException   when the response is not the documented envelope
     * @throws SpicyTransportException  when no usable response is obtained
     */
    public TaskRecord getTask(String taskId) {
        return getTask(taskId, requestTimeout);
    }

    /**
     * Creates a new task from a failed or expired one.
     *
     * <p><b>This reserves funds</b>, exactly as {@link #createTask(CreateTaskRequest, String)} does.
     * The source task is never
     * modified, and the current schema, price, permissions, balance and availability are all
     * evaluated again, so a retry can legitimately cost a different amount or be refused outright.
     *
     * @param taskId         the terminal task to retry, which must have been created by this API key
     * @param idempotencyKey optional key, scoped to this source task and the retry action; the same
     *                       caveat as {@link #createTask(CreateTaskRequest, String)} applies, and
     *                       {@code null} disables
     *                       automatic retries
     * @return the newly created task, carrying {@link AcceptedTask#sourceTask()}
     * @throws IllegalArgumentException when {@code taskId} is blank or the key is malformed
     * @throws SpicyApiException        with status 404 for an unknown or cross-key task, 402 when
     *                                  funds are short, or 409 on an idempotency conflict
     * @throws SpicyProtocolException   when the response is not the documented envelope
     * @throws SpicyTransportException  when no usable response is obtained
     */
    public AcceptedTask retryTask(String taskId, String idempotencyKey) {
        Map<String, Object> body = Map.of("taskId", Internal.requireText(taskId, "taskId"));
        Map<String, String> headers = idempotencyHeaders(idempotencyKey);
        return call("POST", "/jobs/retry", body, headers, !headers.isEmpty(), requestTimeout, AcceptedTask.class);
    }

    /**
     * Destroys a finished task's stored content: the generated media objects, the result payload
     * and the stored request text including the prompt.
     *
     * <p><b>Billing evidence is not touched.</b> The ledger, the charged amount, the model, the
     * state, the timestamps and the request ID all remain, so the task stays auditable; that is
     * what {@link PurgeResult#billingRetained()} reports, and it is the single thing callers
     * misread about an endpoint named purge.
     *
     * <p>Only a finished task can be destroyed. A queued or running one is refused with 400: its
     * outputs have not landed, and removing half of them is worse than removing none.
     *
     * <p>This endpoint reads no idempotency header. The {@code taskId} is itself the idempotency
     * key and a repeat returns the original {@code purgedAt}, which is precisely why this call is
     * safe for the client to resend after a timeout.
     *
     * @param taskId the terminal task whose content should be destroyed
     * @return what was destroyed, and whether the media sweep has caught up
     * @throws IllegalArgumentException when {@code taskId} is blank
     * @throws SpicyApiException        with status 400 for a task that has not finished, or 404 for
     *                                  one not visible to this key
     * @throws SpicyProtocolException   when the response is not the documented envelope
     * @throws SpicyTransportException  when no usable response is obtained
     */
    public PurgeResult purgeTask(String taskId) {
        Map<String, Object> body = Map.of("taskId", Internal.requireText(taskId, "taskId"));
        return call("POST", "/jobs/purge", body, Map.of(), true, requestTimeout, PurgeResult.class);
    }

    /**
     * Polls until the task is terminal, bounded by this client's wait timeout.
     *
     * @param taskId the task to poll
     * @return the terminal record
     * @throws IllegalArgumentException  when {@code taskId} is blank
     * @throws SpicyWaitTimeoutException when the local budget elapses first
     * @throws SpicyApiException         when a poll is rejected, or the task reports a state this
     *                                   release does not know
     * @throws SpicyTransportException   when no usable response is obtained
     */
    public TaskRecord waitForTerminal(String taskId) {
        return waitForTerminal(taskId, waitTimeout);
    }

    /**
     * Polls until the task is terminal, bounded locally.
     *
     * <p>The interval starts at the configured initial value, grows by half each round up to the
     * configured ceiling, and carries jitter so that a fleet of clients started by one event does
     * not poll in lockstep.
     *
     * <p>A terminal state whose assets are still {@code pending} does not end the wait: the record
     * says succeeded while the artifact has no URL yet, and returning there would hand the caller a
     * success with nothing to read.
     *
     * @param taskId  the task to poll
     * @param timeout the local budget
     * @return the terminal record
     * @throws IllegalArgumentException  when {@code taskId} is blank or {@code timeout} is not
     *                                   positive
     * @throws SpicyWaitTimeoutException when the budget elapses. The task is unaffected and still
     *                                   running; the exception carries the ID and the last record
     *                                   seen, so the work can be reconciled rather than repeated
     * @throws SpicyApiException         when a poll is rejected, or the task reports a state this
     *                                   release does not know
     * @throws SpicyTransportException   when no usable response is obtained
     */
    public TaskRecord waitForTerminal(String taskId, Duration timeout) {
        String id = Internal.requireText(taskId, "taskId");
        Internal.requirePositive(timeout, "timeout");
        Instant deadline = clock.instant().plus(timeout);
        long intervalMillis = initialPollInterval.toMillis();
        long ceilingMillis = maxPollInterval.toMillis();
        TaskRecord last = null;

        while (clock.instant().isBefore(deadline)) {
            Duration remaining = Duration.between(clock.instant(), deadline);
            // When the remaining budget cannot fund a meaningful request, break out and throw the
            // timeout below — the one that carries the taskId.
            //
            // Without this floor the final lap would issue a request with a near-zero timeout: for
            // a Duration under one millisecond toMillis() truncates to 0, so that lap is all but
            // guaranteed to time out — and what it throws is SpicyTimeoutException, which carries
            // no taskId. The caller loses the task id at the exact moment it matters most: the
            // task is still running and still being billed, while all they hold is "the request
            // timed out", with nothing tying this call to that task.
            //
            // The TypeScript client does not have this hole: its whole wait shares one abort
            // signal, and the signal's reason carries the taskId. That half was verified by
            // reading client.ts.
            //
            // This comment used to claim "Go does not have it either" — that half was wrong, and
            // it was an inference written without running anything. An audit report then copied it
            // forward as "confirmed", and it nearly became a fact nobody would re-check. Verified
            // by actually running it on 2026-09-20: Go merely lacked the *cause* (its per-attempt
            // deadline derives from waitCtx), but the outcome was identical — one polling timeout
            // ended the entire wait, a 600-second budget was voided by 208 milliseconds, and the
            // caller got no *WaitTimeoutError. Fixed the same day.
            //
            // Python had the identical hole and was fixed the same day. The floor is one second: a
            // round trip shorter than that would not return anything useful anyway.
            if (remaining.compareTo(MIN_POLL_BUDGET) < 0) {
                break;
            }
            TaskRecord polled;
            try {
                polled = getTask(id, remaining.compareTo(requestTimeout) < 0 ? remaining : requestTimeout);
            } catch (SpicyTimeoutException timedOut) {
                // One polling attempt timing out is not this wait failing — while budget
                // remains, keep polling.
                //
                // The MIN_POLL_BUDGET floor above guards against "not enough budget left for a
                // round trip". It does not guard against this case, where ten minutes of budget
                // remain and this single attempt still stalls for the full requestTimeout
                // (30 seconds). Letting it propagate would get two things wrong at once: it
                // loses the taskId (the exception comes from callForData, which does not know
                // which task it is polling for), and it promotes one piece of network turbulence
                // into the end of the whole wait, while the task keeps running and keeps being
                // billed.
                //
                // When the budget genuinely runs out, the loop condition routes the exit to the
                // SpicyWaitTimeoutException below, which does carry the taskId. Python had the
                // identical hole and was fixed the same day.
                polled = null;
            }
            if (polled != null) {
                last = polled;
                TaskState state = last.state();
                if (state == null || state == TaskState.UNKNOWN) {
                    // The state set is closed. A value outside it means this client is older
                    // than the server; polling on would only spin until the timeout, so surface
                    // it immediately instead.
                    throw new SpicyApiException(
                            "task " + id + " reported a state this client release does not recognise", 200, 200, "", null);
                }
                if (state.terminal() && !hasPendingAssets(last)) {
                    return last;
                }
            }
            long remainingMillis = Duration.between(clock.instant(), deadline).toMillis();
            Internal.sleep(Math.min(Internal.jitter(intervalMillis), Math.max(0L, remainingMillis)));
            intervalMillis = Math.min(Math.round(intervalMillis * 1.5d), ceilingMillis);
        }
        throw new SpicyWaitTimeoutException(id, timeout, last);
    }

    /**
     * Submits a task and waits for it to finish.
     *
     * <p><b>This reserves funds</b>, being {@link #createTask(CreateTaskRequest, String)} followed
     * by {@link #waitForTerminal(String)}.
     *
     * <p>The whole call is bounded by this client's wait timeout, submission included: what
     * creating the task costs is taken off the polling budget rather than added to it. If the
     * budget elapses the task is still running and still billable, and
     * {@link SpicyWaitTimeoutException#taskId()} is how it is found again.
     *
     * @param request        the submission
     * @param idempotencyKey see {@link #createTask(CreateTaskRequest, String)}
     * @return the terminal record
     * @throws NullPointerException      when {@code request} is null
     * @throws SpicyWaitTimeoutException when the task is accepted but does not finish in time
     * @throws SpicyApiException         when submission or a poll is rejected
     * @throws SpicyTransportException   when no usable response is obtained
     */
    public TaskRecord run(CreateTaskRequest request, String idempotencyKey) {
        return run(request, idempotencyKey, null);
    }

    /**
     * Submits a task with a shortened retention and waits for it to finish.
     *
     * <p><b>This reserves funds</b>, being {@link #createTask(CreateTaskRequest, String, Duration)}
     * followed by {@link #waitForTerminal(String)}.
     *
     * <p>The whole call is bounded by this client's wait timeout, submission included: whatever
     * creating the task costs is taken off the polling budget rather than added to it. Otherwise
     * the real ceiling would be "submission plus the wait timeout", which is not a number the
     * caller ever chose — and a slow or retried submission would quietly move it.
     *
     * <p>If the budget is gone by the time the task is accepted, the task still exists and is
     * still billable, so the failure is a {@link SpicyWaitTimeoutException} carrying its ID rather
     * than a bare timeout that carries nothing.
     *
     * @param request        the submission
     * @param idempotencyKey see {@link #createTask(CreateTaskRequest, String)}
     * @param retention      see {@link #createTask(CreateTaskRequest, String, Duration)};
     *                       {@code null} leaves the account setting in charge
     * @return the terminal record
     * @throws NullPointerException      when {@code request} is null
     * @throws SpicyWaitTimeoutException when the task is accepted but does not finish in time
     * @throws SpicyApiException         when submission or a poll is rejected
     * @throws SpicyTransportException   when no usable response is obtained
     */
    public TaskRecord run(CreateTaskRequest request, String idempotencyKey, Duration retention) {
        Instant startedAt = clock.instant();
        AcceptedTask accepted = createTask(request, idempotencyKey, retention);
        // Time spent creating the task comes out of the wait budget. Otherwise run's real
        // ceiling is "however long submission took, plus waitTimeout", while the caller believes
        // the ceiling is waitTimeout — and submission can be slow: it has its own requestTimeout
        // and may retry several times under an idempotency key.
        //
        // If the budget is already exhausted once the task exists, what we throw must be the wait
        // timeout that carries the taskId: the task has been created and the funds are already
        // held, so handing back a timeout without an id would leave a live charge untraceable. The
        // budget reported is the full waitTimeout, because that is the number the caller set.
        Duration remaining = waitTimeout.minus(Duration.between(startedAt, clock.instant()));
        if (remaining.compareTo(MIN_POLL_BUDGET) < 0) {
            throw new SpicyWaitTimeoutException(accepted.taskId(), waitTimeout, null);
        }
        return waitForTerminal(accepted.taskId(), remaining);
    }

    // ── Media ───────────────────────────────────────────────────────────

    /**
     * Step 1 of 3: asks for a presigned upload slot.
     *
     * <p>Free. Most callers want {@link #uploadBytes} or {@link #uploadFile(Path)}, which perform
     * all three steps; the steps are public for callers that stream bytes from elsewhere.
     *
     * @param contentType the media type of what is about to be sent
     * @param bytes       the exact byte count about to be sent; the commit compares it against what
     *                    actually arrived
     * @return the ticket
     * @throws NullPointerException     when {@code contentType} is null
     * @throws IllegalArgumentException when {@code bytes} is not positive or exceeds the documented
     *                                  ceiling for this media type
     * @throws SpicyApiException        with status 413 when the service refuses the declared size
     * @throws SpicyProtocolException   when the response is not the documented envelope
     * @throws SpicyTransportException  when no usable response is obtained
     */
    public UploadTicket createUploadUrl(UploadContentType contentType, long bytes) {
        Objects.requireNonNull(contentType, "contentType");
        if (bytes <= 0) {
            throw new IllegalArgumentException("bytes must be positive");
        }
        if (bytes > contentType.maxBytes()) {
            throw new IllegalArgumentException(contentType.mediaType() + " uploads are limited to "
                    + contentType.maxBytes() + " bytes, and " + bytes + " were declared");
        }
        Map<String, Object> body = Map.of("contentType", contentType.mediaType(), "bytes", bytes);
        // No automatic retry: every call claims a fresh object key, so a retry would only leave
        // behind one more ticket nobody will ever use.
        return call("POST", "/common/upload-url", body, Map.of(), false, requestTimeout, UploadTicket.class);
    }

    /**
     * Step 2 of 3: moves the bytes to object storage.
     *
     * <p>Free, and it does not talk to the API at all. Two properties of this leg are easy to get
     * wrong and fail opaquely.
     *
     * <p>The ticket's headers are forwarded <b>unchanged</b>. They are part of what the presigned
     * URL signed, so dropping one, renaming one or helpfully adding one makes the signature stop
     * matching and storage answers 403.
     *
     * <p>No SpicyAPI {@code Authorization} header is attached. The URL is itself the grant and it
     * points at object storage rather than at the API; adding a key would only send the key
     * somewhere it was never needed.
     *
     * @param ticket the ticket obtained from {@link #createUploadUrl}
     * @param data   exactly the bytes declared when the ticket was created
     * @throws NullPointerException    when either argument is null
     * @throws SpicyUploadException    when the data exceeds the ticket, or storage refuses the PUT
     * @throws SpicyTimeoutException   when the upload budget elapses
     * @throws SpicyTransportException when the transfer fails or the thread is interrupted
     */
    public void putUploadBytes(UploadTicket ticket, byte[] data) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(data, "data");
        if (ticket.maxBytes() > 0 && data.length > ticket.maxBytes()) {
            throw new SpicyUploadException(
                    "file is " + data.length + " bytes, above the ticket limit of " + ticket.maxBytes(), 413);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ticket.uploadUrl()))
                .timeout(uploadTimeout);
        for (Map.Entry<String, String> header : ticket.headers().entrySet()) {
            // Content-Length is in that set, and BodyPublishers.ofByteArray sets it to
            // data.length — the same number declared to the ticket — so skipping it does not
            // change what actually goes out.
            if (HEADERS_MANAGED_BY_THE_JDK.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            builder.header(header.getKey(), header.getValue());
        }
        HttpRequest put = builder
                .method(ticket.method(), HttpRequest.BodyPublishers.ofByteArray(data))
                .build();
        try {
            HttpResponse<Void> response = http.send(put, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new SpicyUploadException(
                        "presigned upload failed with HTTP " + response.statusCode(), response.statusCode());
            }
        } catch (HttpTimeoutException timeout) {
            throw new SpicyTimeoutException(uploadTimeout);
        } catch (IOException failure) {
            throw new SpicyTransportException("presigned upload failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SpicyTransportException("interrupted during the upload", interrupted);
        }
    }

    /**
     * Step 3 of 3: commits the upload, which is what makes the file usable.
     *
     * <p>Free. The service compares the stored size, media type and file signature against the
     * ticket, hashes the bytes and copies them to an immutable private key.
     *
     * @param fileId the identifier from {@link UploadTicket#fileId()}
     * @return the committed file, whose {@link UploadedFile#uri()} is what goes into task input
     * @throws IllegalArgumentException when {@code fileId} is blank
     * @throws SpicyApiException        with business code 40003 when the stored bytes do not match
     *                                  the ticket, which cannot be fixed by committing again: start
     *                                  over from a fresh ticket
     * @throws SpicyProtocolException   when the response is not the documented envelope
     * @throws SpicyTransportException  when no usable response is obtained
     */
    public UploadedFile commitUploadedFile(String fileId) {
        String path = "/files/" + Internal.encodePathSegment(Internal.requireText(fileId, "fileId")) + "/commit";
        // The server explicitly guarantees commit is idempotent — repeating it returns the same
        // record — so a transport-level retry is safe here.
        return call("POST", path, null, Map.of(), true, requestTimeout, UploadedFile.class);
    }

    /**
     * Performs all three upload steps.
     *
     * <p>Free. Only needed for bytes held locally: a publicly reachable HTTPS URL can be placed in
     * model input directly, with no upload at all.
     *
     * @param data        the bytes to upload
     * @param contentType their media type
     * @return the committed file; place {@link UploadedFile#uri()} into the model input field
     * @throws NullPointerException     when either argument is null
     * @throws IllegalArgumentException when the data is empty or above the type's ceiling
     * @throws SpicyUploadException     when storage refuses the transfer
     * @throws SpicyApiException        when the ticket or the commit is rejected
     * @throws SpicyTransportException  when no usable response is obtained
     */
    public UploadedFile uploadBytes(byte[] data, UploadContentType contentType) {
        Objects.requireNonNull(data, "data");
        UploadTicket ticket = createUploadUrl(contentType, data.length);
        putUploadBytes(ticket, data);
        return commitUploadedFile(ticket.fileId());
    }

    /**
     * Uploads a file, inferring the media type from its extension.
     *
     * <p>Free. Reads the whole file into memory, which the 90 MiB ceiling bounds.
     *
     * @param path the file to upload
     * @return the committed file
     * @throws NullPointerException     when {@code path} is null
     * @throws IllegalArgumentException when the extension is not one of the accepted media types
     * @throws IOException              when the file cannot be read
     * @throws SpicyUploadException     when storage refuses the transfer
     * @throws SpicyApiException        when the ticket or the commit is rejected
     */
    public UploadedFile uploadFile(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        UploadContentType contentType = UploadContentType.fromFileName(path.getFileName().toString())
                .orElseThrow(() -> new IllegalArgumentException(
                        "cannot infer a media type from " + path.getFileName()
                                + "; pass an UploadContentType explicitly"));
        return uploadFile(path, contentType);
    }

    /**
     * Uploads a file with an explicit media type.
     *
     * <p>Free.
     *
     * @param path        the file to upload
     * @param contentType its media type, which the service verifies against the bytes
     * @return the committed file
     * @throws NullPointerException    when either argument is null
     * @throws IOException             when the file cannot be read
     * @throws SpicyUploadException    when storage refuses the transfer
     * @throws SpicyApiException       when the ticket or the commit is rejected
     */
    public UploadedFile uploadFile(Path path, UploadContentType contentType) throws IOException {
        return uploadBytes(Files.readAllBytes(Objects.requireNonNull(path, "path")), contentType);
    }

    /**
     * Mints a fresh signed URL for the first output of a task.
     *
     * <p>Free.
     *
     * @param taskId the task that produced the output
     * @return the download ticket
     * @throws IllegalArgumentException when {@code taskId} is blank
     * @throws SpicyApiException        with status 404 when the task is not visible to this key
     * @throws SpicyTransportException  when no usable response is obtained
     */
    public DownloadTicket createDownloadUrl(String taskId) {
        return createDownloadUrl(taskId, null);
    }

    /**
     * Mints a fresh signed URL for one output of a task.
     *
     * <p>Free. Assets already arrive with usable URLs on the task record, so this exists to refresh
     * one that has expired, or to select a specific output by key.
     *
     * @param taskId the task that produced the output
     * @param key    the asset key from {@link TaskAsset#key()}, or {@code null} for the first output
     * @return the download ticket
     * @throws IllegalArgumentException when {@code taskId} is blank
     * @throws SpicyApiException        with status 404 when the task or key is not visible to this
     *                                  key
     * @throws SpicyTransportException  when no usable response is obtained
     */
    public DownloadTicket createDownloadUrl(String taskId, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", Internal.requireText(taskId, "taskId"));
        if (key != null && !key.isBlank()) {
            body.put("key", key);
        }
        return call("POST", "/common/download-url", body, Map.of(), true, requestTimeout, DownloadTicket.class);
    }

    // ── Request pipeline ────────────────────────────────────────────────

    private TaskRecord getTask(String taskId, Duration timeout) {
        String path = "/jobs/recordInfo?taskId="
                + URLEncoder.encode(Internal.requireText(taskId, "taskId"), StandardCharsets.UTF_8);
        return call("GET", path, null, Map.of(), true, timeout, TaskRecord.class);
    }

    private <T> T call(String method, String path, Object body, Map<String, String> headers, boolean retryable,
                       Duration timeout, Class<T> type) {
        JsonNode data = callForData(method, path, body, headers, retryable, timeout);
        try {
            return mapper.treeToValue(data, type);
        } catch (JsonProcessingException failure) {
            throw new SpicyProtocolException(
                    "the response payload did not match " + type.getSimpleName(), 200, failure);
        }
    }

    private JsonNode callForData(String method, String path, Object body, Map<String, String> extraHeaders,
                                 boolean retryable, Duration timeout) {
        String payload = serialize(body);
        Instant deadline = clock.instant().plus(timeout);
        SpicyException lastFailure = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                throw lastFailure == null ? new SpicyTimeoutException(timeout) : lastFailure;
            }

            HttpResponse<String> response;
            try {
                response = http.send(buildRequest(method, path, payload, extraHeaders, remaining),
                        BoundedResponseBody.handler(MAX_RESPONSE_BYTES));
            } catch (HttpTimeoutException timedOut) {
                lastFailure = new SpicyTimeoutException(timeout);
                if (!retryable || attempt == maxRetries) {
                    throw lastFailure;
                }
                logRetry(method, path, "a local timeout", attempt);
                backOff(attempt, null, deadline);
                continue;
            } catch (IOException failure) {
                if (BoundedResponseBody.tooLarge(failure)) {
                    // Do not retry. This is not turbulence: the same broken intermediary will
                    // emit exactly the same thing again, so four attempts just hit the ceiling
                    // four times and make the failure slower to surface.
                    throw new SpicyTransportException("the API response exceeded the local "
                            + MAX_RESPONSE_BYTES + "-byte ceiling; something between this client and the API"
                            + " is not answering with the API's own response", failure);
                }
                lastFailure = new SpicyTransportException("the network request failed", failure);
                if (!retryable || attempt == maxRetries) {
                    throw lastFailure;
                }
                logRetry(method, path, "a transport failure", attempt);
                backOff(attempt, null, deadline);
                continue;
            } catch (InterruptedException interrupted) {
                // Restore the interrupt flag, or cancellation logic further up never sees it.
                Thread.currentThread().interrupt();
                throw new SpicyTransportException("interrupted while awaiting the API response", interrupted);
            }

            int status = response.statusCode();
            JsonNode envelope;
            try {
                envelope = mapper.readTree(response.body());
            } catch (JsonProcessingException malformed) {
                SpicyProtocolException failure =
                        new SpicyProtocolException("the response body was not JSON", status, malformed);
                if (retryable && attempt < maxRetries
                        && SpicyApiException.RETRYABLE_HTTP_STATUS.contains(status)) {
                    lastFailure = failure;
                    logRetry(method, path, "an unreadable body behind HTTP " + status, attempt);
                    backOff(attempt, retryAfterOf(response), deadline);
                    continue;
                }
                throw failure;
            }
            if (envelope == null || !envelope.isObject()) {
                throw new SpicyProtocolException(
                        "the response body was not the documented {code,msg,data,request_id} envelope", status, null);
            }

            int code = envelope.path("code").asInt(0);
            String requestId = envelope.path("request_id").asText("");
            if (status / 100 != 2 || code != 200) {
                String message = envelope.path("msg").asText("the request failed with HTTP " + status);
                SpicyApiException failure =
                        new SpicyApiException(message, status, code, requestId, retryAfterOf(response));
                if (retryable && attempt < maxRetries && failure.transientFailure()) {
                    lastFailure = failure;
                    logRetry(method, path, "HTTP " + status + " code " + code, attempt);
                    backOff(attempt, failure.retryAfter().orElse(null), deadline);
                    continue;
                }
                throw failure;
            }

            JsonNode data = envelope.get("data");
            if (data == null || data.isNull()) {
                throw new SpicyProtocolException("a successful envelope carried no data", status, null);
            }
            return data;
        }
        throw lastFailure == null
                ? new SpicyTransportException("the request produced no response")
                : lastFailure;
    }

    private HttpRequest buildRequest(String method, String path, String payload, Map<String, String> extraHeaders,
                                     Duration remaining) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(remaining)
                .header("Accept", "application/json")
                .header("Accept-Language", errorLanguage)
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", USER_AGENT);
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        extraHeaders.forEach(builder::header);
        return builder
                .method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    private String serialize(Object body) {
        if (body == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("the request body could not be serialized to JSON", failure);
        }
    }

    // Method, path and reason only. Never the key, the request body, the response body or any
    // signed URL — a published library that writes those into its users' logs has created a leak
    // on their behalf.
    private void logRetry(String method, String path, String reason, int attempt) {
        LOG.log(System.Logger.Level.DEBUG, () -> "retrying " + method + " " + path + " after " + reason
                + " (attempt " + (attempt + 1) + " of " + (maxRetries + 1) + ")");
    }

    private Duration retryAfterOf(HttpResponse<?> response) {
        Optional<String> header = response.headers().firstValue("retry-after");
        if (header.isEmpty()) {
            return null;
        }
        String value = header.get().trim();
        try {
            return Duration.ofSeconds(Math.max(0L, Long.parseLong(value)));
        } catch (NumberFormatException notSeconds) {
            // Retry-After may also be an HTTP date; both forms are valid.
            try {
                Duration delay = Duration.between(clock.instant(),
                        Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value)));
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeException notADate) {
                return null;
            }
        }
    }

    private void backOff(int attempt, Duration retryAfter, Instant deadline) {
        long exponential = Math.min(retryBaseDelay.toMillis() << Math.min(attempt, 16), maxRetryDelay.toMillis());
        // The server's own delay wins: it knows when the rate-limit window actually reopens,
        // whereas we would only be guessing.
        long delay = Math.max(Internal.jitter(exponential), retryAfter == null ? 0L : retryAfter.toMillis());
        long remaining = Duration.between(clock.instant(), deadline).toMillis();
        Internal.sleep(Math.min(delay, Math.max(0L, remaining)));
    }

    private static boolean hasPendingAssets(TaskRecord task) {
        if (task.state() != TaskState.SUCCEEDED) {
            return false;
        }
        for (TaskAsset asset : task.assets()) {
            if (asset.pending() && !asset.unavailable()) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> retentionHeaders(Duration retention) {
        if (retention == null) {
            return Map.of();
        }
        // Reject only the two cases that can be decided locally: a negative value, and a
        // fractional second. The contract says the server clamps negatives to 0, clamps anything
        // above the ceiling down to it, and ignores non-integer values outright — that leniency
        // belongs to the server, so that a header meant only to be more conservative cannot fail
        // an entire generation.
        //
        // The ceiling is deliberately not checked here: only the server knows the platform limit,
        // and copying it into the client buries a constant that will expire. The value that
        // actually took effect is read back from TaskRecord.retention. All four SDKs agree on
        // this.
        if (retention.isNegative()) {
            throw new IllegalArgumentException("retention must not be negative");
        }
        if (retention.getNano() != 0) {
            // Someone passing 1500 milliseconds did not mean "1 second". Truncating would be a
            // silent shortening, and this header governs exactly when content is destroyed.
            throw new IllegalArgumentException("retention must be a whole number of seconds");
        }
        return Map.of("X-Spicy-Retention", Long.toString(retention.toSeconds()));
    }

    private static Map<String, String> idempotencyHeaders(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Map.of();
        }
        String value = Internal.requireHeaderValue(idempotencyKey, "idempotencyKey");
        if (value.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey must be at most 128 characters");
        }
        return Map.of("Idempotency-Key", value);
    }
}
