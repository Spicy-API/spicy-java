package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Optional;

/**
 * Where an account's spending power comes from.
 *
 * <p>The split matters because the three sources are not interchangeable. Prepaid funds pay for
 * anything; a grant may be restricted to named models; approved credit is a ceiling to borrow
 * against, not money in the wallet. Summing them into one number is how a caller ends up sure a
 * request is affordable right up to the 402.
 *
 * @param balanceUsd          net wallet available balance; may be negative after credit was drawn
 *                            on or an external payment was recovered
 * @param heldUsd             reserved by accepted tasks that have not settled
 * @param prepaidAvailableUsd unrestricted prepaid funds, the part that pays for any model
 * @param grantAvailableUsd   active grant funds. Whether a grant pays for a given request is
 *                            decided at admission, against that grant's model list
 * @param cashShortfallUsd    an external payment recovery that neither grants nor credit can
 *                            cover. While this is above zero, new tasks are blocked
 * @param credit              the approved credit facility; {@code null} when the account has none
 * @param grants              the active grants, most useful for their model restrictions
 * @param grantsHasMore       true when the account has more grants than this response listed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FundingOverview(String balanceUsd, String heldUsd, String prepaidAvailableUsd,
                              String grantAvailableUsd, String cashShortfallUsd, CreditFacility credit,
                              List<FundingGrant> grants, boolean grantsHasMore) {

    // A missing grants array normalises to an empty list: an account with no grants and an account
    // whose response omitted the section both mean "nothing to enumerate" to a caller, so one must
    // not arrive as null and the other as an empty list.
    public FundingOverview {
        grants = Internal.emptyIfNull(grants);
    }

    /**
     * The approved credit facility, when the account has one.
     *
     * @return the credit facility, or empty when the account has none
     */
    public Optional<CreditFacility> creditFacility() {
        return Optional.ofNullable(credit);
    }
}
