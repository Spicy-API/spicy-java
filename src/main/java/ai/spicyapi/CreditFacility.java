package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Optional;

/**
 * An approved pay-later facility.
 *
 * <p><b>This is a ceiling to borrow against, not funds in the wallet.</b> {@code limitUsd} is what
 * was approved, {@code usedUsd} is what is outstanding, and neither is money that was ever paid in.
 *
 * <p>Amounts are decimal USD strings rather than {@link java.math.BigDecimal} here because none of
 * them is an amount this client computes with: they are reported, displayed and reconciled. The
 * fields that do take part in a charge — a quote, a hold, a task's cost — are decimals.
 *
 * @param enabled      whether the facility can currently be drawn on
 * @param limitUsd     the approved ceiling
 * @param availableUsd how much of the ceiling is still free
 * @param usedUsd      outstanding settled principal
 * @param heldUsd      credit reserved for accepted tasks that have not settled
 * @param expiresAt    when the facility lapses; {@code null} when it does not
 * @param status       the facility's state as the platform names it
 * @param version      optimistic-concurrency version of the facility record
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditFacility(boolean enabled, String limitUsd, String availableUsd, String usedUsd,
                             String heldUsd, Instant expiresAt, String status, long version) {

    /**
     * When the facility lapses, when it does at all.
     *
     * @return the expiry, or empty when the facility does not expire
     */
    public Optional<Instant> expiry() {
        return Optional.ofNullable(expiresAt);
    }
}
