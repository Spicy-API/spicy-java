package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One UTC day's share of a {@link UsageReport}.
 *
 * <p>Days with no activity are absent rather than present with zeros, so a report over a week can
 * hold fewer than seven of these. A caller charting the period has to fill the gaps itself.
 *
 * @param day       the UTC calendar date
 * @param calls     tasks created that day, whatever became of them
 * @param succeeded how many of them produced a result
 * @param failed    how many of them ended without one
 * @param spend     settled charges for that day's tasks. Holds on tasks that have not settled are
 *                  not in here, so a day that is still in flight reads low and rises later
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsageByDay(LocalDate day, long calls, long succeeded, long failed, BigDecimal spend) {
}
