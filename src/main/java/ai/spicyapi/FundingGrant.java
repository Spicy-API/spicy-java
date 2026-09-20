package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One promotional grant on the account.
 *
 * <p>Grant money is real money with a fence around it. The fence is {@link #modelSlugs()}, and it
 * is checked when a task is admitted rather than when the grant is read — so a balance that looks
 * sufficient can still fail to pay for one particular model.
 *
 * @param id           the grant identifier
 * @param name         the grant's internal name
 * @param amountUsd    the amount originally granted
 * @param availableUsd how much is still spendable
 * @param heldUsd      how much is reserved by tasks that have not settled
 * @param spentUsd     how much has been settled against it
 * @param status       the grant's state as the platform names it
 * @param startsAt     when the grant became spendable
 * @param expiresAt    when the grant lapses; {@code null} when it does not
 * @param modelSlugs   the models this grant may pay for. <b>Empty means every model</b>, not none
 * @param customerMemo the note written for the account holder
 * @param createdAt    when the grant was issued
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FundingGrant(String id, String name, String amountUsd, String availableUsd, String heldUsd,
                           String spentUsd, String status, Instant startsAt, Instant expiresAt,
                           List<String> modelSlugs, String customerMemo, Instant createdAt) {

    public FundingGrant {
        modelSlugs = Internal.emptyIfNull(modelSlugs);
    }

    /**
     * Whether this grant is unrestricted.
     *
     * <p>Exists because the wire form of "pays for everything" is an empty list, which reads at a
     * glance as "pays for nothing". Getting that backwards turns a fully usable grant into one a
     * caller believes it cannot spend.
     *
     * @return true when the grant may pay for any model
     */
    public boolean appliesToEveryModel() {
        return modelSlugs.isEmpty();
    }

    /**
     * When the grant lapses, when it does at all.
     *
     * @return the expiry, or empty when the grant does not expire
     */
    public Optional<Instant> expiry() {
        return Optional.ofNullable(expiresAt);
    }
}
