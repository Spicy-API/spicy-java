package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A task as the service currently sees it.
 *
 * @param taskId           the task identifier
 * @param sourceTaskId     present when this task was created by an explicit retry
 * @param model            the endpoint this task ran on
 * @param state            the lifecycle state
 * @param input            normalized model input, omitted once retention has redacted it
 * @param output           the result, present once the task has produced one
 * @param errorCode        the identifier to branch on when a task failed
 * @param errorMessage     human-readable failure reason; prose, translated, never to be parsed
 * @param cost             the final charge once {@code settled}, otherwise the amount still held
 * @param settled          whether {@code cost} is final. On success the charge is capped at the
 *                         hold and unused funds are released; a failed or expired task releases
 *                         the hold in full
 * @param createdAt        when the task was accepted
 * @param deadlineAt       the service's execution deadline
 * @param completedAt      when the task reached a terminal state
 * @param contentState     whether the content is still stored: {@code present}, {@code expired}
 *                         (retention removed it) or {@code purged} (the account owner destroyed
 *                         it). The two removals are deliberately distinguished
 * @param contentRemovedBy {@code user} or {@code system}, present only once content is gone
 * @param purgedAt         when the content was destroyed, when it was
 * @param retention        the removal schedule for this task's content
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskRecord(String taskId, String sourceTaskId, String model, TaskState state, JsonNode input,
                         TaskOutput output, TaskErrorCode errorCode, String errorMessage, BigDecimal cost,
                         boolean settled, Instant createdAt, Instant deadlineAt, Instant completedAt,
                         String contentState, String contentRemovedBy, Instant purgedAt,
                         TaskRetention retention) {

    /**
     * Whether the task has finished, whatever the outcome.
     *
     * @return true when the task has finished, whatever the outcome
     */
    public boolean terminal() {
        return state != null && state.terminal();
    }

    /**
     * Whether the task finished with a result.
     *
     * @return true when the task finished with a result
     */
    public boolean succeeded() {
        return state == TaskState.SUCCEEDED;
    }

    /**
     * Generated artifacts, empty when there are none or the task has not finished.
     *
     * @return the generated artifacts, empty when there are none
     */
    public List<TaskAsset> assets() {
        return output == null ? List.of() : output.assets();
    }

    /**
     * The answer when the endpoint answers in text.
     *
     * <p>Worth checking before walking {@link #assets()}: a client that only looks for files
     * reports a perfectly successful transcription as having produced nothing.
     *
     * @return the text answer, when the endpoint answers in text
     */
    public Optional<String> outputText() {
        return output == null ? Optional.empty() : Optional.ofNullable(output.text());
    }

    /**
     * The failure identifier, present only on a failed task.
     *
     * @return the error code, or empty when the task did not fail
     */
    public Optional<TaskErrorCode> failure() {
        return Optional.ofNullable(errorCode);
    }

    /**
     * The task this one was retried from, when it was a retry.
     *
     * @return the source task identifier, or empty when this task was not a retry
     */
    public Optional<String> sourceTask() {
        return Optional.ofNullable(sourceTaskId);
    }
}
