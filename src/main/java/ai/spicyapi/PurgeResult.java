package ai.spicyapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * The outcome of destroying a task's content.
 *
 * @param taskId               the task whose content was destroyed
 * @param contentState         {@code purged} after a successful destroy and on any idempotent
 *                             repeat; still {@code present} means this call removed nothing and
 *                             may be retried later
 * @param purgedAt             time of the <em>first</em> destroy, not of this call
 * @param contentRemovedBy     {@code user} or {@code system}
 * @param billingRetained      always true. Destroying content never changes the ledger, the
 *                             charged amount or any other billing fact; the field exists because
 *                             it is the one thing callers misread about an endpoint named purge
 * @param mediaDeletionPending true while the background sweep is still removing stored objects,
 *                             which can lag {@code contentState} by about a minute
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PurgeResult(String taskId, String contentState, Instant purgedAt, String contentRemovedBy,
                          boolean billingRetained, boolean mediaDeletionPending) {

    /**
     * Whether the content is gone as far as the service is concerned.
     *
     * @return true when the content is gone as far as the service is concerned
     */
    public boolean purged() {
        return "purged".equals(contentState);
    }
}
