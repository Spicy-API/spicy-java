package ai.spicyapi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * A verified webhook delivery.
 *
 * <p>Scheme-independent on purpose: it describes what arrived, not how it was authenticated.
 *
 * @param taskId         the task the delivery concerns, taken from the field the declared
 *                       payload version puts it in
 * @param payloadVersion 1 or 2
 * @param taskRecord     the task record, present for payload version 2, whose body is the same
 *                       envelope and record that {@link SpicyClient#getTask} returns
 * @param body           the parsed body, for version 1 payloads and for any field this release
 *                       does not map
 */
public record WebhookDelivery(String taskId, int payloadVersion, TaskRecord taskRecord, JsonNode body) {

    /**
     * The task record, present only for payload version 2.
     *
     * @return the mapped record, or empty for a payload version 1 delivery
     */
    public Optional<TaskRecord> task() {
        return Optional.ofNullable(taskRecord);
    }

    /**
     * The delivery identifier to deduplicate on.
     *
     * <p>Deliveries are retried, so receivers must be idempotent. For version 2 the stable ID is
     * the envelope's {@code request_id}; version 1 carries none, and the task ID plus terminal
     * state is the best available substitute.
     *
     * @return the stable delivery identifier, present for payload version 2
     */
    public Optional<String> deliveryId() {
        JsonNode requestId = body.get("request_id");
        return requestId != null && requestId.isTextual() ? Optional.of(requestId.asText()) : Optional.empty();
    }
}
