package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * One row of task history: metadata only.
 *
 * <p>There is no input, no output, no signed media URL and no processing detail here. Read a
 * selected task back with {@link SpicyClient#getTask(String)} when any of that is needed; listing
 * is for finding the task, not for reading it.
 *
 * @param taskId      the task identifier, which is what {@link SpicyClient#getTask(String)} takes
 * @param model       the public model identifier the task ran on
 * @param state       the lifecycle state at the moment the page was read
 * @param cost        the final charge once {@code settled} is true, and the amount still held
 *                    before that. An unsettled row is a ceiling, not a bill
 * @param settled     whether {@code cost} is final
 * @param createdAt   when the task was accepted
 * @param deadlineAt  the service's execution deadline
 * @param completedAt when the task reached a terminal state; {@code null} while it is in flight
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskSummary(String taskId, String model, TaskState state, BigDecimal cost, boolean settled,
                          Instant createdAt, Instant deadlineAt, Instant completedAt) {

    /**
     * Whether the task has finished, whatever the outcome.
     *
     * @return true when the task has finished
     */
    public boolean terminal() {
        return state != null && state.terminal();
    }

    /**
     * When the task finished, when it has.
     *
     * @return the completion time, or empty while the task is still in flight
     */
    public Optional<Instant> completion() {
        return Optional.ofNullable(completedAt);
    }
}
