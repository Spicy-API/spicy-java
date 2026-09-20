package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One price tier of a model.
 *
 * @param variant      tier key; empty when the model has a single price. When one input field
 *                     sets the price it is that field's value, for example {@code 720p}; when
 *                     several do it is {@code field=value} pairs sorted by field name and joined
 *                     with {@code ;}
 * @param unit         what the price is per: {@code per_image}, {@code per_second},
 *                     {@code per_request} or {@code per_1k_tokens}. Left as a string because the
 *                     platform may add units, and an unrecognised unit should still display
 * @param price        the price this account pays
 * @param regularPrice the undiscounted price, present only while an offer applies
 * @param offerLabel   customer-facing name of the active offer, when there is one
 * @param offerPercent the discount as a percentage string, when an offer applies
 * @param offerEndsAt  when the offer ends, when it has an end
 * @param currency     always {@code USD}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelPrice(String variant, String unit, BigDecimal price, BigDecimal regularPrice,
                         String offerLabel, String offerPercent, Instant offerEndsAt, String currency) {

    /**
     * Whether a discount is currently reflected in {@link #price()}.
     *
     * @return true when an offer is reflected in the price
     */
    public boolean discounted() {
        return regularPrice != null && price != null && regularPrice.compareTo(price) > 0;
    }
}
