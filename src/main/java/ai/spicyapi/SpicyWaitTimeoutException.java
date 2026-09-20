package ai.spicyapi;

import java.time.Duration;
import java.util.Optional;

/**
 * Polling gave up before the task reached a terminal state.
 *
 * <p>This says nothing about the task, which is still running, still billable and still going
 * to finish. Persist {@link #taskId()} and reconcile it later, or take the result on a callback.
 */
public final class SpicyWaitTimeoutException extends SpicyException {

    private static final long serialVersionUID = 1L;

    /**
     * @serial the identifier of the task that was being polled
     */
    private final String taskId;
    private final transient Duration timeout;
    private final transient TaskRecord lastObserved;

    /**
     * @param taskId       the task that was being polled
     * @param timeout      the local polling budget that elapsed
     * @param lastObserved the last record fetched, or {@code null} if none was
     */
    public SpicyWaitTimeoutException(String taskId, Duration timeout, TaskRecord lastObserved) {
        super("task " + taskId + " did not reach a terminal state within the local "
                + timeout.toMillis() + "ms polling budget; its remote state is unknown");
        this.taskId = taskId;
        this.timeout = timeout;
        this.lastObserved = lastObserved;
    }

    /**
     * The task that is still running.
     *
     * @return the identifier of the task that is still running
     */
    public String taskId() {
        return taskId;
    }

    /**
     * The polling budget that elapsed.
     *
     * @return the duration the poll loop was allowed to run for
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * The last record fetched before giving up, when one was fetched at all.
     *
     * @return the last record fetched, when one was fetched
     */
    public Optional<TaskRecord> lastObserved() {
        return Optional.ofNullable(lastObserved);
    }
}
