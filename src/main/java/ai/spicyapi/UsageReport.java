package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Settled spend and call counts for one API key over a date range.
 *
 * <p><b>This is a reconciliation report, not a progress indicator.</b> Three properties make it
 * unsuitable for following work in flight, and each one has bitten someone:
 *
 * <ul>
 *   <li><b>It counts settled charges only.</b> Funds held by a task that has not finished are
 *       nowhere in here, so a period that is still running always reads low.</li>
 *   <li><b>A past day can change.</b> Late settlement is attributed to the day the task was
 *       created, so yesterday's total is not final today.</li>
 *   <li><b>It has its own rate limit, and that limiter fails closed.</b> The budget is
 *       account-wide — a burst of 30 with 30 replenished per minute, shared by every key on the
 *       account — so polling this endpoint can lock the whole account out of its own reporting.
 *       Follow one task with {@link SpicyClient#waitForTerminal(String)} instead.</li>
 * </ul>
 *
 * <p>Scope is the calling API key. Tasks created by sibling keys, and generations started in the
 * web console (which use no key at all), are not counted here, and their absence is not a fault.
 *
 * @param from       first day of the range, inclusive
 * @param to         last day of the range, <b>exclusive</b>
 * @param currency   always {@code USD}
 * @param totalCalls tasks created in the range
 * @param totalSpend settled charges for those tasks
 * @param days       per-day breakdown, sparse: days with no activity are omitted
 * @param models     per-model breakdown, sparse in the same way
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsageReport(LocalDate from, LocalDate to, String currency, long totalCalls, BigDecimal totalSpend,
                          List<UsageByDay> days, List<UsageByModel> models) {

    public UsageReport {
        days = Internal.emptyIfNull(days);
        models = Internal.emptyIfNull(models);
    }
}
