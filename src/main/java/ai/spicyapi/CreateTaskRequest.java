package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * A task submission.
 *
 * <p>{@code input} is a map rather than a typed object because it genuinely is model-specific:
 * the service validates it against the selected model's {@link ApiModel#inputSchema()}, and no
 * field set is shared across models. Values may be any type Jackson can serialize.
 *
 * <p>Media fields accept a public HTTPS URL, a committed {@code spicy://f/fil_...} URI from
 * {@link SpicyClient#uploadBytes}, or a Base64 data URI for small images.
 *
 * @param model        an identifier from the catalogue
 * @param input        payload for that model's schema
 * @param callBackUrl  optional public HTTPS endpoint for terminal delivery; verify what arrives
 *                     there with {@link CallbackSignatureVerifier}
 * @param quoteId      optional quote token from {@link SpicyClient#quote}
 * @param expectedCost optional confirmed amount, sent together with {@code quoteId}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateTaskRequest(String model, Map<String, Object> input, String callBackUrl, String quoteId,
                                @JsonSerialize(using = UsdAmountSerializer.class) BigDecimal expectedCost) {

    // input is defensively copied here: mutating that map after submission must not change what
    // has already gone out.
    public CreateTaskRequest {
        model = Internal.requireText(model, "model");
        input = Internal.unmodifiableCopy(Objects.requireNonNull(input, "input"));
    }

    /**
     * A submission with no callback and no quote.
     *
     * @param model an identifier from the catalogue
     * @param input payload for that model's schema
     * @return the request
     * @throws IllegalArgumentException when {@code model} is blank
     * @throws NullPointerException     when {@code input} is null
     */
    public static CreateTaskRequest of(String model, Map<String, Object> input) {
        return new CreateTaskRequest(model, input, null, null, null);
    }

    /**
     * Adds a terminal-delivery callback.
     *
     * @param url a public HTTPS endpoint; {@code http://} is refused by the service because the
     *            delivery carries the prompt and signed result links
     * @return a new request
     */
    public CreateTaskRequest withCallBackUrl(String url) {
        return new CreateTaskRequest(model, input, url, quoteId, expectedCost);
    }

    /**
     * Binds this submission to a quote, so that a price which moved is refused rather than
     * charged.
     *
     * @param quote a quote obtained for this same request
     * @return a new request carrying the quote token and confirmed amount
     * @throws NullPointerException when {@code quote} is null
     */
    public CreateTaskRequest withQuote(TaskQuote quote) {
        Objects.requireNonNull(quote, "quote");
        return new CreateTaskRequest(model, input, callBackUrl, quote.quoteId(), quote.estimatedCost());
    }
}
