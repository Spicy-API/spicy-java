package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * What this account can spend right now.
 *
 * <p>Free to read, and scoped to the account rather than to the API key: every key on the account
 * draws on the same funds.
 *
 * <p><b>A positive {@link #available()} does not mean the next task will be admitted.</b> Part of
 * the money can be a promotional grant restricted to particular models, and part can be approved
 * credit rather than cash. Admission checks the funds that can actually pay for <em>that</em>
 * request, which is why a balance reading is a budget indicator and never a guarantee — the
 * authoritative answer for one request is {@link SpicyClient#quote}, which runs the same admission
 * rules without reserving anything.
 *
 * @param available net wallet funds. Can be negative, when approved credit has been drawn on or an
 *                  external payment was recovered
 * @param held      funds reserved by accepted tasks that have not settled yet. Already spoken for,
 *                  and not available to a new task
 * @param total     {@code available} plus {@code held}
 * @param funding   where the money comes from, broken down by source; {@code null} when the service
 *                  did not include the breakdown
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Balance(BigDecimal available, BigDecimal held, BigDecimal total, FundingOverview funding) {

    /**
     * The funding breakdown, when the service sent one.
     *
     * @return the breakdown by source, or empty when it was not included
     */
    public Optional<FundingOverview> fundingSources() {
        return Optional.ofNullable(funding);
    }
}
