package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * The service accepted a task. Acceptance is not completion.
 *
 * @param taskId        the identifier to poll, and the one to persist before anything else
 * @param state         {@code QUEUED} for a new submission; an idempotent replay reports the
 *                      original task's current state
 * @param estimatedCost funds held on acceptance, and the ceiling for this task's charge; unused
 *                      funds are released and nothing above the hold is collected later
 * @param deadlineAt    the service's execution deadline, fixed at acceptance and unrelated to
 *                      any local wait
 * @param sourceTaskId  present when this task was created by {@link SpicyClient#retryTask}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AcceptedTask(String taskId, TaskState state, BigDecimal estimatedCost, Instant deadlineAt,
                           String sourceTaskId) {

    /**
     * The task this one was retried from, when it was a retry.
     *
     * @return the source task identifier, or empty when this task was not a retry
     */
    public Optional<String> sourceTask() {
        return Optional.ofNullable(sourceTaskId);
    }
}
