package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A signed price for one exact request, valid for five minutes.
 *
 * <p>Quoting reserves nothing and creates nothing. Carrying the quote into
 * {@link SpicyClient#createTask(CreateTaskRequest, String)} makes a price that moved in the meantime a rejection with
 * business code 40901, raised before any funds are held, rather than a silent charge at the new
 * price.
 *
 * @param quoteId       opaque signed token; pass it through unchanged and do not log it
 * @param model         the endpoint the quote resolved to
 * @param estimatedCost what this request is expected to cost
 * @param maxCharge     the ceiling; the final charge never exceeds it
 * @param currency      always {@code USD}
 * @param quantity      estimated billable quantity, expressed in {@code unit}
 * @param unit          what {@code quantity} counts
 * @param expiresAt     when the quote stops being accepted
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskQuote(String quoteId, String model, BigDecimal estimatedCost, BigDecimal maxCharge,
                        String currency, String quantity, String unit, Instant expiresAt) {

    /**
     * Whether this quote can still be submitted.
     *
     * @param now the reference instant, normally from the same clock as the rest of the caller
     * @return true when the quote is no longer accepted
     */
    public boolean expired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
